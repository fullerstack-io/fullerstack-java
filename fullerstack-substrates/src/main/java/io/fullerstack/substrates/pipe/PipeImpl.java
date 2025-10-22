package io.fullerstack.substrates.pipe;

import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.flow.FlowImpl;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implementation of Substrates.Pipe interface with Lombok for getters.
 *
 * <p>A Pipe provides typed emission with optional transformation support via Flow.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script creates a Capture and invokes the emission handler callback.
 * This ensures all Conduits share the Circuit's single-threaded execution model
 * ("Virtual CPU Core" design principle).
 *
 * <p>When created without a Flow, emissions pass through directly.
 * When created with a Flow, emissions are transformed (filtered, mapped, reduced, etc.)
 * before being posted as Scripts.
 *
 * <p><b>Subject Propagation:</b> Each Pipe knows its Channel's Subject, which is paired
 * with the emission value in a Capture within the Script. This preserves the context
 * of WHO emitted for delivery to Subscribers.
 *
 * <p><b>Sibling Coordination:</b> Holds an emission handler callback provided by Source
 * (via Channel). Source and Channel are siblings owned by Conduit. This is NOT callback
 * passing through layers - it's dependency injection at construction time. The callback
 * routes emissions to Source's private distribution logic.
 *
 * <p>Transformations are executed by the emitting thread before posting the Script,
 * minimizing work in the Circuit's single-threaded queue processor.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final Scheduler scheduler; // Circuit's scheduler (retained for potential future use)
    private final Subject<Channel<E>> channelSubject; // WHO this pipe belongs to
    private final Consumer<Capture<E, Channel<E>>> emissionHandler; // Callback to route emissions to Source
    private final SourceImpl<E> source; // Source reference for early subscriber check optimization
    private final FlowImpl<E> flow; // FlowImpl for apply() and hasReachedLimit()

    /**
     * Creates a Pipe without transformations.
     *
     * @param scheduler the Circuit's scheduler
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param emissionHandler callback to route emissions (provided by Source)
     * @param source the Source instance for subscriber check optimization
     */
    public PipeImpl(Scheduler scheduler, Subject<Channel<E>> channelSubject, Consumer<Capture<E, Channel<E>>> emissionHandler, SourceImpl<E> source) {
        this(scheduler, channelSubject, emissionHandler, source, null);
    }

    /**
     * Creates a Pipe with transformations defined by a Flow.
     *
     * @param scheduler the Circuit's scheduler
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param emissionHandler callback to route emissions (provided by Source)
     * @param source the Source instance for subscriber check optimization
     * @param flow the transformation pipeline (null for no transformations)
     */
    public PipeImpl(Scheduler scheduler, Subject<Channel<E>> channelSubject, Consumer<Capture<E, Channel<E>>> emissionHandler, SourceImpl<E> source, FlowImpl<E> flow) {
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.channelSubject = Objects.requireNonNull(channelSubject, "Channel subject cannot be null");
        this.emissionHandler = Objects.requireNonNull(emissionHandler, "Emission handler cannot be null");
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.flow = flow;
    }

    @Override
    public void emit(E value) {
        if (flow == null) {
            // No transformations - post Script directly
            postScript(value);
        } else {
            // Apply transformations before posting
            if (flow.hasReachedLimit()) {
                // Limit reached - drop emission
                return;
            }

            E transformed = flow.apply(value);
            if (transformed != null) {
                // Transformation passed - post Script with result
                postScript(transformed);
            }
            // If null, emission was filtered out
        }
    }

    /**
     * Posts emission to Circuit's queue for ordered processing.
     *
     * <p><b>Architecture:</b> Emissions are posted as Scripts to the Circuit's queue.
     * Each Script creates a Capture and invokes subscriber callbacks in the Circuit's
     * single-threaded execution context. This ensures ordering guarantees as specified
     * by the Substrates API.
     *
     * <p><b>OPTIMIZATION:</b> Early exit if no subscribers - avoids allocating Capture
     * and posting to queue when no subscribers are registered.
     *
     * @param value the emission value (after transformations, if any)
     */
    private void postScript(E value) {
        // OPTIMIZATION: Early exit if no subscribers
        // Avoids: Capture allocation + queue posting when nothing is listening
        if (!source.hasSubscribers()) {
            return;
        }

        // Post to Circuit's queue - ensures ordering guarantees
        scheduler.schedule(() -> {
            Capture<E, Channel<E>> capture = new CaptureImpl<>(channelSubject, value);
            emissionHandler.accept(capture);
        });
    }

}
