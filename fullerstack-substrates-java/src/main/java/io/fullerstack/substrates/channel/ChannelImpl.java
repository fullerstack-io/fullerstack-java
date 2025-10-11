package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.PipeImpl;
import io.fullerstack.substrates.segment.SegmentImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;

/**
 * Generic implementation of Substrates.Channel interface.
 *
 * <p>Provides a subject-based emission port into a conduit's shared queue.
 *
 * <p>Each Channel has its own Subject identity (WHO), and when creating Pipes,
 * passes this Subject so that emissions can be paired with their source in a Capture.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class ChannelImpl<E> implements Channel<E> {

    private final Subject channelSubject;
    private final BlockingQueue<Capture<E>> queue;

    public ChannelImpl(Name channelName, BlockingQueue<Capture<E>> queue) {
        this.channelSubject = new SubjectImpl(
            IdImpl.generate(),
            channelName,
            StateImpl.empty(),
            Subject.Type.CHANNEL
        );
        this.queue = queue;
    }

    @Override
    public Subject subject() {
        return channelSubject;
    }

    @Override
    public Pipe<E> pipe() {
        return new PipeImpl<>(queue, channelSubject);
    }

    @Override
    public Pipe<E> pipe(Sequencer<? super Segment<E>> sequencer) {
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // Create a Segment and apply the Sequencer transformations
        SegmentImpl<E> segment = new SegmentImpl<>();
        sequencer.apply(segment);

        // Return a Pipe that applies the Segment transformations and knows its Subject
        return new PipeImpl<>(queue, channelSubject, segment);
    }
}
