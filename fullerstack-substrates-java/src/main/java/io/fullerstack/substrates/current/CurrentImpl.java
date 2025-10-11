package io.fullerstack.substrates.current;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;

/**
 * Implementation of Substrates.Current for posting runnables to a circuit's queue.
 *
 * <p>Current provides Scripts with access to the circuit's work queue for asynchronous execution.
 * It wraps a Queue and converts Runnables into Scripts.
 *
 * @see Current
 * @see Queue
 * @see Script
 */
public class CurrentImpl implements Current {

    private final Queue queue;
    private final Subject currentSubject;

    /**
     * Creates a Current that wraps the given Queue.
     *
     * @param queue the circuit's queue
     * @param subject the subject for this Current
     * @throws NullPointerException if queue or subject is null
     */
    public CurrentImpl(Queue queue, Subject subject) {
        this.queue = Objects.requireNonNull(queue, "Queue cannot be null");
        this.currentSubject = Objects.requireNonNull(subject, "Subject cannot be null");
    }

    @Override
    public Subject subject() {
        return currentSubject;
    }

    @Override
    public void post(Runnable runnable) {
        Objects.requireNonNull(runnable, "Runnable cannot be null");

        // Convert Runnable to Script and post to queue
        queue.post(current -> runnable.run());
    }
}
