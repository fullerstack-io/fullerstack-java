package io.fullerstack.substrates.conduit;

import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.name.LinkedName;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ConduitImpl - validates percept caching and container behavior.
 *
 * <p>From Substrates 101 blog:
 * "There's only one Channel per Name supplied in each call to a Conduit's get method.
 * Channels are thus managed within a container."
 */
class ConduitImplTest {

    private Cortex cortex;
    private Circuit circuit;

    @BeforeEach
    void setUp() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();
    }

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    // ========== Percept Caching (Pool Behavior) ==========

    @Test
    void shouldReturnSamePerceptForSameName() {
        // Given a conduit with Pipe composer
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("messages"),
            Composer.pipe()
        );

        Name channelName = cortex.name("producer1");

        // When we call get() twice with the same name
        Pipe<String> pipe1 = conduit.get(channelName);
        Pipe<String> pipe2 = conduit.get(channelName);

        // Then both calls return THE SAME percept instance (cached)
        assertThat(pipe1).isSameAs(pipe2);
    }

    @Test
    void shouldReturnDifferentPerceptsForDifferentNames() {
        // Given a conduit with Pipe composer
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        // When we call get() with different names
        Pipe<Long> pipe1 = conduit.get(cortex.name("counter1"));
        Pipe<Long> pipe2 = conduit.get(cortex.name("counter2"));

        // Then each name returns a DIFFERENT percept instance
        assertThat(pipe1).isNotSameAs(pipe2);
    }

    @Test
    void shouldCachePerceptsAcrossMultipleAccesses() {
        // Given a conduit with Channel composer
        Conduit<Channel<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("sensors"),
            Composer.channel()
        );

        Name sensor1 = cortex.name("temperature");
        Name sensor2 = cortex.name("pressure");

        // When we access multiple names multiple times
        Channel<Integer> temp1 = conduit.get(sensor1);
        Channel<Integer> pressure1 = conduit.get(sensor2);
        Channel<Integer> temp2 = conduit.get(sensor1);  // Second access to temperature
        Channel<Integer> pressure2 = conduit.get(sensor2);  // Second access to pressure

        // Then same names return same instances (cached)
        assertThat(temp1).isSameAs(temp2);
        assertThat(pressure1).isSameAs(pressure2);

        // And different names return different instances
        assertThat(temp1).isNotSameAs(pressure1);
    }

    @Test
    void shouldActAsContainerForPercepts() {
        // Given a conduit acting as a percept container
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("events"),
            Composer.pipe()
        );

        // When we create multiple percepts
        Pipe<String> event1 = conduit.get(cortex.name("login"));
        Pipe<String> event2 = conduit.get(cortex.name("logout"));
        Pipe<String> event3 = conduit.get(cortex.name("purchase"));

        // Then all percepts are managed by the conduit
        // Accessing them again returns the same instances
        assertThat(conduit.get(cortex.name("login"))).isSameAs(event1);
        assertThat(conduit.get(cortex.name("logout"))).isSameAs(event2);
        assertThat(conduit.get(cortex.name("purchase"))).isSameAs(event3);
    }

    // ========== Composer Integration ==========

    @Test
    void shouldApplyComposerToCreatePercepts() {
        // Given a conduit with Pipe composer
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        // When we get a percept
        Pipe<Long> pipe = conduit.get(cortex.name("value"));

        // Then the composer creates a Pipe percept
        assertThat(pipe).isNotNull();
        assertThat(pipe).isInstanceOf(Pipe.class);
    }

    @Test
    void shouldUseChannelComposerWhenSpecified() {
        // Given a conduit with Channel composer
        Conduit<Channel<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.channel()
        );

        // When we get a percept
        Channel<String> channel = conduit.get(cortex.name("source"));

        // Then the composer creates a Channel percept
        assertThat((Object) channel).isNotNull();
        assertThat(channel).isInstanceOf(Channel.class);
    }

    // ========== Subject Identity ==========

    @Test
    void shouldHaveConduitSubject() {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test-conduit"),
            Composer.pipe()
        );

        Subject subject = conduit.subject();

        assertThat((Object) subject).isNotNull();
        assertThat(subject.type()).isEqualTo(Subject.Type.CONDUIT);
    }

    @Test
    void shouldHaveHierarchicalSubjectName() {
        // Given a named circuit and conduit
        Circuit namedCircuit = cortex.circuit(cortex.name("observability"));
        Conduit<Pipe<Long>, Long> conduit = namedCircuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        // Then the conduit subject has hierarchical name
        Subject subject = conduit.subject();
        String path = subject.name().path().toString();

        assertThat(path).isEqualTo("observability.metrics");
    }

    // ========== Source Access ==========

    @Test
    void shouldProvideSource() {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Source<String> source = conduit.source();

        assertThat((Object) source).isNotNull();
    }

    @Test
    void shouldAllowSubscribingToSource() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("events"),
            Composer.pipe()
        );

        // Subscribe to the conduit's source
        final String[] received = {null};
        Subscription subscription = conduit.source().subscribe(
            cortex.subscriber(
                cortex.name("listener"),
                (subject, registrar) -> {
                    registrar.register(value -> received[0] = value);
                }
            )
        );

        // Emit through a percept
        Pipe<String> pipe = conduit.get(cortex.name("producer"));
        pipe.emit("test-message");

        Thread.sleep(50);

        assertThat(received[0]).isEqualTo("test-message");

        subscription.close();
    }

    // ========== Tap Method ==========

    @Test
    void shouldSupportTapForFluentConfiguration() {
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        // Tap returns the same conduit for fluent chaining
        Conduit<Pipe<Long>, Long> tapped = conduit.tap(c -> {
            // Configure via tap
            c.source().subscribe(cortex.subscriber(
                cortex.name("logger"),
                (subject, registrar) -> {
                    registrar.register(value -> {
                        // Log emissions
                    });
                }
            ));
        });

        assertThat(tapped).isSameAs(conduit);
    }

    // ========== Thread Safety ==========

    @Test
    void shouldHandleConcurrentGetCalls() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("concurrent"),
            Composer.pipe()
        );

        Name sharedName = cortex.name("shared-channel");

        // Multiple threads calling get() with same name
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                conduit.get(sharedName);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                conduit.get(sharedName);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // All calls should return the same cached instance
        Pipe<Integer> pipe1 = conduit.get(sharedName);
        Pipe<Integer> pipe2 = conduit.get(sharedName);

        assertThat(pipe1).isSameAs(pipe2);
    }

    // ========== Integration Test ==========

    @Test
    void shouldWorkAsPerceptContainerInCircuit() throws InterruptedException {
        // Given a circuit with a conduit
        Circuit observability = cortex.circuit(cortex.name("observability"));
        Conduit<Pipe<Long>, Long> metrics = observability.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        // When we create multiple named channels
        Pipe<Long> requestCount = metrics.get(cortex.name("requests"));
        Pipe<Long> errorCount = metrics.get(cortex.name("errors"));

        // And subscribe to the metrics source
        final long[] counts = {0, 0};
        metrics.source().subscribe(
            cortex.subscriber(
                cortex.name("aggregator"),
                (subject, registrar) -> {
                    registrar.register(value -> {
                        if (subject.name().value().contains("requests")) {
                            counts[0] += value;
                        } else if (subject.name().value().contains("errors")) {
                            counts[1] += value;
                        }
                    });
                }
            )
        );

        // When we emit values
        requestCount.emit(100L);
        errorCount.emit(5L);
        requestCount.emit(150L);

        Thread.sleep(100);

        // Then the subscriber receives all emissions
        assertThat(counts[0]).isEqualTo(250L);  // 100 + 150
        assertThat(counts[1]).isEqualTo(5L);

        // And accessing the same names returns cached percepts
        assertThat(metrics.get(cortex.name("requests"))).isSameAs(requestCount);
        assertThat(metrics.get(cortex.name("errors"))).isSameAs(errorCount);
    }
}
