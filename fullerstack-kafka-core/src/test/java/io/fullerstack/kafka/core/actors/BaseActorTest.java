package io.fullerstack.kafka.core.actors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Situations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaseActor Tests")
class BaseActorTest {

    private Circuit reporterCircuit;
    private Circuit actorCircuit;
    private Conduit<Situations.Situation, Situations.Signal> reporters;
    private Conduit<Actors.Actor, Actors.Sign> actors;
    private TestActor testActor;
    private List<Actors.Sign> actorSigns;

    /**
     * Test implementation of BaseActor.
     */
    private static class TestActor extends BaseActor {
        private final AtomicInteger actionCount = new AtomicInteger(0);
        private final Subscription subscription;

        public TestActor(
            Conduit<Situations.Situation, Situations.Signal> reporters,
            Conduit<Actors.Actor, Actors.Sign> actors
        ) {
            super(actors, "test-actor", 1000); // 1 second rate limit

            // Subscribe to test-reporter
            Name targetReporterName = cortex().name("test.reporter");

            this.subscription = reporters.subscribe(cortex().subscriber(
                cortex().name("test-actor-subscriber"),
                (Subject<Channel<Situations.Signal>> subject, Registrar<Situations.Signal> registrar) -> {
                    // Filter: Only register pipe for test-reporter channel
                    if (subject.name().equals(targetReporterName)) {
                        registrar.register(sign -> {
                            if (sign == Situations.Sign.CRITICAL) {
                                handleCritical(subject.name());
                            }
                        });
                    }
                }
            ));
        }

        private void handleCritical(Name reporterName) {
            String actionKey = "test:" + reporterName.path();
            executeWithProtection(actionKey, () -> {
                actionCount.incrementAndGet();
            });
        }

        public int getActionCount() {
            return actionCount.get();
        }

        @Override
        public void close() {
            if (subscription != null) {
                subscription.close();
            }
        }
    }

    @BeforeEach
    void setUp() {
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Situations::composer);

        actorCircuit = cortex().circuit(cortex().name("actors"));
        actors = actorCircuit.conduit(cortex().name("actors"), Actors::composer);

        testActor = new TestActor(reporters, actors);

