package io.fullerstack.substrates.integration;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.circuit.SingleThreadCircuit;
import io.fullerstack.substrates.name.HierarchicalName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests to verify that Channel.pipe() caches and returns the same Pipe instance.
 *
 * <p>These tests ensure that Segment state (emission counters, limit tracking,
 * reduce accumulators, diff last values) is shared across all emissions from
 * a Channel, preventing incorrect behavior where multiple Pipe instances would
 * have separate state.
 */
class PipeCachingTest {

    private SingleThreadCircuit circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    /**
     * Helper to create a simple subscriber that collects emissions.
     */
    @SuppressWarnings("unchecked")
    private <E> Subscriber<E> subscriber(Subject<Subscriber<E>> subject, List<E> collector, CountDownLatch latch) {
        return new Subscriber<E>() {
            @Override
            public void accept(Subject<Channel<E>> s, Registrar<E> registrar) {
                registrar.register(emission -> {
                    collector.add(emission);
                    latch.countDown();
                });
            }

            @Override
            public Subject<Subscriber<E>> subject() {
                return subject;
            }
        };
    }

    @Test
    void shouldReturnSamePipeInstanceOnMultipleCalls() {
        circuit = new SingleThreadCircuit(HierarchicalName.of("test-circuit"));

        // Create conduit with limit transformation
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            HierarchicalName.of("test-conduit"),
            Composer.pipe(path -> path.limit(3))
        );

        // Get the same channel twice - should be same instance (cached by Conduit)
        Pipe<Integer> pipe1 = conduit.get(HierarchicalName.of("channel-1"));
        Pipe<Integer> pipe2 = conduit.get(HierarchicalName.of("channel-1"));

        // Should return the SAME Pipe instance (cached by Conduit)
        assertThat(pipe1).isSameAs(pipe2);
    }

    @Test
    void shouldShareSegmentStateAcrossMultiplePipeCalls() throws InterruptedException {
        circuit = new SingleThreadCircuit(HierarchicalName.of("test-circuit"));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit with limit(3)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            HierarchicalName.of("test-conduit"),
            Composer.pipe(path -> path.limit(3))
        );

        @SuppressWarnings("unchecked")
        Subject<Subscriber<Integer>> subscriberSubject = (Subject<Subscriber<Integer>>) (Subject<?>) conduit.subject();
        conduit.subscribe(subscriber(subscriberSubject, received, latch));

        // Get pipe and verify it's the same instance on multiple calls
        Pipe<Integer> pipe1 = conduit.get(HierarchicalName.of("channel-1"));
        Pipe<Integer> pipe2 = conduit.get(HierarchicalName.of("channel-1"));

        assertThat(pipe1).isSameAs(pipe2);

        // Emit values - limit should be shared (only 3 emissions pass)
        pipe1.emit(1);  // Passes (count = 1)
        pipe2.emit(2);  // Passes (count = 2) - same Segment!
        pipe1.emit(3);  // Passes (count = 3)
        pipe2.emit(4);  // Blocked by limit
        pipe1.emit(5);  // Blocked by limit

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(1, 2, 3);
    }

    @Test
    void shouldShareReduceAccumulatorState() throws InterruptedException {
        circuit = new SingleThreadCircuit(HierarchicalName.of("test-circuit"));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        // Create conduit with reduce (accumulating sum)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            HierarchicalName.of("test-conduit"),
            Composer.pipe(path -> path.reduce(0, Integer::sum))
        );

        @SuppressWarnings("unchecked")
        Subject<Subscriber<Integer>> subscriberSubject = (Subject<Subscriber<Integer>>) (Subject<?>) conduit.subject();
        conduit.subscribe(subscriber(subscriberSubject, received, latch));

        // Get pipe twice - should be same instance
        Pipe<Integer> pipe1 = conduit.get(HierarchicalName.of("accumulator"));
        Pipe<Integer> pipe2 = conduit.get(HierarchicalName.of("accumulator"));

        assertThat(pipe1).isSameAs(pipe2);

        // Emit values alternating between pipe references
        // If Segment state is shared, accumulator should work correctly
        pipe1.emit(1);  // 0 + 1 = 1
        pipe2.emit(2);  // 1 + 2 = 3 (same accumulator!)
        pipe1.emit(3);  // 3 + 3 = 6
        pipe2.emit(4);  // 6 + 4 = 10

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(1, 3, 6, 10);
    }
}
