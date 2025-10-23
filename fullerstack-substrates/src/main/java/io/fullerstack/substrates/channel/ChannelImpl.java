package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.ProducerPipe;
import io.fullerstack.substrates.flow.FlowImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
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
    // Emission handler callback from Conduit (which IS-A Source)
    private final Consumer<Capture<E, Channel<E>>> emissionHandler;
    // Subscriber check from Conduit for early-exit optimization
    private final BooleanSupplier hasSubscribers;
    private final Consumer<Flow<E>> flowConfigurer; // Optional transformation pipeline (nullable)

    // Cached Pipe instance - ensures Segment state (limits, accumulators, etc.) is shared
    // across multiple calls to pipe()
    private volatile Pipe<E> cachedPipe;

    /**
     * Creates a Channel with emission handler callback from Conduit.
     *
     * @param channelName hierarchical channel name (e.g., "circuit.conduit.channel")
     * @param scheduler circuit's scheduler
     * @param emissionHandler callback to notify Conduit of emissions (Conduit IS-A Source)
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    public ChannelImpl(Name channelName, Scheduler scheduler, Consumer<Capture<E, Channel<E>>> emissionHandler, Consumer<Flow<E>> flowConfigurer) {
        this(channelName, scheduler, emissionHandler, () -> true, flowConfigurer);
    }

    /**
     * Creates a Channel with emission handler and subscriber check from Conduit.
     *
     * @param channelName hierarchical channel name (e.g., "circuit.conduit.channel")
     * @param scheduler circuit's scheduler
     * @param emissionHandler callback to notify Conduit of emissions
     * @param hasSubscribers check if any subscribers exist (early-exit optimization)
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    public ChannelImpl(Name channelName, Scheduler scheduler, Consumer<Capture<E, Channel<E>>> emissionHandler,
                      BooleanSupplier hasSubscribers, Consumer<Flow<E>> flowConfigurer) {
        this.emissionHandler = Objects.requireNonNull(emissionHandler, "Emission handler cannot be null");
        this.hasSubscribers = Objects.requireNonNull(hasSubscribers, "Subscriber check cannot be null");
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
                        // Otherwise, create a plain ProducerPipe with subscriber notifier from Conduit
                        cachedPipe = new ProducerPipe<>(scheduler, channelSubject, emissionHandler, hasSubscribers);
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

        // Return a ProducerPipe with subscriber notifier from Conduit and Flow transformations
        return new ProducerPipe<>(scheduler, channelSubject, emissionHandler, hasSubscribers, flow);
    }
}
