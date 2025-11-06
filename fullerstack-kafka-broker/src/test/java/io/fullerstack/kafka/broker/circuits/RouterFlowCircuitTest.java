package io.fullerstack.kafka.broker.circuits;

import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Subscriber;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import io.humainary.substrates.ext.serventis.ext.Routers.Sign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for RouterFlowCircuit (RC6 Routers API Pattern).
 * <p>
 * Tests validate complete Router lifecycle and signal sequences:
 * <ul>
 *   <li>Circuit creation with Routers::composer</li>
 *   <li>Router signal emission (SEND, RECEIVE, DROP, etc.)</li>
 *   <li>Signal subscription and delivery</li>
 *   <li>Complete signal sequences (ISR, reassignment patterns)</li>
 * </ul>
 *
 * <b>RC6 Pattern</b>: Reference implementation for all Router-based monitoring.
 */
@DisplayName("RouterFlowCircuit (RC6 Routers API)")
class RouterFlowCircuitTest {

    private RouterFlowCircuit circuit;
    private List<RouterSignalEvent> capturedSignals;

    @BeforeEach
    void setUp() {
        circuit = new RouterFlowCircuit("test-router-flow");
        capturedSignals = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (circuit != null) {
            circuit.close();
        }
    }

    /**
     * Helper record to track router signal events.
     */
    private record RouterSignalEvent(String entityName, Sign sign) {}

    /**
     * Helper to create signal subscriber.
     */
    private void subscribeToSignals() {
        circuit.subscribe("test-subscriber", (subject, sign) -> {
            String entityName = subject.name().toString();
            capturedSignals.add(new RouterSignalEvent(entityName, sign));
        });
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create circuit with valid name")
        void testConstructor_ValidName() {
            assertThatCode(() -> new RouterFlowCircuit("valid-circuit"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject null circuit name")
        void testConstructor_NullName() {
            assertThatThrownBy(() -> new RouterFlowCircuit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("circuitName cannot be null");
        }
    }

    // ========================================================================
    // Router Creation Tests
    // ========================================================================

    @Nested
    @DisplayName("Router Creation")
    class RouterCreationTests {

        @Test
        @DisplayName("Should get router for entity")
        void testGetRouter_ValidEntity() {
            // When
            Router router = circuit.getRouter("broker-1.partition-test-0");

            // Then
            assertThat(router).isNotNull();
        }

        @Test
        @DisplayName("Should get same router for same entity")
        void testGetRouter_SameEntity() {
            // When
            Router router1 = circuit.getRouter("broker-1.partition-0");
            Router router2 = circuit.getRouter("broker-1.partition-0");

            // Then: Should return same instance (conduit caching)
            assertThat(router1).isSameAs(router2);
        }

        @Test
        @DisplayName("Should get different routers for different entities")
        void testGetRouter_DifferentEntities() {
            // When
            Router router1 = circuit.getRouter("broker-1.partition-0");
            Router router2 = circuit.getRouter("broker-2.partition-0");

            // Then: Should return different instances
            assertThat(router1).isNotSameAs(router2);
        }

        @Test
        @DisplayName("Should reject null router name")
        void testGetRouter_NullName() {
            assertThatThrownBy(() -> circuit.getRouter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("routerName cannot be null");
        }
    }

    // ========================================================================
    // Signal Emission Tests
    // ========================================================================

    @Nested
    @DisplayName("Signal Emission")
    class SignalEmissionTests {

        @BeforeEach
        void setUpSubscriber() {
            subscribeToSignals();
        }

        @Test
        @DisplayName("Should emit SEND signal")
        void testEmit_Send() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.send();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.SEND);
        }

        @Test
        @DisplayName("Should emit RECEIVE signal")
        void testEmit_Receive() {
            // Given
            Router router = circuit.getRouter("partition-0.follower");

            // When
            router.receive();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.RECEIVE);
        }

        @Test
        @DisplayName("Should emit DROP signal")
        void testEmit_Drop() {
            // Given
            Router router = circuit.getRouter("partition-0.follower");

            // When
            router.drop();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.DROP);
        }

        @Test
        @DisplayName("Should emit FORWARD signal")
        void testEmit_Forward() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.forward();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.FORWARD);
        }

