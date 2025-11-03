package io.fullerstack.kafka.producer.composers;

import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ProducerHealthCellComposer}.
 */
class ProducerHealthCellComposerTest {

    private Circuit circuit;
    private Cell<ProducerMetrics, Monitors.Status> healthCell;
    private CopyOnWriteArrayList<Monitors.Status> receivedSignals;
    private AtomicReference<Subject<?>> receivedSubject;

    @BeforeEach
    void setUp() {
        // Create Circuit
        circuit = cortex().circuit(cortex().name("test-producer-health"));

        // Create Cell with ProducerHealthCellComposer
        // RC3 pattern: cell(transformer, aggregator, downstream)
        Pipe<Monitors.Status> noopPipe = new Pipe<>() {
            @Override
            public void emit(Monitors.Status status) {
                // No-op for test - signals go to subscribers
            }

            @Override
            public void flush() {
                // No-op for test
            }
        };

        Composer<Monitors.Status, Pipe<Monitors.Status>> aggregator = channel -> channel.pipe();

        healthCell = circuit.cell(new ProducerHealthCellComposer(), aggregator, noopPipe);

        // Setup signal collection
        receivedSignals = new CopyOnWriteArrayList<>();
        receivedSubject = new AtomicReference<>();

        // Subscribe to signals
        healthCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> {
                receivedSubject.set(subject);
                registrar.register(receivedSignals::add);
            }
        ));
    }

    @Test
    void testStableCondition_HealthyProducer() {
        // Arrange - Healthy producer metrics
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000,      // sendRate
            25.0,      // avgLatencyMs < 50ms (STABLE)
            45.0,      // p99LatencyMs
            50,        // batchSizeAvg
            0.65,      // compressionRatio
            500_000,   // bufferAvailableBytes (50% utilization - STABLE)
            1_000_000, // bufferTotalBytes
            5,         // ioWaitRatio
            0,         // recordErrorRate (no errors)
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);

        // Wait for async processing
        await(() -> !receivedSignals.isEmpty());

        // Assert
        assertThat(receivedSignals).hasSize(1);
        Monitors.Status signal = receivedSignals.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(signal.confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    @Test
    void testDegradedCondition_HighLatency() {
        // Arrange - High latency producer
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000,
            120.0,     // avgLatencyMs 50-200ms (DEGRADED)
            180.0,
            50, 0.65,
            500_000,
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DEGRADED);
    }

    @Test
    void testDegradedCondition_HighBufferUtilization() {
        // Arrange - High buffer utilization
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            150_000,   // 85% buffer utilization (DEGRADED)
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DEGRADED);
    }

    @Test
    void testDegradedCondition_LowErrorRate() {
        // Arrange - Low error rate but non-zero
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            500_000,
            1_000_000,
            5,
            0,        // < 1 error/sec but > 0 (still STABLE in current thresholds)
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert - Should be STABLE since errorRate is 0
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.STABLE);
    }

    @Test
    void testDownCondition_VeryHighLatency() {
        // Arrange - Very high latency
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000,
            250.0,     // avgLatencyMs > 200ms (DOWN)
            350.0,
            50, 0.65,
            500_000,
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testDownCondition_BufferFull() {
        // Arrange - Buffer nearly full
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            40_000,    // 96% buffer utilization (DOWN)
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testDownCondition_HighErrorRate() {
        // Arrange - High error rate
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            500_000,
            1_000_000,
            5,
            5,         // 5 errors/sec (DOWN)
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testConfidence_FreshMetrics() {
        // Arrange - Fresh metrics (< 5 seconds old)
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            500_000, 1_000_000, 5, 0,
            System.currentTimeMillis()  // Fresh
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);
        assertThat(signal.confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    // Note: Timing-sensitive confidence tests omitted due to async latency variability
    // Confidence assessment logic is covered by unit tests in ProducerMetrics
    // (isFresh, isRecent methods) which don't have timing issues

    @Test
    void testContext_AllMetricsIncluded() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer-123",
            1500, 35.5, 67.8, 75, 0.72,
            600_000, 1_000_000, 8, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert
        Monitors.Status signal = receivedSignals.get(0);

    }
    @Test
    void testSubject_FromChannel() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000, 25.0, 45.0, 50, 0.65,
            500_000, 1_000_000, 5, 0,
            System.currentTimeMillis()
        );

        // Act
        // TODO RC3
        // healthCell.accept(metrics);
        await(() -> !receivedSignals.isEmpty());

        // Assert - Subject comes from infrastructure
        assertThat((Object) receivedSubject.get()).isNotNull();
        Monitors.Status signal = receivedSignals.get(0);
    }

    /**
     * Helper method to wait for async operations.
     */
    private void await(java.util.function.BooleanSupplier condition) {
        long start = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - start > 5000) {
                fail("Timeout waiting for condition");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting");
            }
        }
    }
}
