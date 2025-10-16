package io.fullerstack.substrates.queue;

import io.humainary.substrates.api.Substrates.Queue;

/**
 * Factory interface for creating {@link Queue} implementations.
 *
 * <p>This abstraction allows different Queue strategies to be plugged in:
 * <ul>
 *   <li>{@link BlockingQueueImpl} - Unbounded FIFO blocking queue (default)</li>
 *   <li>Future: Priority queue for weighted script execution</li>
 *   <li>Future: Bounded queue with backpressure feedback</li>
 *   <li>Future: Batching queue for throughput optimization</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * QueueFactory factory = BlockingQueueFactory.getInstance();
 * Queue queue = factory.create();
 * </pre>
 *
 * <p><b>Dependency Injection:</b>
 * QueueFactory is injected into {@link io.fullerstack.substrates.circuit.CircuitImpl}
 * via constructor, allowing different Queue strategies per Circuit.
 *
 * @see Queue
 * @see BlockingQueueImpl
 */
public interface QueueFactory {

    /**
     * Creates a new Queue instance.
     *
     * @return a new Queue implementation
     */
    Queue create();
}
