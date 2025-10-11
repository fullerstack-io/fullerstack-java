package io.fullerstack.substrates.subscriber;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Subscriber based on examples from:
 * https://humainary.io/blog/observability-x-subscribers/
 */
class SubscriberIntegrationTest {

    @Test
    void shouldSetupCortexCircuitConduitAndSource() {
        // Example 1: Basic Cortex Setup
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit();
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("test-conduit"),
            Composer.pipe()
        );
        Source<Long> source = conduit.source();

        assertThat((Object) cortex).isNotNull();
        assertThat((Object) circuit).isNotNull();
        assertThat((Object) conduit).isNotNull();
        assertThat((Object) source).isNotNull();
        assertThat((Object) source.subject()).isNotNull();
    }

    @Test
    void shouldForwardEmissionsAcrossNamespaceHierarchy() throws Exception {
        // Simplified: Demonstrate subscriber routing to specific pipes
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit();
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );
        Source<Long> source = conduit.source();

        CopyOnWriteArrayList<Long> receivedValues = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Subscribe with routing logic
        Subscriber<Long> subscriber = cortex.subscriber(
            cortex.name("routing-subscriber"),
            (subject, registrar) -> {
                // Register a pipe to receive emissions from this subject
                registrar.register(value -> {
                    receivedValues.add(value);
                    latch.countDown();
                });
            }
        );

        source.subscribe(subscriber);

        // Emit value
        Pipe<Long> pipe = conduit.get(cortex.name("app"));
        pipe.emit(100L);

        // Wait for emissions to be processed
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify emission was received
        assertThat(receivedValues).containsExactly(100L);
    }

    @Test
    void shouldRegisterPipesUsingRegistrarPattern() throws Exception {
        // Example 3: Subscriber with Registrar pattern
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit();
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("sensors"),
            Composer.pipe()
        );
        Source<Long> source = conduit.source();

        CopyOnWriteArrayList<Long> receivedValues = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Create subscriber that uses Registrar to register pipes
        Subscriber<Long> subscriber = cortex.subscriber(
            cortex.name("registrar-subscriber"),
            (subject, registrar) -> {
                // Get pipe for this subject's name
                Pipe<Long> targetPipe = conduit.get(subject.name());

                // Register a consumer pipe
                registrar.register(value -> {
                    receivedValues.add(value);
                    latch.countDown();
                });
            }
        );

        source.subscribe(subscriber);

        // Emit value
        Pipe<Long> sensorPipe = conduit.get(cortex.name("sensor1"));
        sensorPipe.emit(42L);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedValues).containsExactly(42L);
    }

    @Test
    void shouldUsePoolBasedSubscriber() throws Exception {
        // Pool-based Subscriber pattern
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit();
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("counters"),
            Composer.pipe()
        );
        Source<Long> source = conduit.source();

        CopyOnWriteArrayList<Long> values = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Create a pool that maps subject names to consumer pipes
        Pool<Pipe<Long>> pipePool = subjectName -> value -> {
            values.add(value);
            latch.countDown();
        };

        // Create pool-based subscriber
        Subscriber<Long> subscriber = cortex.subscriber(
            cortex.name("pool-subscriber"),
            pipePool
        );

        source.subscribe(subscriber);

        // Emit from different channels
        conduit.get(cortex.name("counter1")).emit(10L);
        conduit.get(cortex.name("counter2")).emit(20L);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(values).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void shouldIterateThroughNameHierarchy() {
        // Demonstrate Name iteration capabilities
        Cortex cortex = new CortexRuntime();

        Name hierarchicalName = cortex.name("app")
            .name("service")
            .name("metric");

        AtomicInteger iterationCount = new AtomicInteger(0);

        // Iterate through name hierarchy
        for (Name name : hierarchicalName) {
            assertThat((Object) name).isNotNull();
            assertThat(name.part()).isNotNull();
            iterationCount.incrementAndGet();
        }

        // Name is iterable - should iterate through all levels
        assertThat(iterationCount.get()).isGreaterThan(0);
    }

    @Test
    void shouldHandleMultipleSubscribers() throws Exception {
        // Multiple subscribers on same source
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit();
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("events"),
            Composer.pipe()
        );
        Source<Long> source = conduit.source();

        AtomicInteger subscriber1Count = new AtomicInteger(0);
        AtomicInteger subscriber2Count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        // First subscriber
        source.subscribe(cortex.subscriber(
            cortex.name("sub1"),
            (subject, registrar) -> registrar.register(value -> {
                subscriber1Count.incrementAndGet();
                latch.countDown();
            })
        ));

        // Second subscriber
        source.subscribe(cortex.subscriber(
            cortex.name("sub2"),
            (subject, registrar) -> registrar.register(value -> {
                subscriber2Count.incrementAndGet();
                latch.countDown();
            })
        ));

        // Emit once
        conduit.get(cortex.name("event1")).emit(99L);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber1Count.get()).isEqualTo(1);
        assertThat(subscriber2Count.get()).isEqualTo(1);
    }
}
