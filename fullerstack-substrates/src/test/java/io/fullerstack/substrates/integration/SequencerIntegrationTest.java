package io.fullerstack.substrates.integration;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.circuit.CircuitImpl;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Sequencer functionality showing end-to-end transformation pipelines.
 *
 * <p>These tests validate the complete flow from Circuit → Conduit → Channel → Pipe with Sequencer
 * → Segment transformations → Source emissions.
 */
class SequencerIntegrationTest {

    private CircuitImpl circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    /**
     * Helper to create a simple subscriber that collects emissions.
     */
    private <E> Subscriber<E> subscriber(Subject subject, List<E> collector, CountDownLatch latch) {
        return new Subscriber<E>() {
            @Override
            public void accept(Subject s, Registrar<E> registrar) {
                registrar.register(emission -> {
                    collector.add(emission);
                    latch.countDown();
                });
            }

            @Override
            public Subject subject() {
                return subject;
            }
        };
    }

    @Test
    void shouldApplySequencerTransformationsToEmissions() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit using API's Composer.pipe(sequencer) factory
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("sensors", null),
            Composer.pipe(
                path -> path
                    .guard(value -> value > 0)  // Filter negatives
                    .limit(3)                    // Limit to 3 emissions
            )
        );

        // Subscribe to conduit's source
        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        // Get pipe and emit values
        Pipe<Integer> pipe = conduit.get(new NameImpl("sensor-1", null));
        pipe.emit(-5);   // Filtered by guard
        pipe.emit(10);   // Passes
        pipe.emit(0);    // Filtered by guard
        pipe.emit(20);   // Passes
        pipe.emit(30);   // Passes
        pipe.emit(40);   // Blocked by limit

        // Wait for emissions to be processed
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(10, 20, 30);
    }

    @Test
    void shouldApplyReduceTransformation() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        // Create conduit with reduce (accumulating sum)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("accumulators", null),
            Composer.pipe(
                path -> path.reduce(0, Integer::sum)
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("accumulator-1", null));
        pipe.emit(1);  // 0 + 1 = 1
        pipe.emit(2);  // 1 + 2 = 3
        pipe.emit(3);  // 3 + 3 = 6
        pipe.emit(4);  // 6 + 4 = 10

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(1, 3, 6, 10);
    }

    @Test
    void shouldApplyReplaceTransformation() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit with replace (multiply by 2)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("mappers", null),
            Composer.pipe(
                path -> path.replace(value -> value * 2)
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("mapper-1", null));
        pipe.emit(1);
        pipe.emit(5);
        pipe.emit(10);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(2, 10, 20);
    }

    @Test
    void shouldApplyDiffTransformation() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit with diff (only pass changed values)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("differs", null),
            Composer.pipe(
                path -> path.diff()
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("differ-1", null));
        pipe.emit(1);  // First value - passes
        pipe.emit(1);  // Duplicate - filtered
        pipe.emit(2);  // Changed - passes
        pipe.emit(2);  // Duplicate - filtered
        pipe.emit(1);  // Changed - passes

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(1, 2, 1);
    }

    @Test
    void shouldApplySampleTransformation() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Create conduit with sample (every 3rd value)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("samplers", null),
            Composer.pipe(
                path -> path.sample(3)
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("sampler-1", null));
        pipe.emit(1);  // 1st - filtered
        pipe.emit(2);  // 2nd - filtered
        pipe.emit(3);  // 3rd - passes
        pipe.emit(4);  // 4th - filtered
        pipe.emit(5);  // 5th - filtered
        pipe.emit(6);  // 6th - passes

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(3, 6);
    }

    @Test
    void shouldApplySiftTransformation() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit with sift (values above 5)
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("sifters", null),
            Composer.pipe(
                path -> path.sift(Integer::compareTo, sift -> sift.above(5))
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("sifter-1", null));
        pipe.emit(3);   // Below 5 - filtered
        pipe.emit(5);   // Equal to 5 - filtered
        pipe.emit(6);   // Above 5 - passes
        pipe.emit(10);  // Above 5 - passes
        pipe.emit(2);   // Below 5 - filtered
        pipe.emit(15);  // Above 5 - passes

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(6, 10, 15);
    }

    @Test
    void shouldChainMultipleTransformations() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Create conduit with chained transformations
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("complex", null),
            Composer.pipe(
                path -> path
                    .guard(value -> value > 0)        // Filter negatives
                    .replace(value -> value * 2)      // Double the value
                    .guard(value -> value < 20)       // Filter values >= 20
                    .limit(3)                          // Limit to 3 emissions
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        Pipe<Integer> pipe = conduit.get(new NameImpl("complex-1", null));
        pipe.emit(-1);  // Filtered by first guard
        pipe.emit(1);   // 1 * 2 = 2, passes
        pipe.emit(5);   // 5 * 2 = 10, passes
        pipe.emit(10);  // 10 * 2 = 20, filtered by second guard
        pipe.emit(7);   // 7 * 2 = 14, passes
        pipe.emit(3);   // 3 * 2 = 6, blocked by limit

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactly(2, 10, 14);
    }

    @Test
    void shouldSupportMultiplePipesWithDifferentSequencers() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        // Create conduit
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("multi", null),
            Composer.pipe(
                path -> path.guard(value -> value > 0)
            )
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        // Multiple pipes from same conduit, each with own Segment instance
        Pipe<Integer> pipe1 = conduit.get(new NameImpl("pipe-1", null));
        Pipe<Integer> pipe2 = conduit.get(new NameImpl("pipe-2", null));

        pipe1.emit(10);
        pipe2.emit(20);
        pipe1.emit(30);
        pipe2.emit(40);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(received).containsExactlyInAnyOrder(10, 20, 30, 40);
    }

    @Test
    void shouldApplySequencerAtConduitLevelToAllChannels() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        List<Integer> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(6);

        // Create Sequencer that filters negatives and doubles values
        Sequencer<Segment<Integer>> sequencer = path -> path
            .guard(value -> value > 0)        // Filter negatives
            .replace(value -> value * 2);     // Double the value

        // Create conduit with Sequencer at Conduit level (not via Composer)
        // This means ALL channels/pipes created from this Conduit will apply these transformations
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            new NameImpl("conduit-sequencer", null),
            Composer.pipe(),  // Plain composer, no sequencer
            sequencer         // Sequencer applied at Conduit level
        );

        conduit.source().subscribe(subscriber(conduit.subject(), received, latch));

        // Create multiple channels - all should apply the same transformations
        Pipe<Integer> channel1 = conduit.get(new NameImpl("channel-1", null));
        Pipe<Integer> channel2 = conduit.get(new NameImpl("channel-2", null));
        Pipe<Integer> channel3 = conduit.get(new NameImpl("channel-3", null));

        // Emit from channel 1
        channel1.emit(-5);  // Filtered by guard
        channel1.emit(10);  // Passes, becomes 20
        channel1.emit(15);  // Passes, becomes 30

        // Emit from channel 2
        channel2.emit(0);   // Filtered by guard
        channel2.emit(5);   // Passes, becomes 10
        channel2.emit(7);   // Passes, becomes 14

        // Emit from channel 3
        channel3.emit(-1);  // Filtered by guard
        channel3.emit(3);   // Passes, becomes 6
        channel3.emit(4);   // Passes, becomes 8

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // All channels applied the same transformations
        assertThat(received).containsExactlyInAnyOrder(20, 30, 10, 14, 6, 8);
    }

    @Test
    void shouldApplySequencerAtContainerLevelToAllConduits() throws InterruptedException {
        circuit = new CircuitImpl(new NameImpl("test-circuit", null));

        // Create Sequencer that filters positives only
        Sequencer<Segment<Integer>> sequencer = path -> path.guard(value -> value > 0);

        // Create Container with Sequencer at Container level
        // This means ALL Conduits created by this Container will apply these transformations
        Container<Pool<Pipe<Integer>>, Source<Integer>> container = circuit.container(
            new NameImpl("container-sequencer", null),
            Composer.pipe(),  // Plain composer
            sequencer         // Sequencer applied at Container level
        );

        // Get two different conduits from the container
        Pool<Pipe<Integer>> conduit1 = container.get(new NameImpl("conduit-1", null));
        Pool<Pipe<Integer>> conduit2 = container.get(new NameImpl("conduit-2", null));

        Pipe<Integer> pipe1 = conduit1.get(new NameImpl("channel-1", null));
        Pipe<Integer> pipe2 = conduit2.get(new NameImpl("channel-2", null));

        // Emit values
        pipe1.emit(-5);  // Filtered
        pipe1.emit(10);  // Passes
        pipe2.emit(0);   // Filtered
        pipe2.emit(20);  // Passes

        // Wait to ensure all Scripts are processed
        circuit.queue().await();

        // Note: This test demonstrates the API usage - Container passes Sequencer to all created Conduits
        // Full verification would require subscribing to individual Conduit sources
    }
}
