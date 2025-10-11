package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Generic implementation of Substrates.Conduit interface.
 *
 * <p>Routes emitted values from Channels (producers) to Pipes (consumers) via a shared queue.
 * Manages percepts created from channels via a Composer. Each percept corresponds to a subject
 * and shares a common queue for signal processing.
 *
 * <p><b>Data Flow:</b>
 * <ol>
 *   <li>Channel (producer) emits value → goes to shared queue</li>
 *   <li>Queue processor takes value → calls processEmission()</li>
 *   <li>processEmission() calls emitter.emit() (SourceImpl)</li>
 *   <li>Source dispatches to Subscribers → registered consumer Pipes receive emission</li>
 * </ol>
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Composes Channels into percepts on-demand via get(Name)</li>
 *   <li>Processes emissions asynchronously via queue processor thread</li>
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
    private final Source<E> eventSource; // Observable stream - external code subscribes to this
    private final BlockingQueue<Capture<E>> queue = new LinkedBlockingQueue<>(10000);
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

    @Override
    public Conduit<P, E> tap(java.util.function.Consumer<? super Conduit<P, E>> consumer) {
        java.util.Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return this;
    }

    private Thread startQueueProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Capture<E> capture = queue.take();
                    processEmission(capture);
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

    private void processEmission(Capture<E> capture) {
        // Get subscribers from the Source (Source only manages the subscriber list)
        SourceImpl<E> source = (SourceImpl<E>) eventSource;

        // For each subscriber, invoke accept() with the Channel's Subject and a Registrar
        for (Subscriber<E> subscriber : source.getSubscribers()) {
            // Collect pipes that the subscriber registers
            List<Pipe<E>> pipes = new java.util.concurrent.CopyOnWriteArrayList<>();

            // Call subscriber.accept() with Channel's Subject (from Capture)
            subscriber.accept(capture.subject(), new Registrar<E>() {
                @Override
                public void register(Pipe<E> pipe) {
                    pipes.add(pipe);
                }
            });

            // Emit to all registered pipes
            for (Pipe<E> pipe : pipes) {
                pipe.emit(capture.emission());
            }
        }
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
