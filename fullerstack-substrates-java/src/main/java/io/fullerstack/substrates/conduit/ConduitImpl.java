package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

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
 * <p>Key behaviors:
 * <ul>
 *   <li>Composes Channels into percepts on-demand via get(Name)</li>
 *   <li>Emits events through Source when Channels emit</li>
 *   <li>Subscribers can observe all emissions via source().subscribe()</li>
 * </ul>
 *
 * @param <P> the percept type (e.g., Pipe<E>)
 * @param <E> the emission type (e.g., MonitorSignal)
 */
public class ConduitImpl<P, E> implements Conduit<P, E> {

    private final Subject conduitSubject;
    private final Composer<? extends P, E> composer;
    private final Map<Name, P> percepts = new ConcurrentHashMap<>();
    private final SourceImpl<E> eventSource;
    private final BlockingQueue<E> queue = new LinkedBlockingQueue<>(10000);
    private final Thread queueProcessor;

    public ConduitImpl(Name circuitName, Name conduitName, Composer<? extends P, E> composer) {
        this.conduitSubject = new SubjectImpl(
            IdImpl.generate(),
            conduitName,
            StateImpl.empty(),
            Subject.Type.CONDUIT
        );
        this.composer = composer;
        this.eventSource = new SourceImpl<>(conduitName);

        // Start queue processor
        this.queueProcessor = startQueueProcessor();
    }

    @Override
    public Subject subject() {
        return conduitSubject;
    }

    @Override
    public Source<E> source() {
        return eventSource;
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
                    System.err.println("Error processing emission in conduit " + conduitSubject.name() + ": " + e.getMessage());
                }
            }
        });
        processor.setDaemon(true);
        processor.setName("conduit-" + conduitSubject.name());
        processor.start();
        return processor;
    }

    private void processEmission(E emission) {
        // Emit through Source so subscribers can observe
        eventSource.emit(emission);
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
