package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pipe.PipeImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.concurrent.BlockingQueue;

/**
 * Generic implementation of Substrates.Channel interface.
 *
 * <p>Provides a subject-based emission port into a conduit's shared queue.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class ChannelImpl<E> implements Channel<E> {

    private final Subject channelSubject;
    private final BlockingQueue<E> queue;

    public ChannelImpl(Name channelName, BlockingQueue<E> queue) {
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
        return new PipeImpl<>(this);
    }

    @Override
    public Pipe<E> pipe(Sequencer<? super Segment<E>> sequencer) {
        // TODO: Implement sequencer support for ordered emission
        // For now, return simple pipe
        return new PipeImpl<>(this);
    }

    /**
     * Emit directly to the queue (used by Pipe).
     * The queue is shared with the Conduit, which will process and forward to its Source.
     */
    public void emit(E emission) {
        try {
            queue.put(emission);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to emit to channel: " + channelSubject.name(), e);
        }
    }
}
