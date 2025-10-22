package io.fullerstack.substrates.sink;

import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.name.NameNode;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SinkImpl - validates Sink buffering, draining, and lifecycle.
 */
class SinkImplTest {

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

    // ========== Basic Creation & Identity ==========

    @Test
    void shouldCreateSinkWithSubject() {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("messages"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context

        assertThat(sink).isNotNull();
        assertThat((Object) sink.subject()).isNotNull();
        assertThat(sink.subject().type()).isEqualTo(Sink.class);
        assertThat(sink.subject().name().value()).contains("sink");
    }

    @Test
    void shouldCreateEmptySink() {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context

        assertThat(sink.drain().toList()).isEmpty();
    }

    // ========== Buffering & Draining ==========

    @Test
    void shouldBufferSingleEmission() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit("message1");

        // Give async processing time to complete
        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).emission()).isEqualTo("message1");
        assertThat((Object) captures.get(0).subject()).isNotNull();
    }

    @Test
    void shouldBufferMultipleEmissions() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit("msg1");
        pipe.emit("msg2");
        pipe.emit("msg3");

        // Give async processing time to complete
        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(3);
        assertThat(captures.get(0).emission()).isEqualTo("msg1");
        assertThat(captures.get(1).emission()).isEqualTo("msg2");
        assertThat(captures.get(2).emission()).isEqualTo("msg3");
    }

    @Test
    void shouldClearBufferAfterDrain() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit("message");
        Thread.sleep(50);

        // First drain returns emission
        List<Capture<String, Channel<String>>> firstDrain = sink.drain().toList();
        assertThat(firstDrain).hasSize(1);

        // Second drain should be empty
        List<Capture<String, Channel<String>>> secondDrain = sink.drain().toList();
        assertThat(secondDrain).isEmpty();
    }

    @Test
    void shouldBufferEmissionsFromMultipleSources() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context

        // Multiple producers
        Pipe<String> pipe1 = conduit.get(cortex.name("producer1"));
        Pipe<String> pipe2 = conduit.get(cortex.name("producer2"));

        pipe1.emit("from-p1");
        pipe2.emit("from-p2");

        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(2);
        assertThat(captures)
            .extracting(Capture::emission)
            .containsExactlyInAnyOrder("from-p1", "from-p2");
    }

    @Test
    void shouldAccumulateBetweenDrains() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        // First batch
        pipe.emit("batch1-msg1");
        pipe.emit("batch1-msg2");
        Thread.sleep(50);

        List<Capture<String, Channel<String>>> firstBatch = sink.drain().toList();
        assertThat(firstBatch).hasSize(2);

        // Second batch
        pipe.emit("batch2-msg1");
        pipe.emit("batch2-msg2");
        pipe.emit("batch2-msg3");
        Thread.sleep(50);

        List<Capture<String, Channel<String>>> secondBatch = sink.drain().toList();
        assertThat(secondBatch).hasSize(3);
        assertThat(secondBatch)
            .extracting(Capture::emission)
            .containsExactly("batch2-msg1", "batch2-msg2", "batch2-msg3");
    }

    // ========== Capture Structure ==========

    @Test
    void shouldCaptureSubjectWithEachEmission() throws InterruptedException {
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("numbers"),
            Composer.pipe()
        );

        Sink<Long> sink = cortex.sink(conduit);
        Pipe<Long> pipe = conduit.get(cortex.name("counter"));

        pipe.emit(42L);
        Thread.sleep(50);

        List<Capture<Long, Channel<Long>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(1);
        Capture<Long, Channel<Long>> capture = captures.get(0);

        assertThat(capture.emission()).isEqualTo(42L);
        assertThat((Object) capture.subject()).isNotNull();
        assertThat(capture.subject().type()).isEqualTo(Channel.class);
    }

    @Test
    void shouldDistinguishSubjectsInCaptures() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context

        Pipe<String> pipe1 = conduit.get(cortex.name("source1"));
        Pipe<String> pipe2 = conduit.get(cortex.name("source2"));

        pipe1.emit("from-1");
        pipe2.emit("from-2");

        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(2);

        // Different subjects
        Subject subject1 = captures.get(0).subject();
        Subject subject2 = captures.get(1).subject();

        assertThat(subject1.name().value()).isNotEqualTo(subject2.name().value());
    }

    // ========== Lifecycle & Close ==========

    @Test
    void shouldStopCapturingAfterClose() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        // Emit before close
        pipe.emit("before");
        Thread.sleep(50);

        // Drain before closing to capture "before" emission
        List<Capture<String, Channel<String>>> capturesBeforeClose = sink.drain().toList();
        assertThat(capturesBeforeClose).hasSize(1);

        sink.close();

        // Emit after close
        pipe.emit("after");
        Thread.sleep(50);

        // Drain after close should be empty (no new captures after close)
        List<Capture<String, Channel<String>>> capturesAfterClose = sink.drain().toList();
        assertThat(capturesAfterClose).isEmpty();
    }

    @Test
    void shouldBeIdempotentOnClose() {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context

        // Multiple close calls should not throw
        sink.close();
        sink.close();
        sink.close();

        // Should still work
        assertThat(sink.drain().toList()).isEmpty();
    }

    @Test
    void shouldClearBufferOnClose() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit("message");
        Thread.sleep(50);

        sink.close();

        // Drain after close should be empty (buffer cleared)
        List<Capture<String, Channel<String>>> captures = sink.drain().toList();
        assertThat(captures).isEmpty();
    }

    // ========== Thread Safety ==========

    @Test
    void shouldHandleConcurrentEmissions() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("concurrent"),
            Composer.pipe()
        );

        Sink<Integer> sink = cortex.sink(conduit);
        Pipe<Integer> pipe = conduit.get(cortex.name("producer"));

        int threadCount = 10;
        int emissionsPerThread = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Emit from multiple threads
        IntStream.range(0, threadCount).forEach(threadId -> {
            new Thread(() -> {
                IntStream.range(0, emissionsPerThread).forEach(i -> {
                    pipe.emit(threadId * emissionsPerThread + i);
                });
                latch.countDown();
            }).start();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // Allow async processing to complete

        List<Capture<Integer, Channel<Integer>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(threadCount * emissionsPerThread);
    }

    @Test
    void shouldHandleConcurrentDrains() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("test"),
            Composer.pipe()
        );

        Sink<Integer> sink = cortex.sink(conduit);
        Pipe<Integer> pipe = conduit.get(cortex.name("producer"));

        // Emit some values
        IntStream.range(0, 100).forEach(pipe::emit);
        Thread.sleep(100);

        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Drain from multiple threads
        IntStream.range(0, threadCount).forEach(i -> {
            new Thread(() -> {
                sink.drain().toList(); // Concurrent drains
                latch.countDown();
            }).start();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Final drain should be empty
        assertThat(sink.drain().toList()).isEmpty();
    }

    // ========== Integration Tests ==========

    @Test
    void shouldWorkWithTransformations() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("filtered"),
            Composer.pipe(segment -> segment
                .guard(n -> n > 0)
                .replace(n -> n * 2)
            )
        );

        Sink<Integer> sink = cortex.sink(conduit);
        Pipe<Integer> pipe = conduit.get(cortex.name("producer"));

        pipe.emit(-5);  // Filtered out
        pipe.emit(10);  // Becomes 20
        pipe.emit(3);   // Becomes 6

        Thread.sleep(50);

        List<Capture<Integer, Channel<Integer>>> captures = sink.drain().toList();

        assertThat(captures)
            .hasSize(2)
            .extracting(Capture::emission)
            .containsExactly(20, 6);
    }

    @Test
    void shouldWorkWithMultipleSubscribers() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("broadcast"),
            Composer.pipe()
        );

        // Create two sinks on the same source
        Sink<String> sink1 = cortex.sink(conduit);
        Sink<String> sink2 = cortex.sink(conduit);

        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit("broadcast-message");
        Thread.sleep(50);

        // Both sinks should receive the emission
        List<Capture<String, Channel<String>>> captures1 = sink1.drain().toList();
        List<Capture<String, Channel<String>>> captures2 = sink2.drain().toList();

        assertThat(captures1).hasSize(1);
        assertThat(captures2).hasSize(1);
        assertThat(captures1.get(0).emission()).isEqualTo("broadcast-message");
        assertThat(captures2.get(0).emission()).isEqualTo("broadcast-message");

        sink1.close();
        sink2.close();
    }

    @Test
    void shouldPreserveEmissionOrder() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("ordered"),
            Composer.pipe()
        );

        Sink<Integer> sink = cortex.sink(conduit);
        Pipe<Integer> pipe = conduit.get(cortex.name("producer"));

        // Emit in specific order
        IntStream.range(0, 50).forEach(pipe::emit);

        Thread.sleep(100);

        List<Capture<Integer, Channel<Integer>>> captures = sink.drain().toList();

        assertThat(captures)
            .hasSize(50)
            .extracting(Capture::emission)
            .containsExactly(IntStream.range(0, 50).boxed().toArray(Integer[]::new));
    }

    @Test
    void shouldWorkWithContextSink() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("context-test"),
            Composer.pipe()
        );

        // Create sink from Context (alternative API)
        Sink<String> sink = cortex.sink(conduit);

        Pipe<String> pipe = conduit.get(cortex.name("producer"));
        pipe.emit("via-context");

        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures)
            .hasSize(1)
            .extracting(Capture::emission)
            .containsExactly("via-context");

        sink.close();
    }

    // ========== Edge Cases ==========

    @Test
    void shouldHandleNullEmissions() throws InterruptedException {
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("nullable"),
            Composer.pipe()
        );

        Sink<String> sink = cortex.sink(conduit);  // Conduit extends Context
        Pipe<String> pipe = conduit.get(cortex.name("producer"));

        pipe.emit(null);
        pipe.emit("not-null");

        Thread.sleep(50);

        List<Capture<String, Channel<String>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(2);
        assertThat(captures.get(0).emission()).isNull();
        assertThat(captures.get(1).emission()).isEqualTo("not-null");

        sink.close();
    }

    @Test
    void shouldHandleRapidEmissions() throws InterruptedException {
        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("rapid"),
            Composer.pipe()
        );

        Sink<Integer> sink = cortex.sink(conduit);
        Pipe<Integer> pipe = conduit.get(cortex.name("producer"));

        // Emit many values rapidly
        IntStream.range(0, 1000).forEach(pipe::emit);

        Thread.sleep(200);

        List<Capture<Integer, Channel<Integer>>> captures = sink.drain().toList();

        assertThat(captures).hasSize(1000);

        sink.close();
    }
}
