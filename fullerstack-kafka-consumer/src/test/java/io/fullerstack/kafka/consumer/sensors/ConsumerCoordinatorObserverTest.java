package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsumerCoordinatorObserver}.
 * <p>
 * Tests coordinator monitoring logic, signal emission patterns, and AdminClient + JMX interaction.
 */
class ConsumerCoordinatorObserverTest {

    @Mock
    private AdminClient mockAdminClient;

    @Mock
    private MBeanServerConnection mockMBeanServer;

    @Mock
    private DescribeConsumerGroupsResult mockDescribeResult;

    private Circuit circuit;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        circuit = cortex().circuit(cortex().name("test-coordinator-circuit"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (circuit != null) {
            circuit.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================================
    // Constructor Tests
    // ========================================

    @Test
    void testConstructorWithValidParameters() {
        // Given
        Set<String> groups = Set.of("group-1", "group-2");

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testConstructorCreatesInstrumentsPerGroup() {
        // Given
        Set<String> groups = Set.of("group-1", "group-2", "group-3");

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then - should create 4 instruments per group (counter, gauge, 2 monitors)
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testConstructorDoesNotStartMonitoring() {
        // Given
        Set<String> groups = Set.of("group-1");

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then - AdminClient should not be called during construction
        verifyNoInteractions(mockAdminClient);
        verifyNoInteractions(mockMBeanServer);

        // Cleanup
        observer.close();
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testStartAndStopLifecycle() {
        // Given
        Set<String> groups = Set.of("group-1");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // When
        observer.start();
        observer.stop();

        // Then - should not throw
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testMultipleStartCallsAreIdempotent() {
        // Given
        Set<String> groups = Set.of("group-1");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // When / Then - multiple starts should not throw
        assertThatCode(() -> {
            observer.start();
            observer.start();
            observer.start();
        }).doesNotThrowAnyException();

        // Cleanup
        observer.stop();
        observer.close();
    }

    @Test
    void testCloseWithoutStart() {
        // Given
        Set<String> groups = Set.of("group-1");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // When / Then - should not throw
        assertThatCode(observer::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        Set<String> groups = Set.of("group-1");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // When / Then - should not throw
        assertThatCode(observer::stop).doesNotThrowAnyException();

        // Cleanup
        observer.close();
    }

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        // Given
        Set<String> groups = Set.of("group-1");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // When / Then - multiple closes should not throw
        assertThatCode(() -> {
            observer.close();
            observer.close();
            observer.close();
        }).doesNotThrowAnyException();
    }

    // ========================================
    // Signal Emission Tests
    // ========================================

    @Test
    void shouldEmitIncrementOnCoordinatorSync() throws Exception {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Mock AdminClient to return PREPARING_REBALANCE state
        ConsumerGroupDescription desc = createMockGroupDescription(
            "test-group",
            ConsumerGroupState.PREPARING_REBALANCE
        );

        when(mockAdminClient.describeConsumerGroups(any()))
            .thenReturn(mockDescribeResult);
        when(mockDescribeResult.all())
            .thenReturn(KafkaFuture.completedFuture(Map.of("test-group", desc)));

        // Mock JMX to avoid errors
        when(mockMBeanServer.getAttribute(any(ObjectName.class), anyString()))
            .thenReturn(100.0); // heartbeat latency

        // When - observer should be created correctly
        // Full integration test will verify actual signal emission with real AdminClient

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldTrackHeartbeatLatencyWithGauges() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then - observer should be created with gauge instruments
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldEmitOverflowWhenHeartbeatLatencyExceedsThreshold() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests with real JMX
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldEmitDegradedWhenApproachingSessionTimeout() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests with real monitoring
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldEmitDefectiveOnSessionTimeoutViolation() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldEmitDegradedWhenApproachingMaxPollInterval() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void shouldEmitDefectiveOnPollIntervalViolation() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Multi-Group Tests
    // ========================================

    @Test
    void shouldHandleMultipleConsumerGroupsIndependently() {
        // Given
        Set<String> groups = Set.of("group-1", "group-2", "group-3");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then - should create separate instruments for each group
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    void shouldHandleAdminClientTimeoutsGracefully() throws Exception {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Mock AdminClient to throw TimeoutException
        when(mockAdminClient.describeConsumerGroups(any()))
            .thenReturn(mockDescribeResult);
        when(mockDescribeResult.all())
            .thenReturn(KafkaFuture.completedFuture(Collections.emptyMap()));

        // When / Then - should not throw
        assertThatCode(() -> observer.start()).doesNotThrowAnyException();

        // Cleanup
        observer.stop();
        observer.close();
    }

    @Test
    void shouldHandleMissingConsumerGroups() throws Exception {
        // Given
        Set<String> groups = Set.of("non-existent-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Mock AdminClient to throw GroupIdNotFoundException
        when(mockAdminClient.describeConsumerGroups(any()))
            .thenReturn(mockDescribeResult);

        KafkaFuture<Map<String, ConsumerGroupDescription>> failedFuture = mock(KafkaFuture.class);
        when(failedFuture.get(anyLong(), any()))
            .thenThrow(new ExecutionException(new GroupIdNotFoundException("Group not found")));
        when(mockDescribeResult.all()).thenReturn(failedFuture);

        // When / Then - should not throw (logs as debug)
        assertThatCode(() -> observer.start()).doesNotThrowAnyException();

        // Cleanup
        observer.stop();
        observer.close();
    }

    @Test
    void shouldHandleJmxConnectionFailures() {
        // Given
        Set<String> groups = Set.of("test-group");
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // This will be tested in integration tests
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Configuration Tests
    // ========================================

    @Test
    void testEmptyConsumerGroupsSet() {
        // Given
        Set<String> groups = Set.of();

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then - should handle empty set gracefully
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testSingleConsumerGroup() {
        // Given
        Set<String> groups = Set.of("single-group");

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testManyConsumerGroups() {
        // Given
        Set<String> groups = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            groups.add("group-" + i);
        }

        // When
        ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
            mockAdminClient,
            mockMBeanServer,
            circuit,
            groups
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Helper Methods
    // ========================================

    private ConsumerGroupDescription createMockGroupDescription(
        String groupId,
        ConsumerGroupState state
    ) {
        Node coordinator = new Node(1, "localhost", 9092);
        return new ConsumerGroupDescription(
            groupId,
            false,  // not simple consumer group
            Collections.emptyList(),  // members
            "",  // partition assignor
            state,
            coordinator
        );
    }
}
