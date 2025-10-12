package io.fullerstack.substrates.pipe;

import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.segment.SegmentImpl;

import java.util.Objects;

/**
 * Implementation of Substrates.Pipe interface.
 *
 * <p>A Pipe provides typed emission with optional transformation support via Segment.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script creates a Capture and calls Source.notifySubscribers().
 * This ensures all Conduits share the Circuit's single-threaded execution model
 * ("Virtual CPU Core" design principle).
 *
 * <p>When created without a Segment, emissions pass through directly.
 * When created with a Segment, emissions are transformed (filtered, mapped, reduced, etc.)
 * before being posted as Scripts.
 *
 * <p><b>Subject Propagation:</b> Each Pipe knows its Channel's Subject, which is paired
 * with the emission value in a Capture within the Script. This preserves the context
 * of WHO emitted for delivery to Subscribers.
 *
 * <p><b>Direct Source Reference:</b> Holds a direct reference to Source, not a callback.
 * This eliminates callback passing through layers (Conduit → Channel → Pipe). The Pipe
 * directly notifies the Source when emissions occur, and Source owns the pipe cache for
 * subscriber management.
 *
 * <p>Transformations are executed by the emitting thread before posting the Script,
 * minimizing work in the Circuit's single-threaded queue processor.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final Queue circuitQueue; // Circuit's shared Queue for Scripts
    private final Subject channelSubject; // WHO this pipe belongs to
    private final Source<E> source; // Source to notify when emissions occur
    private final SegmentImpl<E> segment; // SegmentImpl for apply() and hasReachedLimit()

    /**
     * Creates a Pipe without transformations.
     *
     * @param circuitQueue the Circuit's Queue to post Scripts to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param source the Source to notify when emissions occur
     */
    public PipeImpl(Queue circuitQueue, Subject channelSubject, Source<E> source) {
        this(circuitQueue, channelSubject, source, null);
    }

    /**
     * Creates a Pipe with transformations defined by a Segment.
     *
     * @param circuitQueue the Circuit's Queue to post Scripts to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param source the Source to notify when emissions occur
     * @param segment the transformation pipeline (null for no transformations)
     */
    public PipeImpl(Queue circuitQueue, Subject channelSubject, Source<E> source, SegmentImpl<E> segment) {
        this.circuitQueue = Objects.requireNonNull(circuitQueue, "Circuit queue cannot be null");
        this.channelSubject = Objects.requireNonNull(channelSubject, "Channel subject cannot be null");
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.segment = segment;
    }

    @Override
    public void emit(E value) {
        if (segment == null) {
            // No transformations - post Script directly
            postScript(value);
        } else {
            // Apply transformations before posting
            if (segment.hasReachedLimit()) {
                // Limit reached - drop emission
                return;
            }

            E transformed = segment.apply(value);
            if (transformed != null) {
                // Transformation passed - post Script with result
                postScript(transformed);
            }
            // If null, emission was filtered out
        }
    }

    /**
     * Posts a Script to the Circuit Queue that will process the emission.
     *
     * <p>The Script creates a Capture (pairing Subject with emission) and calls
     * Source.notifySubscribers() directly. This ensures single-threaded execution within
     * the Circuit domain while avoiding circular dependencies and callback passing.
     *
     * @param value the emission value (after transformations, if any)
     */
    private void postScript(E value) {
        // Create Capture outside the Script (in emitting thread)
        Capture<E> capture = new CaptureImpl<>(channelSubject, value);

        // Post Script that calls Source.notifySubscribers() directly
        circuitQueue.post(current -> ((SourceImpl<E>) source).notifySubscribers(capture));
    }
}
