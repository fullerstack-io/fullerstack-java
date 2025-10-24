package io.fullerstack.substrates.sink;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.name.HierarchicalName;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Implementation of Substrates.Sink for buffering and draining emissions.
 *
 * <p>Sink accumulates Capture events from a Source and provides them via drain().
 * Each call to drain() returns accumulated events since the last drain (or creation)
 * and clears the buffer.
 *
 * <p>Thread-safe implementation using CopyOnWriteArrayList for concurrent access.
 *
 * @param <E> the emission type
 * @see Sink
 * @see Source
 * @see Capture
 */
public class CollectingSink<E> implements Sink<E> {

    private final Subject<Sink<E>> sinkSubject;
    private final Source<E> source;
    private final List<Capture<E, Channel<E>>> buffer = new CopyOnWriteArrayList<>();
    private final Subscription subscription;
    private volatile boolean closed = false;

    // Cache the internal subscriber's Subject - represents persistent identity
    private final Subject<Subscriber<E>> internalSubscriberSubject;

    /**
     * Creates a Sink that subscribes to the given Source.
     *
     * @param source the source to subscribe to
     * @throws NullPointerException if source is null
     */
    @SuppressWarnings("unchecked")
    public CollectingSink(Source<E> source) {
        Objects.requireNonNull(source, "Source cannot be null");

        // Using HierarchicalName.of() static factory
        this.source = source;
        Id sinkId = UuidIdentifier.generate();
        this.sinkSubject = new HierarchicalSubject<>(
            sinkId,
            HierarchicalName.of("sink").name(sinkId.toString()),
            LinkedState.empty(),
            (Class<Sink<E>>) (Class<?>) Sink.class
        );

        // Create internal subscriber's Subject once
        this.internalSubscriberSubject = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            HierarchicalName.of("sink-subscriber"),
            LinkedState.empty(),
            (Class<Subscriber<E>>) (Class<?>) Subscriber.class
        );

        // Subscribe to source and buffer all emissions
        this.subscription = source.subscribe(new Subscriber<E>() {
            @Override
            public Subject subject() {
                return internalSubscriberSubject;
            }

            @Override
            public void accept(Subject<Channel<E>> subject, Registrar<E> registrar) {
                // Register a pipe that captures emissions into the buffer
                registrar.register(emission -> {
                    if (!closed) {
                        buffer.add(new CaptureImpl<>(subject, emission));
                    }
                });
            }
        });
    }

    @Override
    public Subject subject() {
        return sinkSubject;
    }

    @Override
    public Stream<Capture<E, Channel<E>>> drain() {
        // Get all accumulated captures and clear the buffer
        List<Capture<E, Channel<E>>> captured = List.copyOf(buffer);
        buffer.clear();
        return captured.stream();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            subscription.close();
            buffer.clear();
        }
    }
}
