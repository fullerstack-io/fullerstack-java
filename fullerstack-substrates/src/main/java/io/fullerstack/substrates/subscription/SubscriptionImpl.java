package io.fullerstack.substrates.subscription;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.name.NameNode;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;

/**
 * Implementation of Substrates.Subscription for managing subscriber lifecycle.
 *
 * <p>Subscription is returned from Source.subscribe() and allows unsubscribing
 * by calling close(). Each subscription has a unique ID and subject.
 *
 * @see Subscription
 * @see Source
 * @see Subscriber
 */
public class SubscriptionImpl implements Subscription {

    private final Id subscriptionId;
    private final Subject<Subscription> subscriptionSubject;
    private final Runnable onClose;
    private volatile boolean closed = false;

    /**
     * Creates a Subscription with the given close handler.
     *
     * @param onClose runnable to execute when close() is called
     * @throws NullPointerException if onClose is null
     */
    public SubscriptionImpl(Runnable onClose) {
        this.onClose = Objects.requireNonNull(onClose, "onClose cannot be null");
        this.subscriptionId = IdImpl.generate();
        this.subscriptionSubject = new SubjectImpl<>(
            subscriptionId,
            NameNode.of("subscription").name(subscriptionId.toString()),
            StateImpl.empty(),
            Subscription.class
        );
    }

    @Override
    public Subject subject() {
        return subscriptionSubject;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            onClose.run();
        }
    }
}
