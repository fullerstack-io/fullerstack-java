package io.fullerstack.substrates.integration;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.circuit.CircuitImpl;
import io.fullerstack.substrates.util.NameImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for CircuitImpl demonstrating all components working together.
 *
 * <p>Tests the full circuit workflow:
 * <ul>
 *   <li>Circuit creation and lifecycle</li>
 *   <li>Queue script execution</li>
 *   <li>Clock periodic emissions</li>
 *   <li>Source event broadcasting</li>
 *   <li>Component integration</li>
 * </ul>
 */
class CircuitIntegrationTest {
    private Circuit circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    @Test
    void shouldCreateCircuitViaCortex() {
        Cortex cortex = new CortexRuntime();

        circuit = cortex.circuit(cortex.name("integration-test"));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Subject.Type.CIRCUIT);
    }

    @Test
    void shouldExecuteScriptsViaQueue() throws Exception {
        circuit = new CircuitImpl(NameImpl.of("test"));
        Queue queue = circuit.queue();

        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        // Post 3 scripts
        queue.post(current -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        queue.post(current -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        queue.post(current -> {
            counter.incrementAndGet();
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void shouldEmitPeriodicEventsViaClock() throws Exception {
        circuit = new CircuitImpl(NameImpl.of("test"));
        Clock clock = circuit.clock(NameImpl.of("timer"));

        AtomicInteger tickCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        Subscription subscription = clock.consume(
            NameImpl.of("ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                tickCount.incrementAndGet();
                latch.countDown();
            }
        );

        assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();
        subscription.close();

        assertThat(tickCount.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldBroadcastStateEventsViaSource() {
        circuit = new CircuitImpl(NameImpl.of("test"));
        Source<State> source = circuit.source();

        AtomicInteger emissionCount = new AtomicInteger(0);

        source.subscribe(new Subscriber<State>() {
            @Override
            public Subject subject() {
                return new io.fullerstack.substrates.subject.SubjectImpl(
                    io.fullerstack.substrates.id.IdImpl.generate(),
                    NameImpl.of("subscriber"),
                    io.fullerstack.substrates.state.StateImpl.empty(),
                    Subject.Type.SUBSCRIBER
                );
            }

            @Override
            public void accept(Subject subject, Registrar<State> registrar) {
                registrar.register(state -> emissionCount.incrementAndGet());
            }
        });

        // Emit state via cast to SourceImpl
        io.fullerstack.substrates.source.SourceImpl<State> sourceImpl =
            (io.fullerstack.substrates.source.SourceImpl<State>) source;
        sourceImpl.emit(io.fullerstack.substrates.state.StateImpl.of(NameImpl.of("event"), 1));

        assertThat(emissionCount.get()).isEqualTo(1);
    }

    @Test
    void shouldIntegrateQueueAndClock() throws Exception {
        circuit = new CircuitImpl(NameImpl.of("integration"));
        Queue queue = circuit.queue();
        Clock clock = circuit.clock();

        AtomicInteger queueExecutions = new AtomicInteger(0);
        AtomicInteger clockTicks = new AtomicInteger(0);
        CountDownLatch queueLatch = new CountDownLatch(2);
        CountDownLatch clockLatch = new CountDownLatch(2);

        // Post scripts to queue
        queue.post(current -> {
            queueExecutions.incrementAndGet();
            queueLatch.countDown();
        });
        queue.post(current -> {
            queueExecutions.incrementAndGet();
            queueLatch.countDown();
        });

        // Start clock
        Subscription subscription = clock.consume(
            NameImpl.of("ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                clockTicks.incrementAndGet();
                clockLatch.countDown();
            }
        );

        assertThat(queueLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(clockLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        subscription.close();

        assertThat(queueExecutions.get()).isEqualTo(2);
        assertThat(clockTicks.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldSupportMultipleClocksWithDifferentCycles() throws Exception {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock fastClock = circuit.clock(NameImpl.of("fast"));
        Clock slowClock = circuit.clock(NameImpl.of("slow"));

        AtomicInteger fastTicks = new AtomicInteger(0);
        AtomicInteger slowTicks = new AtomicInteger(0);
        CountDownLatch fastLatch = new CountDownLatch(5);
        CountDownLatch slowLatch = new CountDownLatch(2);

        Subscription fastSub = fastClock.consume(
            NameImpl.of("fast-ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                fastTicks.incrementAndGet();
                fastLatch.countDown();
            }
        );

        Subscription slowSub = slowClock.consume(
            NameImpl.of("slow-ticker"),
            Clock.Cycle.SECOND,
            instant -> {
                slowTicks.incrementAndGet();
                slowLatch.countDown();
            }
        );

        assertThat(fastLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(slowLatch.await(3, TimeUnit.SECONDS)).isTrue();

        fastSub.close();
        slowSub.close();

        assertThat(fastTicks.get()).isGreaterThanOrEqualTo(5);
        assertThat(slowTicks.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldCleanupAllResourcesOnClose() throws Exception {
        circuit = new CircuitImpl(NameImpl.of("test"));

        // Create all components
        Queue queue = circuit.queue();
        Clock clock1 = circuit.clock(NameImpl.of("clock1"));
        Clock clock2 = circuit.clock(NameImpl.of("clock2"));
        Source<State> source = circuit.source();

        AtomicInteger clockTicks = new AtomicInteger(0);
        clock1.consume(
            NameImpl.of("ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> clockTicks.incrementAndGet()
        );

        Thread.sleep(20);
        int ticksBeforeClose = clockTicks.get();

        // Close circuit
        circuit.close();

        // Verify clocks stopped
        Thread.sleep(20);
        int ticksAfterClose = clockTicks.get();
        assertThat(ticksAfterClose).isLessThanOrEqualTo(ticksBeforeClose + 2);
    }

    @Test
    void shouldProvideAccessToAllComponents() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit.source()).isNotNull();
        assertThat((Object) circuit.queue()).isNotNull();
        assertThat((Object) circuit.clock()).isNotNull();
        assertThat((Object) circuit.clock(NameImpl.of("custom"))).isNotNull();
    }

    @Test
    void shouldSupportTapPatternForFunctionalChaining() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        AtomicInteger tapCount = new AtomicInteger(0);

        Circuit result = circuit
            .tap(c -> tapCount.incrementAndGet())
            .tap(c -> tapCount.incrementAndGet())
            .tap(c -> tapCount.incrementAndGet());

        assertThat((Object) result).isSameAs(circuit);
        assertThat(tapCount.get()).isEqualTo(3);
    }
}
