package io.fullerstack.substrates.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * LinkedBlockingQueue-based implementation of internal Queue for backpressure management.
 *
 * <p>Processes runnables asynchronously using a virtual thread with blocking FIFO semantics.
 * Runnables are executed in strict FIFO order using {@link java.util.concurrent.LinkedBlockingQueue}.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Asynchronous execution via virtual thread</li>
 *   <li>Blocking FIFO ordering guarantee</li>
 *   <li>Unbounded capacity (linked-list backed)</li>
 *   <li>await() blocks until queue is empty</li>
 *   <li>Graceful error handling - continues processing after errors</li>
 *   <li>Thread-safe concurrent post() operations</li>
 * </ul>
 *
 * <p><b>Alternative implementations:</b> Other Queue strategies could include:
 * <ul>
 *   <li>PriorityBlockingQueueImpl - Priority-based weighted script execution</li>
 *   <li>ArrayBlockingQueueImpl - Bounded queue with backpressure feedback</li>
 *   <li>BatchingQueueImpl - Batching queue for throughput optimization</li>
 * </ul>
 *
 * <p>Reference: https://humainary.io/blog/observability-x-circuits/
 *
 * @see Queue
 * @see java.util.concurrent.LinkedBlockingQueue
 */
public class LinkedBlockingQueueImpl implements Queue {
    private final BlockingQueue<Runnable> runnables = new LinkedBlockingQueue<>();
    private final Thread processor;
    private volatile boolean running = true;
    private volatile boolean executing = false;

    /**
     * Creates a LinkedBlockingQueue-based queue and starts background processing.
     */
    public LinkedBlockingQueueImpl() {
        this.processor = Thread.startVirtualThread(this::processQueue);
    }

    @Override
    public void await() {
        // Block until queue is empty and nothing is currently executing
        while (running && (executing || !runnables.isEmpty())) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Queue await interrupted", e);
            }
        }
    }

    @Override
    public void post(Runnable runnable) {
        if (runnable != null && running) {
            runnables.offer(runnable);  // Add to queue (FIFO)
        }
    }

    @Override
    public void close() {
        running = false;
        processor.interrupt();
        try {
            processor.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Background processor that executes runnables from the queue.
     */
    private void processQueue() {
        while (running && !Thread.interrupted()) {
            try {
                Runnable runnable = runnables.take();  // Blocking take (FIFO)
                executing = true;
                try {
                    runnable.run();
                } finally {
                    executing = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log error but continue processing
                System.err.println("Error executing runnable: " + e.getMessage());
                executing = false;
            }
        }
    }

    /**
     * Checks if the queue is empty.
     * Useful for testing.
     */
    public boolean isEmpty() {
        return runnables.isEmpty();
    }
}
