package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Generic implementation of Substrates.Conduit interface with Lombok for getters.
 *
 * <p>Routes emitted values from Channels (producers) to Pipes (consumers) via Circuit's shared queue.
 * Manages percepts created from channels via a Composer. Each percept corresponds to a subject
 * and shares the Circuit's queue for signal processing (single-threaded execution model).
 *
 * <p><b>Data Flow (Circuit Queue Architecture):</b>
 * <ol>
 *   <li>Channel (producer) emits value → posts Script to Circuit Queue</li>
 *   <li>Circuit Queue processor executes Script → calls processEmission()</li>
 *   <li>processEmission() invokes subscribers (Group 1: first emission only, Group 2: every emission)</li>
 *   <li>Subscribers register Pipes via Registrar → Pipes receive emissions</li>
 * </ol>
 *
 * <p><b>Single-Threaded Execution Model:</b>
 * All Conduits within a Circuit share the Circuit's single Queue. This ensures:
 * <ul>
 *   <li>Ordered delivery within Circuit domain</li>
 *   <li>QoS control (can prioritize certain Conduits)</li>
 *   <li>Prevents queue saturation</li>
 *   <li>Matches "Virtual CPU Core" design principle</li>
 * </ul>
 *
 * <p><b>Subscriber Invocation:</b>
 * <ul>
 *   <li>subscriber.accept(subject, registrar) called on <b>first emission from a Subject</b></li>
 *   <li>Registered pipes are cached per Subject per Subscriber</li>
 *   <li>Subsequent emissions reuse cached pipes (efficient multi-dispatch)</li>
 *   <li>Example: Hierarchical routing where pipes register parent pipes once</li>
 * </ul>
 *
 * @param <P> the percept type (e.g., Pipe<E>)
 * @param <E> the emission type (e.g., MonitorSignal)
 */
@Getter
public class ConduitImpl<P, E> implements Conduit<P, E> {

    private final Subject conduitSubject;
    private final Composer<? extends P, E> composer;
    private final Map<Name, P> percepts;
    private final Source<E> eventSource; // Observable stream - external code subscribes to this
    private final Scheduler scheduler; // Circuit scheduler for serialized execution
    private final Consumer<Flow<E>> flowConfigurer; // Optional transformation pipeline (nullable)

    /**
     * Creates a Conduit without transformations.
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> composer, Scheduler scheduler) {
        this(conduitName, composer, scheduler, null);
    }

    /**
     * Creates a Conduit with optional transformation pipeline.
     *
     * @param conduitName hierarchical conduit name (e.g., "circuit.conduit")
     * @param composer composer for creating percepts
     * @param scheduler circuit's scheduler for work execution
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> composer, Scheduler scheduler, Consumer<Flow<E>> flowConfigurer) {
        this.conduitSubject = new SubjectImpl<>(
            IdImpl.generate(),
            conduitName,
            StateImpl.empty(),
            Conduit.class
        );
        this.composer = composer;
        this.percepts = new ConcurrentHashMap<>();
        this.eventSource = new SourceImpl<>(conduitName);
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.flowConfigurer = flowConfigurer; // Can be null
    }

    @Override
    public Subject subject() {
        return conduitSubject;
    }

    public Source<E> source() {
        return eventSource;
    }

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        return eventSource.subscribe(subscriber);
    }

    @Override
    public P get(Name subject) {
        return percepts.computeIfAbsent(subject, s -> {
            // Build hierarchical channel name: circuit.conduit.channel
            Name hierarchicalChannelName = conduitSubject.name().name(s);
            // Pass Source and Scheduler - Channel will use the Circuit
            Channel<E> channel = new ChannelImpl<>(hierarchicalChannelName, scheduler, eventSource, flowConfigurer);
            return composer.compose(channel);
        });
    }

    @Override
    public Conduit<P, E> tap(Consumer<? super Conduit<P, E>> consumer) {
        java.util.Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return this;
    }

}
