package io.fullerstack.substrates.pipe;

import io.fullerstack.substrates.capture.CaptureImpl;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.segment.SegmentImpl;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;

/**
 * Implementation of Substrates.Pipe interface.
 *
 * <p>A Pipe provides typed emission with optional transformation support via Segment.
 *
 * <p>When created without a Segment, emissions pass through directly to the queue.
 * When created with a Segment, emissions are transformed (filtered, mapped, reduced, etc.)
 * before being forwarded to the queue.
 *
 * <p><b>Subject Propagation:</b> Each Pipe knows its Channel's Subject, which is paired
 * with the emission value in a Capture before being queued. This preserves the context
 * of WHO emitted for delivery to Subscribers.
 *
 * <p>Transformations are executed within the Circuit (not by the emitting thread),
 * following Humainary's design principle.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final BlockingQueue<Capture<E>> queue; // Queue stores Capture (Subject + value)
    private final Subject channelSubject; // WHO this pipe belongs to
    private final SegmentImpl<E> segment; // SegmentImpl for apply() and hasReachedLimit()

    /**
     * Creates a Pipe without transformations.
     *
     * @param queue the queue to emit to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     */
    public PipeImpl(BlockingQueue<Capture<E>> queue, Subject channelSubject) {
        this(queue, channelSubject, null);
    }

    /**
     * Creates a Pipe with transformations defined by a Segment.
     *
     * @param queue the queue to emit to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param segment the transformation pipeline (null for no transformations)
     */
    public PipeImpl(BlockingQueue<Capture<E>> queue, Subject channelSubject, SegmentImpl<E> segment) {
        this.queue = Objects.requireNonNull(queue, "Queue cannot be null");
        this.channelSubject = Objects.requireNonNull(channelSubject, "Channel subject cannot be null");
        this.segment = segment;
    }

    @Override
    public void emit(E value) {
        if (segment == null) {
            // No transformations - emit directly
            putOnQueue(value);
        } else {
            // Apply transformations
            if (segment.hasReachedLimit()) {
                // Limit reached - drop emission
                return;
            }

            E transformed = segment.apply(value);
            if (transformed != null) {
                // Transformation passed - emit result
                putOnQueue(transformed);
            }
            // If null, emission was filtered out
        }
    }

    /**
     * Puts a value on the queue as a Capture (pairing Subject with emission).
     *
     * <p>This preserves the context of WHO emitted so that Subscribers can
     * perform hierarchical routing based on the Channel's Subject.
     *
     * @param value the emission value
     */
    private void putOnQueue(E value) {
        try {
            Capture<E> capture = new CaptureImpl<>(channelSubject, value);
            queue.put(capture);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to emit to pipe", e);
        }
    }
}
