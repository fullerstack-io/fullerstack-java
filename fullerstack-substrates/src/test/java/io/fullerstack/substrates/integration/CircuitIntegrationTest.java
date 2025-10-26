package io.fullerstack.substrates.integration;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.capture.SubjectCapture;
import io.fullerstack.substrates.circuit.SequentialCircuit;
import io.fullerstack.substrates.name.HierarchicalName;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SequentialCircuit demonstrating all components working together.
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
        Cortex cortex = CortexRuntime.cortex();

        circuit = cortex.circuit(cortex.name("integration-test"));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Circuit.class);
    }

    @Test
    void shouldProcessEmissionsViaCircuitQueue() throws Exception {
        // M15+ API: Queue is internal, access via Pipe emissions
        Cortex cortex = CortexRuntime.cortex();
        circuit = cortex.circuit(cortex.name("test"));

        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        // Create a conduit to emit through
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("test-conduit"),
            Composer.pipe()
        );

        // Subscribe to receive emissions
        conduit.subscribe(cortex.subscriber(
            cortex.name("subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                counter.incrementAndGet();
                latch.countDown();
            })
        ));

        // Emit 3 values (posts to circuit's internal queue)
        Pipe<Integer> pipe = conduit.get(cortex.name("channel"));
        pipe.emit(1);
        pipe.emit(2);
        pipe.emit(3);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void shouldEmitPeriodicEventsViaClock() throws Exception {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));
        Clock clock = circuit.clock(HierarchicalName.of("timer"));

        AtomicInteger tickCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        Subscription subscription = clock.consume(
            HierarchicalName.of("ticker"),
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

    // NOTE: This test was commented out because it tests internal implementation details
    // (SourceImpl.emissionHandler()) that aren't exposed in the public Circuit API.
    // The Circuit no longer exposes its internal Source for direct manipulation.
    /*
    @Test
    void shouldBroadcastStateEventsViaSource() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));
        Source<State> source = circuit.source();  // Get the Circuit's Source

        AtomicInteger emissionCount = new AtomicInteger(0);

        source.subscribe(new Subscriber<State>() {
            @Override
            @SuppressWarnings("unchecked")
            public Subject<Subscriber<State>> subject() {
                return new io.fullerstack.substrates.subject.HierarchicalSubject<>(
                    io.fullerstack.substrates.id.UuidIdentifier.generate(),
                    HierarchicalName.of("subscriber"),
                    io.fullerstack.substrates.state.LinkedState.empty(),
                    (Class<Subscriber<State>>) (Class<?>) Subscriber.class
                );
            }

            @Override
            public void accept(Subject<Channel<State>> subject, Registrar<State> registrar) {
                registrar.register(state -> emissionCount.incrementAndGet());
            }
        });

        // Simulate Conduit behavior of invoking subscribers
        io.fullerstack.substrates.source.SourceImpl<State> sourceImpl =
            (io.fullerstack.substrates.source.SourceImpl<State>) source;
        @SuppressWarnings("unchecked")
        Subject<Channel<State>> testChannel = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            HierarchicalName.of("test-channel"),
            LinkedState.empty(),
            (Class<Channel<State>>) (Class<?>) Channel.class
        );
        State emission = LinkedState.of(HierarchicalName.of("event"), 1);

        // Use SourceImpl's emission handler (like inlet Pipes do)
        Capture<State, Channel<State>> capture = new SubjectCapture<>(testChannel, emission);
        sourceImpl.emissionHandler().accept(capture);

        assertThat(emissionCount.get()).isEqualTo(1);
    }
    */

    @Test
    void shouldIntegratePipeEmissionsAndClock() throws Exception {
        // M15+ API: Test Pipe emissions + Clock working together
        Cortex cortex = CortexRuntime.cortex();
        circuit = cortex.circuit(cortex.name("integration"));

        AtomicInteger pipeEmissions = new AtomicInteger(0);
        AtomicInteger clockTicks = new AtomicInteger(0);
        CountDownLatch pipeLatch = new CountDownLatch(2);
        CountDownLatch clockLatch = new CountDownLatch(2);

        // Create conduit for pipe emissions
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test-conduit"),
            Composer.pipe()
        );

        conduit.subscribe(cortex.subscriber(
            cortex.name("subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                pipeEmissions.incrementAndGet();
                pipeLatch.countDown();
            })
        ));

        // Emit values through pipe
        Pipe<String> pipe = conduit.get(cortex.name("channel"));
        pipe.emit("emission-1");
        pipe.emit("emission-2");

        // Start clock
        Clock clock = circuit.clock();
        Subscription subscription = clock.consume(
            HierarchicalName.of("ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                clockTicks.incrementAndGet();
                clockLatch.countDown();
            }
        );

        assertThat(pipeLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(clockLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        subscription.close();

        assertThat(pipeEmissions.get()).isEqualTo(2);
        assertThat(clockTicks.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldSupportMultipleClocksWithDifferentCycles() throws Exception {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock fastClock = circuit.clock(HierarchicalName.of("fast"));
        Clock slowClock = circuit.clock(HierarchicalName.of("slow"));

        AtomicInteger fastTicks = new AtomicInteger(0);
        AtomicInteger slowTicks = new AtomicInteger(0);
        CountDownLatch fastLatch = new CountDownLatch(5);
        CountDownLatch slowLatch = new CountDownLatch(2);

        Subscription fastSub = fastClock.consume(
            HierarchicalName.of("fast-ticker"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                fastTicks.incrementAndGet();
                fastLatch.countDown();
            }
        );

        Subscription slowSub = slowClock.consume(
            HierarchicalName.of("slow-ticker"),
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
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Create all components (M15+: no queue() method)
        Clock clock1 = circuit.clock(HierarchicalName.of("clock1"));
        Clock clock2 = circuit.clock(HierarchicalName.of("clock2"));
        Source<State> source = circuit;  // Circuit implements Source

        AtomicInteger clockTicks = new AtomicInteger(0);
        clock1.consume(
            HierarchicalName.of("ticker"),
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
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // M15+ API: queue() is internal, not exposed publicly
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.clock()).isNotNull();
        assertThat((Object) circuit.clock(HierarchicalName.of("custom"))).isNotNull();
    }

    @Test
    void shouldSupportTapPatternForFunctionalChaining() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        AtomicInteger tapCount = new AtomicInteger(0);

        Circuit result = circuit
            .tap(c -> tapCount.incrementAndGet())
            .tap(c -> tapCount.incrementAndGet())
            .tap(c -> tapCount.incrementAndGet());

        assertThat((Object) result).isSameAs(circuit);
        assertThat(tapCount.get()).isEqualTo(3);
    }
}
