package io.fullerstack.substrates.valve;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Valve - Controls the flow of tasks through a virtual thread processor.
 *
 *
 * <p><b>Design Pattern:</b>
 * <ul>
 *   <li>Valve = BlockingQueue + Virtual Thread processor</li>
 *   <li>Emissions → Tasks (via submit)</li>
 *   <li>Virtual thread parks when queue is empty (BlockingQueue.take())</li>
 *   <li>Virtual thread unparks and executes when task arrives</li>
 *   <li>Continuous loop processes tasks serially (FIFO order)</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * Valve valve = new Valve("circuit-main");
 * valve.submit(() -> System.out.println("Task 1"));
 * valve.submit(() -> System.out.println("Task 2"));
 * valve.close(); // Shutdown when done
 * </pre>
 *
 * <p>This abstraction makes William's "valve" pattern explicit in the codebase,
 * aligning terminology with the reference implementation.
 */
public class Valve implements AutoCloseable {

    private final String name;
    private final BlockingQueue<Runnable> queue;
    private final Thread processor;
    private volatile boolean running = true;
    private volatile boolean executing = false;

    // Synchronization for event-driven await() (eliminates polling)
    private final Object idleLock = new Object();

    /**
     * Creates a new Valve with a virtual thread processor.
     *
     * @param name descriptive name for the valve (used in thread naming)
     */
    public Valve(String name) {
        this.name = name;
        this.queue = new LinkedBlockingQueue<>();
        this.processor = Thread.startVirtualThread(this::processQueue);
    }

    /**
     * Submits a task to the valve for execution.
     * The task will be executed serially in FIFO order.
     *
     * @param task the task to execute
     * @return true if task was accepted, false if valve is closed
     */
    public boolean submit(Runnable task) {
        if (task != null && running) {
            return queue.offer(task);
        }
        return false;
    }

    /**
     * Blocks until all queued tasks are executed and the valve is idle.
     * Cannot be called from the valve's own thread.
     *
     * <p><b>Event-Driven Design:</b>
     * Uses wait/notify mechanism instead of polling. The valve processor notifies
     * waiting threads immediately when it becomes idle, eliminating polling overhead
     * and providing zero-latency wake-up.
     *
     * @param contextName name of the calling context (e.g., "Circuit", "Valve") for error messages (will be lowercased for possessive)
     * @throws IllegalStateException if called from valve's thread
     */
    public void await(String contextName) {
        // Cannot be called from valve's own thread
        if (Thread.currentThread() == processor) {
            throw new IllegalStateException(
                "Cannot call " + contextName + "::await from within a " + contextName.toLowerCase() + "'s thread"
            );
        }

        // Event-driven wait - no polling!
        synchronized (idleLock) {
            while (running && (executing || !queue.isEmpty())) {
                try {
                    idleLock.wait();  // Block until notified by processor
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(contextName + " await interrupted", e);
                }
            }
        }
    }

    /**
     * Checks if the valve is currently idle (no tasks executing or queued).
     *
     * @return true if idle, false if tasks are pending or executing
     */
    public boolean isIdle() {
        return !executing && queue.isEmpty();
    }

    /**
     * Background processor that executes tasks from the queue serially.
     * Runs in a virtual thread (parks when queue is empty, unparks when task arrives).
     *
     * <p>Notifies waiting threads when the valve becomes idle (queue empty and no task executing).
     */
    private void processQueue() {
        while (running && !Thread.interrupted()) {
            try {
                Runnable task = queue.take();  // PARK when empty
                executing = true;
                try {
                    task.run();  // UNPARK and execute
                } finally {
                    executing = false;

                    // Notify awaiting threads if valve is now idle
                    if (queue.isEmpty()) {
                        synchronized (idleLock) {
                            idleLock.notifyAll();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log error but continue processing
                System.err.println("Error executing task in valve '" + name + "': " + e.getMessage());
                executing = false;

                // Still notify on error in case queue is empty
                if (queue.isEmpty()) {
                    synchronized (idleLock) {
                        idleLock.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * Closes the valve and stops the processor thread.
     * Waits up to 1 second for graceful shutdown.
     * Notifies any threads waiting in await().
     */
    @Override
    public void close() {
        if (running) {
            running = false;
            processor.interrupt();

            // Notify any threads waiting in await()
            synchronized (idleLock) {
                idleLock.notifyAll();
            }

            try {
                processor.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns the valve name.
     *
     * @return the name provided at construction
     */
    public String getName() {
        return name;
    }
}
