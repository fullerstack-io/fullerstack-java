package io.fullerstack.substrates.pipe;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.segment.SegmentImpl;

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
 * <p>Transformations are executed within the Circuit (not by the emitting thread),
 * following Humainary's design principle.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final BlockingQueue<E> queue; // Direct queue access
    private final SegmentImpl<E> segment; // SegmentImpl for apply() and hasReachedLimit()

    /**
     * Creates a Pipe without transformations.
     */
    public PipeImpl(BlockingQueue<E> queue) {
        this(queue, null);
    }

    /**
     * Creates a Pipe with transformations defined by a Segment.
     *
     * @param queue the queue to emit to
     * @param segment the transformation pipeline (null for no transformations)
     */
    public PipeImpl(BlockingQueue<E> queue, SegmentImpl<E> segment) {
        this.queue = queue;
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

    private void putOnQueue(E value) {
        try {
            queue.put(value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to emit to pipe", e);
        }
    }
}
