package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.pipe.ProducerPipe;
import io.fullerstack.substrates.flow.TransformationPipeline;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.conduit.TransformingConduit;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;
/**
 * Generic implementation of Substrates.Channel interface.
 *
 * <p>Provides a subject-based emission port that posts Scripts to Circuit's shared queue.
 *
 * <p><b>Parent Reference Pattern:</b>
 * Channel has a parent Conduit reference. When creating Pipes, Channel accesses:
 * <ul>
 *   <li>Circuit's scheduler via {@code conduit.getCircuit()}</li>
 *   <li>Emission handler via {@code conduit.emissionHandler()}</li>
 *   <li>Subscriber check via {@code conduit.hasSubscribers()}</li>
 * </ul>
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script invokes the emission handler callback, ensuring
 * all Conduits share the Circuit's single-threaded execution model.
 *
 * <p><b>Subject Hierarchy:</b>
 * Channel's Subject has Conduit's Subject as parent, enabling full path navigation:
 * Circuit → Conduit → Channel via {@code subject.enclosure()}.
 *
 * <p><b>Flow Consumer Support:</b>
 * If a Flow Consumer is configured, this Channel creates Pipes with transformation pipelines
 * (Flows) applied. All Channels from the same Conduit share the same transformation
 * pipeline, as configured at the Conduit level.
 *
 * <p><b>Pipe Caching:</b>
 * The first call to {@code pipe()} creates and caches a Pipe instance. Subsequent calls
 * return the same cached Pipe. This ensures that Flow state (emission counters, limit
 * tracking, reduce accumulators, diff last values) is shared across all emissions from
 * this Channel, preventing incorrect behavior where multiple Pipe instances would have
 * separate state.
 *
 * <p>Note: {@code pipe(Consumer<Flow>)} is NOT cached - each call creates a new Pipe with
 * fresh transformations, allowing different custom pipelines per call.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class EmissionChannel<E> implements Channel<E> {

    private final TransformingConduit<?, E> conduit; // Parent Conduit in hierarchy (provides Circuit + Subject)
    private final Subject<Channel<E>> channelSubject;
    private final Consumer<Flow<E>> flowConfigurer; // Optional transformation pipeline (nullable)

    // Cached Pipe instance - ensures Segment state (limits, accumulators, etc.) is shared
    // across multiple calls to pipe()
    private volatile Pipe<E> cachedPipe;

    /**
     * Creates a Channel with parent Conduit reference.
     *
     * @param channelName simple channel name (e.g., "sensor1") - hierarchy implicit through container
     * @param conduit parent Conduit (provides Circuit, scheduling, subscribers, and Subject hierarchy)
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    public EmissionChannel(Name channelName, TransformingConduit<?, E> conduit, Consumer<Flow<E>> flowConfigurer) {
        this.conduit = Objects.requireNonNull(conduit, "Conduit cannot be null");
        this.channelSubject = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            channelName,  // Simple name - hierarchy implicit through Extent.enclosure()
            LinkedState.empty(),
            Channel.class,
            conduit.subject()  // Parent Subject from parent Conduit
        );
        this.flowConfigurer = flowConfigurer; // Can be null
    }

    @Override
    public Subject subject() {
        return channelSubject;
    }

    @Override
    public Pipe<E> pipe() {
        // Return cached Pipe if it exists (ensures Flow state is shared)
        if (cachedPipe == null) {
            synchronized (this) {
                if (cachedPipe == null) {
                    // If Conduit has a Flow Consumer configured, apply it
                    if (flowConfigurer != null) {
                        cachedPipe = pipe(flowConfigurer);
                    } else {
                        // Otherwise, create a plain ProducerPipe with parent Conduit's capabilities
                        // Note: Circuit also implements Scheduler in our implementation (SequentialCircuit)
                        cachedPipe = new ProducerPipe<E>(
                            (Scheduler) conduit.getCircuit(), // Cast Circuit to Scheduler
                            channelSubject,
                            conduit.emissionHandler(),      // Emission handler from parent Conduit
                            conduit::hasSubscribers         // Subscriber check from parent Conduit
                        );
                    }
                }
            }
        }
        return cachedPipe;
    }

    @Override
    public Pipe<E> pipe(Consumer<? super Flow<E>> configurer) {
        Objects.requireNonNull(configurer, "Flow configurer cannot be null");

        // Create a Flow and apply the Consumer transformations
        TransformationPipeline<E> flow = new TransformationPipeline<>();
        configurer.accept(flow);

        // Return a ProducerPipe with parent Conduit's capabilities and Flow transformations
        // Note: Circuit also implements Scheduler in our implementation (SequentialCircuit)
        return new ProducerPipe<E>(
            (Scheduler) conduit.getCircuit(), // Cast Circuit to Scheduler
            channelSubject,
            conduit.emissionHandler(),      // Emission handler from parent Conduit
            conduit::hasSubscribers,        // Subscriber check from parent Conduit
            flow
        );
    }
}
