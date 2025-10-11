package io.fullerstack.substrates.subscriber;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Implementation of Substrates.Subscriber interface.
 *
 * <p>Subscriber connects one or more Pipes with emitting Subjects within a Source.
 * This implementation supports two patterns:
 * <ul>
 *   <li><b>Function-based:</b> Uses a BiConsumer to handle (Subject, Registrar) callbacks</li>
 *   <li><b>Pool-based:</b> Uses a Pool to retrieve Pipes for specific Subjects</li>
 * </ul>
 *
 * <p><b>Function-Based Pattern:</b>
 * <pre>
 * Subscriber&lt;String&gt; subscriber = new SubscriberImpl&lt;&gt;(
 *   name,
 *   (subject, registrar) -&gt; {
 *     // Register pipes based on Subject
 *     registrar.register(conduit.get(subject.name()));
 *   }
 * );
 * </pre>
 *
 * <p><b>Pool-Based Pattern:</b>
 * <pre>
 * Pool&lt;Pipe&lt;String&gt;&gt; pipePool = name -&gt; conduit.get(name);
 * Subscriber&lt;String&gt; subscriber = new SubscriberImpl&lt;&gt;(name, pipePool);
 * </pre>
 *
 * <p>When a Subject emits, the Source calls accept(subject, registrar), and the
 * Subscriber registers the appropriate Pipes to receive the emission.
 *
 * @param <E> the emission type
 * @see Subscriber
 * @see Registrar
 */
public class SubscriberImpl<E> implements Subscriber<E> {

    private final Subject subscriberSubject;
    private final BiConsumer<Subject, Registrar<E>> handler;
    private final Pool<? extends Pipe<E>> pool;

    /**
     * Creates a function-based Subscriber.
     *
     * @param name the subscriber name
     * @param handler the callback function that receives (Subject, Registrar)
     * @throws NullPointerException if name or handler is null
     */
    public SubscriberImpl(Name name, BiConsumer<Subject, Registrar<E>> handler) {
        Objects.requireNonNull(name, "Subscriber name cannot be null");
        Objects.requireNonNull(handler, "Subscriber handler cannot be null");

        this.subscriberSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SUBSCRIBER
        );
        this.handler = handler;
        this.pool = null;
    }

    /**
     * Creates a pool-based Subscriber.
     *
     * <p>When a Subject emits, this Subscriber retrieves a Pipe from the pool
     * using the Subject's name and registers it to receive the emission.
     *
     * @param name the subscriber name
     * @param pool the pool of Pipes keyed by Subject name
     * @throws NullPointerException if name or pool is null
     */
    public SubscriberImpl(Name name, Pool<? extends Pipe<E>> pool) {
        Objects.requireNonNull(name, "Subscriber name cannot be null");
        Objects.requireNonNull(pool, "Pipe pool cannot be null");

        this.subscriberSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SUBSCRIBER
        );
        this.pool = pool;
        this.handler = null;
    }

    @Override
    public Subject subject() {
        return subscriberSubject;
    }

    @Override
    public void accept(Subject subject, Registrar<E> registrar) {
        if (handler != null) {
            // Function-based: delegate to user-provided handler
            handler.accept(subject, registrar);
        } else if (pool != null) {
            // Pool-based: get pipe from pool and register it
            Pipe<E> pipe = pool.get(subject.name());
            if (pipe != null) {
                registrar.register(pipe);
            }
        }
        // If both are null (shouldn't happen due to constructor checks), do nothing
    }
}