        @Test
        @DisplayName("Should emit ROUTE signal")
        void testEmit_Route() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.route();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.ROUTE);
        }

        @Test
        @DisplayName("Should emit FRAGMENT signal")
        void testEmit_Fragment() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.fragment();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.FRAGMENT);
        }

        @Test
        @DisplayName("Should emit REASSEMBLE signal")
        void testEmit_Reassemble() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.reassemble();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.REASSEMBLE);
        }

        @Test
        @DisplayName("Should emit CORRUPT signal")
        void testEmit_Corrupt() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.corrupt();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.CORRUPT);
        }

        @Test
        @DisplayName("Should emit REORDER signal")
        void testEmit_Reorder() {
            // Given
            Router router = circuit.getRouter("partition-0.leader");

            // When
            router.reorder();
            circuit.await();

            // Then
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.REORDER);
        }
    }

    // ========================================================================
    // Signal Sequence Tests (RC6 Pattern)
    // ========================================================================

    @Nested
    @DisplayName("Signal Sequences")
    class SignalSequenceTests {

        @BeforeEach
        void setUpSubscriber() {
            subscribeToSignals();
        }

        @Test
        @DisplayName("Should emit healthy replication sequence (SEND → RECEIVE)")
        void testSequence_HealthyReplication() {
            // When
            circuit.demonstrateHealthyReplication();

            // Then: Should emit SEND (leader) → RECEIVE (follower)
            assertThat(capturedSignals)
                .hasSize(2)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.SEND, Sign.RECEIVE);
        }

        @Test
        @DisplayName("Should emit ISR shrink sequence (DROP)")
        void testSequence_IsrShrink() {
            // When
            circuit.demonstrateIsrShrink();

            // Then: Should emit DROP
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.DROP);
        }

        @Test
        @DisplayName("Should emit ISR expand sequence (RECEIVE)")
        void testSequence_IsrExpand() {
            // When
            circuit.demonstrateIsrExpand();

            // Then: Should emit RECEIVE
            assertThat(capturedSignals)
                .hasSize(1)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.RECEIVE);
        }

        @Test
        @DisplayName("Should emit partition reassignment sequence")
        void testSequence_PartitionReassignment() {
            // When
            circuit.demonstratePartitionReassignment();

            // Then: Should emit FRAGMENT → SEND → FORWARD → RECEIVE → DROP → REASSEMBLE
            assertThat(capturedSignals)
                .hasSize(6)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(
                    Sign.FRAGMENT,   // Old leader splits traffic
                    Sign.SEND,       // Old leader sends data
                    Sign.FORWARD,    // Forward to new broker
                    Sign.RECEIVE,    // New leader receives
                    Sign.DROP,       // Old leader drops partition
                    Sign.REASSEMBLE  // New leader consolidates
                );
        }

        @Test
        @DisplayName("Should emit all 9 routing signs")
        void testSequence_AllSigns() {
            // When
            circuit.demonstrateAllSigns();

            // Then: Should emit all 9 signs
            assertThat(capturedSignals)
                .hasSize(9)
                .extracting(RouterSignalEvent::sign)
                .containsExactlyInAnyOrder(
                    Sign.SEND,
                    Sign.RECEIVE,
                    Sign.ROUTE,
                    Sign.FORWARD,
                    Sign.DROP,
                    Sign.FRAGMENT,
                    Sign.REASSEMBLE,
                    Sign.CORRUPT,
                    Sign.REORDER
                );
        }

        @Test
        @DisplayName("Should emit multiple signals from different routers")
        void testSequence_MultipleRouters() {
            // Given
            Router leader = circuit.getRouter("partition-0.leader");
            Router follower1 = circuit.getRouter("partition-0.follower-1");
            Router follower2 = circuit.getRouter("partition-0.follower-2");

            // When
            leader.send();
            follower1.receive();
            follower2.drop();
            circuit.await();

            // Then: Should capture all signals with entity context
            assertThat(capturedSignals)
                .hasSize(3)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.SEND, Sign.RECEIVE, Sign.DROP);

            assertThat(capturedSignals)
                .extracting(RouterSignalEvent::entityName)
                .containsExactly(
                    "partition-0.leader",
                    "partition-0.follower-1",
                    "partition-0.follower-2"
                );
        }
    }

    // ========================================================================
    // Subscription Tests
    // ========================================================================

    @Nested
    @DisplayName("Subscription")
    class SubscriptionTests {

        @Test
        @DisplayName("Should receive signals after subscription")
        void testSubscription_ReceivesSignals() throws Exception {
            // Given
            CountDownLatch latch = new CountDownLatch(1);
            circuit.subscribe("test-subscriber", (subject, sign) -> {
                capturedSignals.add(new RouterSignalEvent(subject.name().toString(), sign));
                latch.countDown();
            });

            Router router = circuit.getRouter("test-router");

            // When
            router.send();
            circuit.await();

            // Then
            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(capturedSignals).hasSize(1);
        }

        @Test
        @DisplayName("Should support multiple subscribers")
        void testSubscription_MultipleSubscribers() {
            // Given
            List<RouterSignalEvent> subscriber1Signals = new ArrayList<>();
            List<RouterSignalEvent> subscriber2Signals = new ArrayList<>();

            circuit.subscribe("subscriber-1", (subject, sign) ->
                subscriber1Signals.add(new RouterSignalEvent(subject.name().toString(), sign))
            );

            circuit.subscribe("subscriber-2", (subject, sign) ->
                subscriber2Signals.add(new RouterSignalEvent(subject.name().toString(), sign))
            );

            Router router = circuit.getRouter("test-router");

            // When
            router.send();
            circuit.await();

            // Then: Both subscribers should receive the signal
            assertThat(subscriber1Signals).hasSize(1);
            assertThat(subscriber2Signals).hasSize(1);
        }

        @Test
        @DisplayName("Should reject null subscriber name")
        void testSubscription_NullName() {
            assertThatThrownBy(() ->
                circuit.subscribe(null, (subject, sign) -> {})
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("subscriberName cannot be null");
        }

        @Test
        @DisplayName("Should reject null handler")
        void testSubscription_NullHandler() {
            assertThatThrownBy(() ->
                circuit.subscribe("test-subscriber", null)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("handler cannot be null");
        }
    }

    // ========================================================================
    // Circuit Lifecycle Tests
    // ========================================================================

    @Nested
    @DisplayName("Circuit Lifecycle")
    class CircuitLifecycleTests {

        @Test
        @DisplayName("Should await signal processing")
        void testLifecycle_Await() {
            // Given
            subscribeToSignals();
            Router router = circuit.getRouter("test-router");

            // When
            router.send();
            circuit.await();

            // Then: Signal should be processed
            assertThat(capturedSignals).hasSize(1);
        }

        @Test
        @DisplayName("Should close without errors")
        void testLifecycle_Close() {
            assertThatCode(() -> circuit.close())
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should be idempotent on close")
        void testLifecycle_MultipleClose() {
            assertThatCode(() -> {
                circuit.close();
                circuit.close();
            }).doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Integration")
    class IntegrationTests {

        @Test
        @DisplayName("Should handle rapid signal emission")
        void testIntegration_RapidEmission() {
            // Given
            subscribeToSignals();
            Router router = circuit.getRouter("high-frequency-router");

            // When: Emit many signals rapidly
            for (int i = 0; i < 100; i++) {
                router.send();
            }
            circuit.await();

            // Then: All signals should be captured
            assertThat(capturedSignals).hasSize(100);
        }

        @Test
        @DisplayName("Should maintain signal order")
        void testIntegration_SignalOrder() {
            // Given
            subscribeToSignals();
            Router router = circuit.getRouter("ordered-router");

            // When: Emit signals in specific order
            router.send();
            router.receive();
            router.forward();
            router.drop();
            circuit.await();

            // Then: Should maintain order
            assertThat(capturedSignals)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.SEND, Sign.RECEIVE, Sign.FORWARD, Sign.DROP);
        }

        @Test
        @DisplayName("Should handle ISR shrink → lag → expand recovery scenario")
        void testIntegration_IsrRecoveryScenario() {
            // Given
            subscribeToSignals();
            Router leader = circuit.getRouter("broker-1.partition-0.leader");
            Router follower = circuit.getRouter("broker-1.partition-0.follower");

            // When: Simulate ISR shrink → lag → recovery
            follower.drop();           // Follower dropped from ISR
            circuit.await();

            leader.send();             // Leader continues sending
            circuit.await();

            follower.receive();        // Follower caught up, back in ISR
            circuit.await();

            // Then: Should capture complete sequence
            assertThat(capturedSignals)
                .extracting(RouterSignalEvent::sign)
                .containsExactly(Sign.DROP, Sign.SEND, Sign.RECEIVE);
        }
    }
}
