package io.fullerstack.substrates.queue;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueImplTest {
    private QueueImpl queue;

    /**
     * Helper to create a Script from a Runnable.
     */
    private Script script(Runnable runnable) {
        return current -> runnable.run();
    }

    @AfterEach
    void cleanup() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void shouldExecuteScriptsInFifoOrder() throws Exception {
        queue = new QueueImpl();
        CopyOnWriteArrayList<Integer> executionOrder = new CopyOnWriteArrayList<>();

        queue.post(script(() -> executionOrder.add(1)));
        queue.post(script(() -> executionOrder.add(2)));
        queue.post(script(() -> executionOrder.add(3)));

        queue.await();

        assertThat(executionOrder).containsExactly(1, 2, 3);
    }

    @Test
    void shouldBlockUntilQueueEmpty() throws Exception {
        queue = new QueueImpl();
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch scriptStarted = new CountDownLatch(1);

        queue.post(script(() -> {
            scriptStarted.countDown();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            counter.incrementAndGet();
        }));

        // Wait for script to start
        assertThat(scriptStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // await() should block until script completes
        queue.await();

        assertThat(counter.get()).isEqualTo(1);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void shouldHandleNullScript() {
        queue = new QueueImpl();

        queue.post(null); // Should not throw

        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void shouldHandleNamedScript() throws Exception {
        queue = new QueueImpl();
        AtomicInteger counter = new AtomicInteger(0);

        queue.post(NameImpl.of("test-script"), script(() -> counter.incrementAndGet()));

        queue.await();

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void shouldContinueProcessingAfterScriptError() throws Exception {
        queue = new QueueImpl();
        CopyOnWriteArrayList<Integer> executed = new CopyOnWriteArrayList<>();

        queue.post(script(() -> executed.add(1)));
        queue.post(script(() -> {
            executed.add(2);
            throw new RuntimeException("Script error");
        }));
        queue.post(script(() -> executed.add(3)));

        queue.await();

        assertThat(executed).containsExactly(1, 2, 3);
    }

    @Test
    void shouldHandleConcurrentPosts() throws Exception {
        queue = new QueueImpl();
        int threadCount = 10;
        int postsPerThread = 100;
        AtomicInteger totalExecuted = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Multiple threads posting concurrently
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.startVirtualThread(() -> {
                for (int j = 0; j < postsPerThread; j++) {
                    queue.post(script(() -> totalExecuted.incrementAndGet()));
                }
                latch.countDown();
            });
        }

        // Wait for all posts to complete
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Wait for all scripts to execute
        queue.await();

        assertThat(totalExecuted.get()).isEqualTo(threadCount * postsPerThread);
    }

    @Test
    void shouldHandleInterruptDuringAwait() throws Exception {
        queue = new QueueImpl();

        // Post a long-running script
        queue.post(script(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        Thread awaitThread = Thread.startVirtualThread(() -> {
            try {
                queue.await();
            } catch (RuntimeException e) {
                // Expected
            }
        });

        // Give it time to start waiting
        Thread.sleep(100);

        // Interrupt the waiting thread
        awaitThread.interrupt();
        awaitThread.join(1000);

        assertThat(awaitThread.isAlive()).isFalse();
    }

    @Test
    void shouldExecuteMultipleScriptsSequentially() throws Exception {
        queue = new QueueImpl();
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        queue.post(script(() -> {
            events.add("start-1");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            events.add("end-1");
        }));

        queue.post(script(() -> {
            events.add("start-2");
            events.add("end-2");
        }));

        queue.await();

        assertThat(events).containsExactly("start-1", "end-1", "start-2", "end-2");
    }

    @Test
    void shouldNotAcceptPostsAfterShutdown() throws Exception {
        queue = new QueueImpl();
        AtomicInteger counter = new AtomicInteger(0);

        queue.post(script(() -> counter.incrementAndGet()));
        queue.await();

        assertThat(counter.get()).isEqualTo(1);

        queue.shutdown();

        // Posts after shutdown should be ignored
        queue.post(script(() -> counter.incrementAndGet()));

        Thread.sleep(100);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void shouldHandleEmptyQueueAwait() {
        queue = new QueueImpl();

        // await() on empty queue should return immediately
        queue.await();

        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void shouldHandleRapidPostAndAwaitCycles() throws Exception {
        queue = new QueueImpl();

        for (int i = 0; i < 10; i++) {
            AtomicInteger counter = new AtomicInteger(0);

            queue.post(script(() -> counter.incrementAndGet()));
            queue.await();

            assertThat(counter.get()).isEqualTo(1);
        }
    }
}
