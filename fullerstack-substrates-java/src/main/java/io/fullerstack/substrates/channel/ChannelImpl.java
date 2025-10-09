package io.fullerstack.substrates.channel;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.pipe.PipeImpl;

import java.util.concurrent.BlockingQueue;

/**
 * Generic implementation of Substrates.Channel interface.
 *
 * <p>Provides a subject-based emission port into a conduit's shared queue.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class ChannelImpl<E> implements Channel<E> {

    private final Name channelName;
    private final BlockingQueue<E> queue;

    public ChannelImpl(Name subject, BlockingQueue<E> queue) {
        this.channelName = subject;
        this.queue = queue;
    }

    @Override
    public Subject subject() {
        // TODO Story 4.3: Create proper Subject implementation
        return new Subject() {
            @Override public Id id() { return new Id() {}; }  // Empty marker interface
            @Override public Name name() { return channelName; }
            @Override public State state() { return null; }  // TODO Story 4.3: Implement state management
            @Override public Type type() { return Type.CHANNEL; }
            @Override public CharSequence part() { return channelName.part(); }
        };
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
     */
    public void emit(E emission) {
        try {
            queue.put(emission);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to emit to channel: " + channelName, e);
        }
    }
}
