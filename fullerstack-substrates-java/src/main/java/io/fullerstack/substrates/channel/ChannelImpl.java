package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.PipeImpl;
import io.fullerstack.substrates.segment.SegmentImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;

/**
 * Generic implementation of Substrates.Channel interface.
 *
 * <p>Provides a subject-based emission port that posts Scripts to Circuit's shared queue.
 *
 * <p>Each Channel has its own Subject identity (WHO), and when creating Pipes,
 * passes this Subject along with the Circuit Queue and a direct Source reference.
 * This completely decouples Channel from Conduit implementation while maintaining
 * the emission flow.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script calls Source.notifySubscribers() directly, ensuring
 * all Conduits share the Circuit's single-threaded execution model.
 *
 * <p><b>Direct Source Reference:</b>
 * Holds a direct reference to Source instead of a callback. This eliminates callback
 * passing through layers (Conduit → Channel → Pipe). The Pipe directly notifies the
 * Source when emissions occur, and Source owns the pipe cache for subscriber management.
 *
 * <p><b>Sequencer Support:</b>
 * If a Sequencer is configured, this Channel creates Pipes with transformation pipelines
 * (Segments) applied. All Channels from the same Conduit share the same transformation
 * pipeline, as configured at the Conduit level.
 *
 * <p><b>Pipe Caching:</b>
 * The first call to {@code pipe()} creates and caches a Pipe instance. Subsequent calls
 * return the same cached Pipe. This ensures that Segment state (emission counters, limit
 * tracking, reduce accumulators, diff last values) is shared across all emissions from
 * this Channel, preventing incorrect behavior where multiple Pipe instances would have
 * separate state.
 *
 * <p>Note: {@code pipe(Sequencer)} is NOT cached - each call creates a new Pipe with
 * fresh transformations, allowing different custom pipelines per call.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class ChannelImpl<E> implements Channel<E> {

    private final Subject channelSubject;
    private final Queue circuitQueue;
    private final Source<E> source; // Direct Source reference for emission routing
    private final Sequencer<Segment<E>> sequencer; // Optional transformation pipeline (nullable)

    // Cached Pipe instance - ensures Segment state (limits, accumulators, etc.) is shared
    // across multiple calls to pipe()
    private volatile Pipe<E> cachedPipe;

    /**
     * Creates a Channel with direct Source reference.
     *
     * @param channelName hierarchical channel name (e.g., "circuit.conduit.channel")
     * @param circuitQueue circuit's shared queue
     * @param source the Source to notify when emissions occur
     * @param sequencer optional transformation pipeline (null if no transformations)
     */
    public ChannelImpl(Name channelName, Queue circuitQueue, Source<E> source, Sequencer<Segment<E>> sequencer) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.channelSubject = new SubjectImpl(
            IdImpl.generate(),
            channelName,  // Already hierarchical (circuit.conduit.channel)
            StateImpl.empty(),
            Subject.Type.CHANNEL
        );
        this.circuitQueue = Objects.requireNonNull(circuitQueue, "Circuit queue cannot be null");
        this.sequencer = sequencer; // Can be null
    }

    @Override
    public Subject subject() {
        return channelSubject;
    }

    @Override
    public Pipe<E> pipe() {
        // Return cached Pipe if it exists (ensures Segment state is shared)
        if (cachedPipe == null) {
            synchronized (this) {
                if (cachedPipe == null) {
                    // If Conduit has a Sequencer configured, apply it
                    if (sequencer != null) {
                        cachedPipe = pipe(sequencer);
                    } else {
                        // Otherwise, create a plain Pipe with direct Source reference
                        cachedPipe = new PipeImpl<>(circuitQueue, channelSubject, source);
                    }
                }
            }
        }
        return cachedPipe;
    }

    @Override
    public Pipe<E> pipe(Sequencer<? super Segment<E>> sequencer) {
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // Create a Segment and apply the Sequencer transformations
        SegmentImpl<E> segment = new SegmentImpl<>();
        sequencer.apply(segment);

        // Return a Pipe with direct Source reference and Segment transformations
        return new PipeImpl<>(circuitQueue, channelSubject, source, segment);
    }
}
