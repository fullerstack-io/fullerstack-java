package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic implementation of Substrates.Conduit interface.
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
public class ConduitImpl<P, E> implements Conduit<P, E> {

    private final Subject conduitSubject;
    private final Composer<? extends P, E> composer;
    private final Map<Name, P> percepts = new ConcurrentHashMap<>();
    private final Source<E> eventSource; // Observable stream - external code subscribes to this
    private final Queue circuitQueue; // Shared Circuit Queue (single-threaded execution)
    private final Sequencer<Segment<E>> sequencer; // Optional transformation pipeline (nullable)

    /**
     * Creates a Conduit without transformations.
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> composer, Queue circuitQueue) {
        this(conduitName, composer, circuitQueue, null);
    }

    /**
     * Creates a Conduit with optional transformation pipeline.
     *
     * @param conduitName hierarchical conduit name (e.g., "circuit.conduit")
     * @param composer composer for creating percepts
     * @param circuitQueue circuit's shared queue
     * @param sequencer optional transformation pipeline (null if no transformations)
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> composer, Queue circuitQueue, Sequencer<Segment<E>> sequencer) {
        this.conduitSubject = new SubjectImpl(
            IdImpl.generate(),
            conduitName,
            StateImpl.empty(),
            Subject.Type.CONDUIT
        );
        this.composer = composer;
        this.eventSource = new SourceImpl<>(conduitName);
        this.circuitQueue = java.util.Objects.requireNonNull(circuitQueue, "Circuit queue cannot be null");
        this.sequencer = sequencer; // Can be null
    }

    @Override
    public Subject subject() {
        return conduitSubject;
    }

    @Override
    public Source<E> source() {
        return eventSource;
    }

    @Override
    public P get(Name subject) {
        return percepts.computeIfAbsent(subject, s -> {
            // Build hierarchical channel name: circuit.conduit.channel
            Name hierarchicalChannelName = conduitSubject.name().name(s);
            // Pass Source directly - eliminates callback passing through layers
            Channel<E> channel = new ChannelImpl<>(hierarchicalChannelName, circuitQueue, eventSource, sequencer);
            return composer.compose(channel);
        });
    }

    @Override
    public Conduit<P, E> tap(java.util.function.Consumer<? super Conduit<P, E>> consumer) {
        java.util.Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return this;
    }

}
