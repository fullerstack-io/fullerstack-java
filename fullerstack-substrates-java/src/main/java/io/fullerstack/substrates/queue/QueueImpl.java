package io.fullerstack.substrates.queue;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Implementation of Substrates.Queue for backpressure management.
 *
 * <p>Processes scripts asynchronously using a virtual thread. Scripts are
 * executed in FIFO order with proper error handling.
 *
 * <p>Features:
 * <ul>
 *   <li>Asynchronous script execution via virtual thread</li>
 *   <li>FIFO ordering guarantee</li>
 *   <li>await() blocks until queue is empty</li>
 *   <li>Graceful error handling - continues processing after script errors</li>
 *   <li>Thread-safe concurrent post() operations</li>
 * </ul>
 *
 * @see Queue
 */
public class QueueImpl implements Queue {
    private final BlockingQueue<Script> scripts = new LinkedBlockingQueue<>();
    private final Thread processor;
    private volatile boolean running = true;
    private volatile boolean executing = false;

    /**
     * Creates a queue and starts background processing.
     */
    public QueueImpl() {
        this.processor = Thread.startVirtualThread(this::processQueue);
    }

    @Override
    public void await() {
        // Block until queue is empty and no script is currently executing
        while (running && (executing || !scripts.isEmpty())) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Queue await interrupted", e);
            }
        }
    }

    @Override
    public void post(Script script) {
        if (script != null && running) {
            scripts.offer(script);
        }
    }

    @Override
    public void post(Name name, Script script) {
        // Named script execution - for now just delegate to post()
        // Could add name to MDC or logging context in future
        post(script);
    }

    /**
     * Background processor that executes scripts from the queue.
     */
    private void processQueue() {
        // Create a Current instance for script execution with stable identity
        Id currentId = IdImpl.generate();
        Current current = new Current() {
            @Override
            public Subject subject() {
                return new SubjectImpl(
                    currentId,
                    NameImpl.of("queue-current").name(currentId.toString()),
                    StateImpl.empty(),
                    Subject.Type.SCRIPT
                );
            }

            @Override
            public void post(Runnable runnable) {
                // Post runnable as a script
                QueueImpl.this.post(curr -> runnable.run());
            }
        };

        while (running && !Thread.interrupted()) {
            try {
                Script script = scripts.take();
                executing = true;
                try {
                    script.exec(current);
                } finally {
                    executing = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log error but continue processing
                // In production, would use proper logging
                System.err.println("Error executing script: " + e.getMessage());
                executing = false;
            }
        }
    }

    /**
     * Shuts down the queue processor.
     * This method is not part of the Queue interface but provided for cleanup.
     */
    public void shutdown() {
        running = false;
        processor.interrupt();
        try {
            processor.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checks if the queue is empty.
     * Useful for testing.
     */
    public boolean isEmpty() {
        return scripts.isEmpty();
    }
}
