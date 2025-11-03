package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DynamicBrokerDiscovery} with mocked AWS SDK.
 */
@ExtendWith(MockitoExtension.class)
class DynamicBrokerDiscoveryTest {

    @Mock
    private MSKClusterDiscovery mockDiscovery;

    @Mock
    private BiConsumer<String, BrokerMetrics> mockMetricsEmitter;

    private DynamicBrokerDiscovery dynamicDiscovery;

    private static final String TEST_CLUSTER_ARN = "arn:aws:kafka:us-east-1:123456789:cluster/test-cluster/uuid-1234";

    @BeforeEach
    void setUp() {
        // Will be initialized in each test
    }

    @AfterEach
    void tearDown() {
        if (dynamicDiscovery != null) {
            dynamicDiscovery.close();
        }
    }

    @Test
    void shouldCallDiscoveryOnSync() {
        // Given: Mock MSK discovery returning 2 brokers
        when(mockDiscovery.discover(TEST_CLUSTER_ARN)).thenReturn(
            new ClusterMetadata(
                TEST_CLUSTER_ARN,
                "test-cluster",
                "3.5.1",
                "ACTIVE",
                List.of(
                    new BrokerEndpoint("broker-1", "arn1", "b-1.test:9092", 11001),
                    new BrokerEndpoint("broker-2", "arn2", "b-2.test:9092", 11001)
                ),
                ClusterConfiguration.defaults()
            )
        );

        DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
        dynamicDiscovery = new DynamicBrokerDiscovery(mockDiscovery, TEST_CLUSTER_ARN, config, mockMetricsEmitter);

        // When: Call syncBrokers directly (unit test - no async)
        dynamicDiscovery.syncBrokers();

        // Then: Verify MSK discovery was called
        verify(mockDiscovery).discover(TEST_CLUSTER_ARN);

        // Note: Actual sensor creation will fail due to no JMX (caught and logged)
        // Integration tests verify sensor lifecycle with real/mocked brokers
    }

    @Test
    void shouldDetectBrokerChanges() {
        // Given: Initially 3 brokers
        when(mockDiscovery.discover(TEST_CLUSTER_ARN))
            .thenReturn(
                new ClusterMetadata(
                    TEST_CLUSTER_ARN,
                    "test-cluster",
                    "3.5.1",
                    "ACTIVE",
                    List.of(
                        new BrokerEndpoint("broker-1", "arn1", "b-1.test:9092", 11001),
                        new BrokerEndpoint("broker-2", "arn2", "b-2.test:9092", 11001),
                        new BrokerEndpoint("broker-3", "arn3", "b-3.test:9092", 11001)
                    ),
                    ClusterConfiguration.defaults()
                )
            );

        DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
        dynamicDiscovery = new DynamicBrokerDiscovery(mockDiscovery, TEST_CLUSTER_ARN, config, mockMetricsEmitter);

        // When: First sync
        dynamicDiscovery.syncBrokers();

        // Then: Discovery called
        verify(mockDiscovery).discover(TEST_CLUSTER_ARN);

        // When: MSK scales down to 2 brokers
        reset(mockDiscovery);
        when(mockDiscovery.discover(TEST_CLUSTER_ARN))
            .thenReturn(
                new ClusterMetadata(
                    TEST_CLUSTER_ARN,
                    "test-cluster",
                    "3.5.1",
                    "ACTIVE",
                    List.of(
                        new BrokerEndpoint("broker-1", "arn1", "b-1.test:9092", 11001),
                        new BrokerEndpoint("broker-2", "arn2", "b-2.test:9092", 11001)
                    ),
                    ClusterConfiguration.defaults()
                )
            );

        dynamicDiscovery.syncBrokers();

        // Then: Discovery called again
        verify(mockDiscovery).discover(TEST_CLUSTER_ARN);
    }

    @Test
    void shouldHandleMSKAPIFailuresGracefully() {
        // Given: Mock DiscoveryException
        when(mockDiscovery.discover(TEST_CLUSTER_ARN))
            .thenThrow(new MSKDiscoveryException("API throttled", null));

        DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
        dynamicDiscovery = new DynamicBrokerDiscovery(mockDiscovery, TEST_CLUSTER_ARN, config, mockMetricsEmitter);

        // When: Call syncBrokers (should log error, not crash)
        dynamicDiscovery.syncBrokers();

        // Then: Discovery was called, exception was caught and logged
        verify(mockDiscovery).discover(TEST_CLUSTER_ARN);
        assertThat(dynamicDiscovery.getActiveSensorCount()).isZero();
    }

    @Test
    void shouldSkipSyncWhenClusterNotActive() {
        // Given: Cluster in CREATING state (not ACTIVE)
        when(mockDiscovery.discover(TEST_CLUSTER_ARN)).thenReturn(
            new ClusterMetadata(
                TEST_CLUSTER_ARN,
                "test-cluster",
                "3.5.1",
                "CREATING",  // Not ACTIVE
                List.of(
                    new BrokerEndpoint("broker-1", "arn1", "b-1.test:9092", 11001)
                ),
                ClusterConfiguration.defaults()
            )
        );

        DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
        dynamicDiscovery = new DynamicBrokerDiscovery(mockDiscovery, TEST_CLUSTER_ARN, config, mockMetricsEmitter);

        // When: Call syncBrokers
        dynamicDiscovery.syncBrokers();

        // Then: Should skip sync (no sensor start attempts)
        verify(mockDiscovery).discover(TEST_CLUSTER_ARN);
        assertThat(dynamicDiscovery.getActiveSensorCount()).isZero();
    }

    @Test
    void shouldCloseGracefully() {
        // Given: Discovery instance
        DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
        dynamicDiscovery = new DynamicBrokerDiscovery(mockDiscovery, TEST_CLUSTER_ARN, config, mockMetricsEmitter);

        // When: Close discovery
        dynamicDiscovery.close();

        // Then: Scheduler is shutdown, no exceptions thrown
        assertThat(dynamicDiscovery.getActiveSensorCount()).isZero();
    }
}
