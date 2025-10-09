package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Generic implementation of Substrates.Conduit interface.
 *
 * <p>Manages percepts created from channels via a Composer. Each percept corresponds to a subject
 * and shares a common queue for signal processing.
 *
 * @param <P> the percept type (e.g., Pipe<E>)
 * @param <E> the emission type (e.g., MonitorSignal)
 */
public class ConduitImpl<P, E> implements Conduit<P, E> {

    private final Name circuitName;
    private final Name conduitName;
    private final Composer<? extends P, E> composer;
    private final Map<Name, P> percepts = new ConcurrentHashMap<>();
    private final BlockingQueue<E> queue = new LinkedBlockingQueue<>(10000);
    private final Thread queueProcessor;

    public ConduitImpl(Name circuitName, Name conduitName, Composer<? extends P, E> composer) {
        this.circuitName = circuitName;
        this.conduitName = conduitName;
        this.composer = composer;

        // Start queue processor
        this.queueProcessor = startQueueProcessor();
    }

    @Override
    public Subject subject() {
        // TODO Story 4.3: Create proper Subject implementation
        return new Subject() {
            @Override public Id id() { return new Id() {}; }  // Empty marker interface
            @Override public Name name() { return conduitName; }
            @Override public State state() { return null; }  // TODO Story 4.3: Implement state management
            @Override public Type type() { return Type.CONDUIT; }
            @Override public CharSequence part() { return conduitName.part(); }
        };
    }

    @Override
    public Source<E> source() {
        // TODO Story 4.3: Implement source for pull-based consumption
        throw new UnsupportedOperationException("Source pattern not yet implemented");
    }

    @Override
    public P get(Name subject) {
        return percepts.computeIfAbsent(subject, s -> {
            Channel<E> channel = new ChannelImpl<>(s, queue);
            return composer.compose(channel);
        });
    }

    private Thread startQueueProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    E emission = queue.take();
                    processEmission(emission);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Log error but continue processing (using System.err as this is pure runtime)
                    System.err.println("Error processing emission in conduit " + conduitName + ": " + e.getMessage());
                }
            }
        });
        processor.setDaemon(true);
        processor.setName("conduit-" + conduitName);
        processor.start();
        return processor;
    }

    private void processEmission(E emission) {
        // TODO Story 4.12: Wire to actual sink
        // For now, just process the emission through the queue
    }

    /**
     * Stops the queue processor thread.
     */
    public void shutdown() {
        if (queueProcessor != null && queueProcessor.isAlive()) {
            queueProcessor.interrupt();
        }
    }
}
