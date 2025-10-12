package io.fullerstack.substrates.sink;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;

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
public class SinkImpl<E> implements Sink<E> {

    private final Subject sinkSubject;
    private final Source<E> source;
    private final List<Capture<E>> buffer = new CopyOnWriteArrayList<>();
    private final Subscription subscription;
    private volatile boolean closed = false;

    /**
     * Creates a Sink that subscribes to the given Source.
     *
     * @param source the source to subscribe to
     * @throws NullPointerException if source is null
     */
    public SinkImpl(Source<E> source) {
        Objects.requireNonNull(source, "Source cannot be null");

        this.source = source;
        Id sinkId = IdImpl.generate();
        this.sinkSubject = new SubjectImpl(
            sinkId,
            NameImpl.of("sink").name(sinkId.toString()),
            StateImpl.empty(),
            Subject.Type.SINK
        );

        // Subscribe to source and buffer all emissions
        this.subscription = source.subscribe(new Subscriber<E>() {
            @Override
            public Subject subject() {
                return new SubjectImpl(
                    IdImpl.generate(),
                    NameImpl.of("sink-subscriber"),
                    StateImpl.empty(),
                    Subject.Type.SUBSCRIBER
                );
            }

            @Override
            public void accept(Subject subject, Registrar<E> registrar) {
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
    public Stream<Capture<E>> drain() {
        // Get all accumulated captures and clear the buffer
        List<Capture<E>> captured = List.copyOf(buffer);
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