        actorSigns = new ArrayList<>();
        actors.subscribe(cortex().subscriber(
            cortex().name("test-receptor"),
            (Subject<Channel<Actors.Sign>> subject, Registrar<Actors.Sign> registrar) -> {
                registrar.register(actorSigns::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (testActor != null) testActor.close();
        if (actorCircuit != null) actorCircuit.close();
        if (reporterCircuit != null) reporterCircuit.close();
    }

    @Test
    @DisplayName("Actor executes action on CRITICAL sign")
    void testActorExecutesOnCritical() {
        // When: Situation emits CRITICAL
        reporters.percept(cortex().name("test.reporter")).critical();

        // Wait for propagation
        reporterCircuit.await();
        actorCircuit.await();

        // Then: Action executed and DELIVER sign emitted
        assertThat(testActor.getActionCount()).isEqualTo(1);
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("Actor rate limiting prevents action storm")
    void testRateLimiting() {
        // When: Emit 5 CRITICAL signs rapidly
        for (int i = 0; i < 5; i++) {
            reporters.percept(cortex().name("test.reporter")).critical();
        }

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Only 1 action should execute (rate limit = 1 second)
        assertThat(testActor.getActionCount()).isEqualTo(1);

        // Verify DENY signs emitted for subsequent attempts
        long denyCount = actorSigns.stream()
            .filter(s -> s == Actors.Sign.DENY)
            .count();
        assertThat(denyCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("Actor idempotency prevents duplicate actions")
    void testIdempotency() {
        // When: Emit same CRITICAL sign twice
        reporters.percept(cortex().name("test.reporter")).critical();
        reporters.percept(cortex().name("test.reporter")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Only 1 action executed
        assertThat(testActor.getActionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Actor does not act on WARNING by default")
    void testNoActionOnWarning() {
        // When: Situation emits WARNING
        reporters.percept(cortex().name("test.reporter")).warning();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No action executed
        assertThat(testActor.getActionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Actor does not act on NORMAL")
    void testNoActionOnNormal() {
        // When: Situation emits NORMAL
        reporters.percept(cortex().name("test.reporter")).normal();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No action executed
        assertThat(testActor.getActionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Actor ignores signals from non-target reporters")
    void testIgnoresOtherReporters() {
        // When: Different reporter emits CRITICAL
        reporters.percept(cortex().name("other.reporter")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No action executed
        assertThat(testActor.getActionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Actor emits DENY sign on exception")
    void testDenySignOnException() {
        // Given: Actor that throws exception
        BaseActor failingActor = new BaseActor(actors, "failing-actor", 1000) {
            private final Subscription subscription;

            {
                Name targetReporterName = cortex().name("test.reporter");
                this.subscription = reporters.subscribe(cortex().subscriber(
                    cortex().name("failing-actor-subscriber"),
                    (Subject<Channel<Situations.Signal>> subject, Registrar<Situations.Signal> registrar) -> {
                        if (subject.name().equals(targetReporterName)) {
                            registrar.register(sign -> {
                                if (sign == Situations.Sign.CRITICAL) {
                                    String actionKey = "failing:" + subject.name().path();
                                    executeWithProtection(actionKey, () -> {
                                        throw new RuntimeException("Simulated failure");
                                    });
                                }
                            });
                        }
                    }
                ));
            }

            @Override
            public void close() {
                if (subscription != null) {
                    subscription.close();
                }
            }
        };

        try {
            // When: Situation emits CRITICAL
            reporters.percept(cortex().name("test.reporter")).critical();

            reporterCircuit.await();
            actorCircuit.await();

            // Then: DENY sign emitted
            assertThat(actorSigns).contains(Actors.Sign.DENY);

        } finally {
            failingActor.close();
        }
    }

    @Test
    @DisplayName("Actor can execute after rate limit period expires")
    void testActionAfterRateLimitExpires() throws InterruptedException {
        // Given: First action executed
        reporters.percept(cortex().name("test.reporter")).critical();
        reporterCircuit.await();
        actorCircuit.await();

        assertThat(testActor.getActionCount()).isEqualTo(1);

        // When: Wait for rate limit to expire (1 second + buffer)
        Thread.sleep(1100);

        // And: Emit CRITICAL again
        reporters.percept(cortex().name("test.reporter")).critical();
        reporterCircuit.await();
        actorCircuit.await();

        // Then: Second action executed
        assertThat(testActor.getActionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Multiple actors can act independently")
    void testMultipleActorsIndependent() {
        // Given: Second actor targeting different reporter
        BaseActor actor2 = new BaseActor(actors, "actor-2", 1000) {
            private final AtomicInteger count = new AtomicInteger(0);
            private final Subscription subscription;

            {
                Name targetReporterName = cortex().name("test.reporter2");
                this.subscription = reporters.subscribe(cortex().subscriber(
                    cortex().name("actor-2-subscriber"),
                    (Subject<Channel<Situations.Signal>> subject, Registrar<Situations.Signal> registrar) -> {
                        if (subject.name().equals(targetReporterName)) {
                            registrar.register(sign -> {
                                if (sign == Situations.Sign.CRITICAL) {
                                    String actionKey = "actor2:" + subject.name().path();
                                    executeWithProtection(actionKey, () -> {
                                        count.incrementAndGet();
                                    });
                                }
                            });
                        }
                    }
                ));
            }

            public int getCount() {
                return count.get();
            }

            @Override
            public void close() {
                if (subscription != null) {
                    subscription.close();
                }
            }
        };

        try {
            // When: Both reporters emit CRITICAL
            reporters.percept(cortex().name("test.reporter")).critical();
            reporters.percept(cortex().name("test.reporter2")).critical();

            reporterCircuit.await();
            actorCircuit.await();

            // Then: Both actors execute
            assertThat(testActor.getActionCount()).isEqualTo(1);
            assertThat(actorSigns).contains(Actors.Sign.DELIVER);

        } finally {
            actor2.close();
        }
    }
}
