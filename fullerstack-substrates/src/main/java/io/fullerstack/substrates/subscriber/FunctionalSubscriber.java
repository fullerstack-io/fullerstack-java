package io.fullerstack.substrates.subscriber;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Implementation of Substrates.Subscriber interface using the Strategy Pattern.
 *
 * <p>Subscriber connects one or more Pipes with emitting Subjects within a Source.
 * This implementation supports two patterns via {@link SubscriberStrategy}:
 * <ul>
 *   <li><b>Function-based:</b> Uses a BiConsumer to handle (Subject, Registrar) callbacks</li>
 *   <li><b>Pool-based:</b> Uses a Pool to retrieve Pipes for specific Subjects</li>
 * </ul>
 *
 * <p><b>Function-Based Pattern:</b>
 * <pre>
 * Subscriber&lt;String&gt; subscriber = new FunctionalSubscriber&lt;&gt;(
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
 * Subscriber&lt;String&gt; subscriber = new FunctionalSubscriber&lt;&gt;(name, pipePool);
 * </pre>
 *
 * <p>When a Subject emits, the Source calls accept(subject, registrar), and the
 * Subscriber delegates to its strategy to register the appropriate Pipes.
 *
 * @param <E> the emission type
 * @see Subscriber
 * @see Registrar
 * @see SubscriberStrategy
 * @see FunctionStrategy
 * @see PoolStrategy
 */
public class FunctionalSubscriber<E> implements Subscriber<E> {

    private final Subject<Subscriber<E>> subscriberSubject;
    private final SubscriberStrategy<E> strategy;

    /**
     * Creates a function-based Subscriber.
     *
     * @param name the name to be used by the subject assigned to the subscriber
     * @param handler the callback function that receives (Subject, Registrar)
     * @throws NullPointerException if name or handler is null
     */
    public FunctionalSubscriber(Name name, BiConsumer<Subject<Channel<E>>, Registrar<E>> handler) {
        Objects.requireNonNull(name, "Subscriber name cannot be null");
        this.subscriberSubject = createSubject(name);
        this.strategy = new FunctionStrategy<>(handler);
    }

    /**
     * Creates a pool-based Subscriber.
     *
     * <p>When a Subject emits, this Subscriber retrieves a Pipe from the pool
     * using the Subject's name and registers it to receive the emission.
     *
     * @param name the name to be used by the subject assigned to the subscriber
     * @param pool the pool of Pipes keyed by Subject name
     * @throws NullPointerException if name or pool is null
     */
    public FunctionalSubscriber(Name name, Pool<? extends Pipe<E>> pool) {
        Objects.requireNonNull(name, "Subscriber name cannot be null");
        this.subscriberSubject = createSubject(name);
        this.strategy = new PoolStrategy<>(pool);
    }

    @SuppressWarnings("unchecked")
    private Subject<Subscriber<E>> createSubject(Name name) {
        return new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            name,
            LinkedState.empty(),
            (Class<Subscriber<E>>) (Class<?>) Subscriber.class
        );
    }

    @Override
    public Subject subject() {
        return subscriberSubject;
    }

    @Override
    public void accept(Subject<Channel<E>> subject, Registrar<E> registrar) {
        strategy.apply(subject, registrar);
    }
}
