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
 *   <li>processEmission() invokes subscribers (Group 1: first emission only, Group 2: every emission)</li>
 *   <li>Subscribers register Pipes via Registrar → Pipes receive emissions</li>
 * </ol>
 *
 * <p><b>Subscriber Invocation (Group 1):</b>
 * <ul>
 *   <li>subscriber.accept(subject, registrar) called on <b>first emission from a Subject</b></li>
 *   <li>Registered pipes are cached per Subject per Subscriber</li>
 *   <li>Subsequent emissions reuse cached pipes (efficient multi-dispatch)</li>
 *   <li>Example: Hierarchical routing where pipes register parent pipes once</li>
 * </ul>
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Composes Channels into percepts on-demand via get(Name)</li>
 *   <li>Processes emissions asynchronously via queue processor thread</li>
 *   <li>Caches pipe registrations for efficiency (29 ns per leaf emit on Apple M4)</li>
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

    // Cache: Subject Name -> Subscriber -> List of registered Pipes
    // Pipes are registered only once per Subject per Subscriber (on first emission)
    private final Map<Name, Map<Subscriber<E>, List<Pipe<E>>>> pipeCache = new ConcurrentHashMap<>();

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
        SourceImpl<E> source = (SourceImpl<E>) eventSource;
        Subject emittingSubject = capture.subject();
        Name subjectName = emittingSubject.name();

        // Get or create the subscriber->pipes map for this Subject
        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes = pipeCache.computeIfAbsent(
            subjectName,
            name -> new ConcurrentHashMap<>()
        );

        // For each subscriber, get cached pipes or register new ones (first emission only)
        for (Subscriber<E> subscriber : source.getSubscribers()) {
            List<Pipe<E>> pipes = subscriberPipes.computeIfAbsent(subscriber, sub -> {
                // First emission from this Subject - call subscriber.accept() to register pipes
                List<Pipe<E>> registeredPipes = new CopyOnWriteArrayList<>();

                sub.accept(emittingSubject, new Registrar<E>() {
                    @Override
                    public void register(Pipe<E> pipe) {
                        registeredPipes.add(pipe);
                    }
                });

                return registeredPipes;
            });

            // Emit to all registered pipes (cached or newly registered)
            for (Pipe<E> pipe : pipes) {
                pipe.emit(capture.emission());
            }
        }
    }

}
