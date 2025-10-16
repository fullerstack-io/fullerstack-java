package io.fullerstack.substrates.queue;

import io.humainary.substrates.api.Substrates.Queue;

/**
 * Factory for creating {@link LinkedBlockingQueueImpl} instances.
 *
 * <p>This factory creates {@link LinkedBlockingQueueImpl} instances which use
 * unbounded {@link java.util.concurrent.LinkedBlockingQueue} with FIFO semantics.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Unbounded blocking queue - no capacity limits (linked-list backed)</li>
 *   <li>FIFO ordering guarantee</li>
 *   <li>Virtual thread for async processing</li>
 *   <li>Graceful error handling</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>General-purpose async script execution</li>
 *   <li>Unpredictable workload patterns</li>
 *   <li>When memory is not constrained</li>
 *   <li>When throughput is more important than bounded memory</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * QueueFactory factory = LinkedBlockingQueueFactory.getInstance();
 * Queue queue = factory.create();
 * </pre>
 *
 * @see LinkedBlockingQueueImpl
 * @see QueueFactory
 */
public final class LinkedBlockingQueueFactory implements QueueFactory {

    private static final LinkedBlockingQueueFactory INSTANCE = new LinkedBlockingQueueFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private LinkedBlockingQueueFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the LinkedBlockingQueueFactory singleton
     */
    public static LinkedBlockingQueueFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Queue create() {
        return new LinkedBlockingQueueImpl();
    }

    @Override
    public String toString() {
        return "LinkedBlockingQueueFactory{}";
    }
}
