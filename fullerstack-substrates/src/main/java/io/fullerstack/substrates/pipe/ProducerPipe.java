package io.fullerstack.substrates.pipe;

import io.fullerstack.substrates.capture.CaptureImpl;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.flow.TransformationPipeline;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * Producer-side pipe that emits values INTO the conduit system.
 *
 * <p><b>Role in Architecture:</b>
 * ProducerPipe represents the producer endpoint in the Producer-Consumer pattern.
 * It emits values INTO the conduit system, which then routes them to registered
 * ConsumerPipe instances via subscriber notifications.
 *
 * <p><b>Creation:</b>
 * Created by Channels when application code calls {@code conduit.get(name)} or
 * {@code channel.pipe()}. Each ProducerPipe is bound to a specific Channel's Subject,
 * preserving WHO emitted for subscriber routing.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, ProducerPipes post Scripts to the
 * Circuit's Queue. Each Script creates a Capture and invokes the subscriber notification callback.
 * This ensures all Conduits share the Circuit's single-threaded execution model
 * ("Virtual CPU Core" design principle).
 *
 * <p><b>Transformation Support:</b>
 * When created without a Flow, emissions pass through directly.
 * When created with a Flow, emissions are transformed (filtered, mapped, reduced, etc.)
 * before being posted as Scripts. Transformations execute on the emitting thread,
 * minimizing work in the Circuit's single-threaded queue processor.
 *
 * <p><b>Subject Propagation:</b> Each ProducerPipe knows its Channel's Subject, which is paired
 * with the emission value in a Capture within the Script. This preserves the context
 * of WHO emitted for delivery to ConsumerPipes via Subscribers.
 *
 * <p><b>Subscriber Notification:</b> Holds a subscriber notification callback provided by the Conduit
 * (via Channel at construction time). This callback routes emissions to the Conduit's registered
 * subscribers, who then dispatch to their ConsumerPipes.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 * @see ConsumerPipe
 */
public class ProducerPipe<E> implements Pipe<E> {

    private final Scheduler scheduler; // Circuit's scheduler (retained for potential future use)
    private final Subject<Channel<E>> channelSubject; // WHO this pipe belongs to
    private final Consumer<Capture<E, Channel<E>>> subscriberNotifier; // Callback to notify subscribers of emissions
    private final BooleanSupplier hasSubscribers; // Check for early subscriber optimization
    private final TransformationPipeline<E> flow; // TransformationPipeline for apply() and hasReachedLimit()

    /**
     * Creates a ProducerPipe without transformations.
     *
     * @param scheduler the Circuit's scheduler
     * @param channelSubject the Subject of the Channel this ProducerPipe belongs to
     * @param subscriberNotifier callback to notify subscribers of emissions
     * @param hasSubscribers subscriber check for early-exit optimization
     */
    public ProducerPipe(Scheduler scheduler, Subject<Channel<E>> channelSubject, Consumer<Capture<E, Channel<E>>> subscriberNotifier, BooleanSupplier hasSubscribers) {
        this(scheduler, channelSubject, subscriberNotifier, hasSubscribers, null);
    }

    /**
     * Creates a ProducerPipe with transformations defined by a Flow.
     *
     * @param scheduler the Circuit's scheduler
     * @param channelSubject the Subject of the Channel this ProducerPipe belongs to
     * @param subscriberNotifier callback to notify subscribers of emissions
     * @param hasSubscribers subscriber check for early-exit optimization
     * @param flow the transformation pipeline (null for no transformations)
     */
    public ProducerPipe(Scheduler scheduler, Subject<Channel<E>> channelSubject, Consumer<Capture<E, Channel<E>>> subscriberNotifier, BooleanSupplier hasSubscribers, TransformationPipeline<E> flow) {
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.channelSubject = Objects.requireNonNull(channelSubject, "Channel subject cannot be null");
        this.subscriberNotifier = Objects.requireNonNull(subscriberNotifier, "Subscriber notifier cannot be null");
        this.hasSubscribers = Objects.requireNonNull(hasSubscribers, "Subscriber check cannot be null");
        this.flow = flow;
    }

    @Override
    public void emit(E value) {
        if (flow == null) {
            // No transformations - post Script directly
            postScript(value);
        } else {
            // Apply transformations before posting
            E transformed = flow.apply(value);
            if (transformed != null) {
                // Transformation passed - post Script with result
                postScript(transformed);
            }
            // If null, emission was filtered out (by guard, diff, limit, etc.)
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
        if (!hasSubscribers.getAsBoolean()) {
            return;
        }

        // Post to Circuit's queue - ensures ordering guarantees
        scheduler.schedule(() -> {
            Capture<E, Channel<E>> capture = new CaptureImpl<>(channelSubject, value);
            subscriberNotifier.accept(capture);
        });
    }

}
