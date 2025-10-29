package io.fullerstack.kafka.broker.composers;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.fullerstack.serventis.signals.Signal.Severity;
import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThreadPoolResourceComposer}.
 */
class ThreadPoolResourceComposerTest {

    private ThreadPoolResourceComposer composer;
    private Channel<ResourceSignal> mockChannel;
    private Pipe<ResourceSignal> mockOutputPipe;
    private Subject mockSubject;

    @BeforeEach
    void setUp() {
        composer = new ThreadPoolResourceComposer();

        mockChannel = mock(Channel.class);
        mockOutputPipe = mock(Pipe.class);
        mockSubject = mock(Subject.class);

        when(mockChannel.pipe()).thenReturn(mockOutputPipe);
        when(mockChannel.subject()).thenReturn(mockSubject);
    }

    @Test
    void composedPipeEmitsGrantSignalForHealthyThreadPool() {
        // Given: Healthy thread pool (50% idle)
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 5, 5,
            0.50,  // 50% idle
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        // Capture emitted signal
        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        // When: Compose and emit
        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        // Then: GRANT signal emitted
        ResourceSignal signal = capturedSignal.get();
        assertThat(signal).isNotNull();
        assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        assertThat(signal.units()).isEqualTo(5);  // 5 idle threads available
        assertThat(signal.payload().get("state")).isEqualTo("AVAILABLE");
    }

    @Test
    void composedPipeEmitsGrantSignalForDegradedThreadPool() {
        // Given: Degraded thread pool (20% idle)
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            10, 8, 2,
            0.20,  // 20% idle (DEGRADED)
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        // Capture emitted signal
        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        // When: Compose and emit
        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        // Then: GRANT signal with DEGRADED state
        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        assertThat(signal.units()).isEqualTo(2);  // 2 idle threads available
        assertThat(signal.payload().get("state")).isEqualTo("DEGRADED");
    }

    @Test
    void composedPipeEmitsDenySignalForExhaustedThreadPool() {
        // Given: Exhausted thread pool (5% idle)
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 9, 1,
            0.05,  // 5% idle (EXHAUSTED)
            10L, 0L, 5L,
            System.currentTimeMillis()
        );

        // Capture emitted signal
        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        // When: Compose and emit
        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        // Then: DENY signal emitted
        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        assertThat(signal.units()).isEqualTo(0);  // No capacity
        assertThat(signal.payload().get("state")).isEqualTo("EXHAUSTED");
    }

    @Test
    void denySignalHasErrorSeverity() {
        // Given: Exhausted pool
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            8, 8, 0,
            0.0,  // 0% idle
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.severity()).isEqualTo(Severity.ERROR);
        assertThat(signal.requiresAttention()).isTrue();
    }

    @Test
    void grantSignalHasInfoSeverity() {
        // Given: Healthy pool
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 3, 7,
            0.70,  // 70% idle
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.severity()).isEqualTo(Severity.INFO);
        assertThat(signal.requiresAttention()).isFalse();
    }

    @Test
    void contextIncludesAllRelevantFields() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-2",
            ThreadPoolType.IO,
            8, 6, 2,
            0.25,
            15L,    // queueSize
            12345L,
            7L,     // rejectionCount
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.payload()).containsEntry("brokerId", "broker-2");
        assertThat(signal.payload()).containsEntry("poolType", "io");
        assertThat(signal.payload()).containsEntry("totalThreads", "8");
        assertThat(signal.payload()).containsEntry("activeThreads", "6");
        assertThat(signal.payload()).containsEntry("idleThreads", "2");
        assertThat(signal.payload()).containsKey("avgIdlePercent");
        assertThat(signal.payload()).containsKey("utilizationPercent");
        assertThat(signal.payload()).containsEntry("queueSize", "15");
        assertThat(signal.payload()).containsEntry("rejectionCount", "7");
    }

    @Test
    void contextOmitsZeroQueueSizeAndRejections() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 5, 5,
            0.50,
            0L,  // queueSize = 0
            0L,
            0L,  // rejectionCount = 0
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> capturedSignal = new AtomicReference<>();
        doAnswer(inv -> {
            capturedSignal.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(metrics);

        ResourceSignal signal = capturedSignal.get();
        assertThat(signal.payload()).doesNotContainKey("queueSize");
        assertThat(signal.payload()).doesNotContainKey("rejectionCount");
    }

    @Test
    void stateTransitionFromAvailableToDegraded() {
        // First: Healthy (40% idle)
        ThreadPoolMetrics healthy = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 6, 4,
            0.40,
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> signal1 = new AtomicReference<>();
        doAnswer(inv -> {
            signal1.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);
        inputPipe.emit(healthy);

        assertThat(signal1.get().payload().get("state")).isEqualTo("AVAILABLE");
        assertThat(signal1.get().sign()).isEqualTo(Resources.Sign.GRANT);

        // Second: Degraded (20% idle)
        ThreadPoolMetrics degraded = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 8, 2,
            0.20,
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> signal2 = new AtomicReference<>();
        doAnswer(inv -> {
            signal2.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        inputPipe.emit(degraded);

        assertThat(signal2.get().payload().get("state")).isEqualTo("DEGRADED");
        assertThat(signal2.get().sign()).isEqualTo(Resources.Sign.GRANT);  // Still GRANT
    }

    @Test
    void stateTransitionFromDegradedToExhausted() {
        Pipe<ThreadPoolMetrics> inputPipe = composer.compose(mockChannel);

        // Degraded (15% idle)
        ThreadPoolMetrics degraded = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            8, 7, 1,
            0.15,
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> signal1 = new AtomicReference<>();
        doAnswer(inv -> {
            signal1.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        inputPipe.emit(degraded);

        assertThat(signal1.get().payload().get("state")).isEqualTo("DEGRADED");
        assertThat(signal1.get().sign()).isEqualTo(Resources.Sign.GRANT);

        // Exhausted (5% idle)
        ThreadPoolMetrics exhausted = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            8, 8, 0,
            0.05,
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        AtomicReference<ResourceSignal> signal2 = new AtomicReference<>();
        doAnswer(inv -> {
            signal2.set(inv.getArgument(0));
            return null;
        }).when(mockOutputPipe).emit(any(ResourceSignal.class));

        inputPipe.emit(exhausted);

        assertThat(signal2.get().payload().get("state")).isEqualTo("EXHAUSTED");
        assertThat(signal2.get().sign()).isEqualTo(Resources.Sign.DENY);  // Now DENY
        assertThat(signal2.get().requiresAttention()).isTrue();
    }

}
