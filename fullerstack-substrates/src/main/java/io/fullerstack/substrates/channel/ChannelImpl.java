package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.PipeImpl;
import io.fullerstack.substrates.flow.FlowImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;
/**
 * Generic implementation of Substrates.Channel interface with Lombok for getters.
 *
 * <p>Provides a subject-based emission port that posts Scripts to Circuit's shared queue.
 *
 * <p>Each Channel has its own Subject identity (WHO). When creating inlet Pipes,
 * Channel requests an emission handler from Source (its sibling) and passes it to
 * the Pipe along with the Circuit Queue and Channel Subject.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script invokes the emission handler callback, ensuring
 * all Conduits share the Circuit's single-threaded execution model.
 *
 * <p><b>Sibling Coordination:</b>
 * Channel and Source are siblings owned by Conduit. Channel gets the emission handler
 * from Source via {@link SourceImpl#emissionHandler()} when creating inlet Pipes. This
 * is NOT callback passing through layers - it's dependency injection at construction time
 * between siblings. Source's distribution logic remains private.
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
public class ChannelImpl<E> implements Channel<E> {

    private final Subject<Channel<E>> channelSubject;
    private final Scheduler scheduler;
    // M17: Source is sealed, using SourceImpl directly
    private final SourceImpl<E> source; // Direct Source reference for emission routing
    private final Consumer<Flow<E>> flowConfigurer; // Optional transformation pipeline (nullable)

    // Cached Pipe instance - ensures Segment state (limits, accumulators, etc.) is shared
    // across multiple calls to pipe()
    private volatile Pipe<E> cachedPipe;

    /**
     * Creates a Channel with Source reference for emission handler coordination.
     *
     * @param channelName hierarchical channel name (e.g., "circuit.conduit.channel")
     * @param scheduler circuit's scheduler
     * @param source sibling Source (provides emission handler for Pipe creation)
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    // M17: Source is sealed, constructor now takes SourceImpl
    public ChannelImpl(Name channelName, Scheduler scheduler, SourceImpl<E> source, Consumer<Flow<E>> flowConfigurer) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.channelSubject = new SubjectImpl<>(
            IdImpl.generate(),
            channelName,  // Already hierarchical (circuit.conduit.channel)
            StateImpl.empty(),
            Channel.class
        );
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
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
                        // Otherwise, create a plain Pipe with emission handler from Source
                        SourceImpl<E> sourceImpl = (SourceImpl<E>) source;
                        Consumer<Capture<E, Channel<E>>> handler = sourceImpl.emissionHandler();
                        cachedPipe = new PipeImpl<>(scheduler, channelSubject, handler, sourceImpl);
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
        FlowImpl<E> flow = new FlowImpl<>();
        configurer.accept(flow);

        // Get emission handler from Source (sibling coordination)
        SourceImpl<E> sourceImpl = (SourceImpl<E>) source;
        Consumer<Capture<E, Channel<E>>> handler = sourceImpl.emissionHandler();

        // Return a Pipe with emission handler, Source reference, and Flow transformations
        return new PipeImpl<>(scheduler, channelSubject, handler, sourceImpl, flow);
    }
}
