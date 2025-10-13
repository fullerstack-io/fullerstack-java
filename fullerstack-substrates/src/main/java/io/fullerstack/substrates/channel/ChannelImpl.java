package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.PipeImpl;
import io.fullerstack.substrates.segment.SegmentImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generic implementation of Substrates.Channel interface.
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
     * Creates a Channel with Source reference for emission handler coordination.
     *
     * @param channelName hierarchical channel name (e.g., "circuit.conduit.channel")
     * @param circuitQueue circuit's shared queue
     * @param source sibling Source (provides emission handler for Pipe creation)
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
                        // Otherwise, create a plain Pipe with emission handler from Source
                        Consumer<Capture<E>> handler = ((SourceImpl<E>) source).emissionHandler();
                        cachedPipe = new PipeImpl<>(circuitQueue, channelSubject, handler);
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

        // Get emission handler from Source (sibling coordination)
        Consumer<Capture<E>> handler = ((SourceImpl<E>) source).emissionHandler();

        // Return a Pipe with emission handler and Segment transformations
        return new PipeImpl<>(circuitQueue, channelSubject, handler, segment);
    }
}
