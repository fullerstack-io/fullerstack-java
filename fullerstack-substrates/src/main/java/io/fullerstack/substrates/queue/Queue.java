package io.fullerstack.substrates.queue;

/**
 * Internal queue interface for Circuit execution model.
 *
 * <p>Note: This is an internal interface that replaced the Substrates.Queue interface
 * which was removed in M15+. The public API now exposes Circuit.await() directly.
 *
 * <p>Queue manages the backpressure and execution order of Scripts posted by Pipes.
 * All Conduits within a Circuit share a single Queue instance.
 *
 * @see <a href="https://humainary.io/blog/observability-x-circuits/">Observability X - Circuits</a>
 */
public interface Queue {

    /**
     * Blocks the calling thread until the queue has finished processing all scripts.
     *
     * <p>This method is called by Circuit.await() to wait for all pending scripts to complete.
     */
    void await();

    /**
     * Posts a script to the queue for execution.
     *
     * @param script the script to execute
     */
    void post(Runnable script);

    /**
     * Closes the queue and releases resources.
     */
    void close();
}
