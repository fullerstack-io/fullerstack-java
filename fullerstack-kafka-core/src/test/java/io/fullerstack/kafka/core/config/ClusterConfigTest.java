package io.fullerstack.kafka.core.config;

import io.fullerstack.serventis.config.HealthThresholds;
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterConfig - AWS MSK hierarchical configuration.
 */
class ClusterConfigTest {

    @Test
    void testFullConstructor() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.xyz.kafka.us-east-1.amazonaws.com:9092,b-2.trans.xyz.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.xyz.kafka.us-east-1.amazonaws.com:11001",
                30_000,
                null  // null triggers default health thresholds
        );

        assertEquals("prod-account", config.accountName());
        assertEquals("us-east-1", config.regionName());
        assertEquals("transactions-cluster", config.clusterName());
        assertEquals("b-1.trans.xyz.kafka.us-east-1.amazonaws.com:9092,b-2.trans.xyz.kafka.us-east-1.amazonaws.com:9092",
                     config.bootstrapServers());
        assertEquals("b-1.trans.xyz.kafka.us-east-1.amazonaws.com:11001", config.jmxUrl());
        assertEquals(30_000, config.collectionIntervalMs());
        assertNotNull(config.healthThresholds(), "healthThresholds should be defaulted");
        assertEquals(HealthThresholds.withDefaults(), config.healthThresholds());
    }

    @Test
    void testFactoryMethodWithDefaultInterval() {
        ClusterConfig config = ClusterConfig.of(
                "staging-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001"
        );

        assertEquals("staging-account", config.accountName());
        assertEquals("eu-west-1", config.regionName());
        assertEquals("analytics-cluster", config.clusterName());
        assertEquals(ClusterConfig.DEFAULT_COLLECTION_INTERVAL_MS, config.collectionIntervalMs());
    }

    @Test
    void testFactoryMethodWithDefaults() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "localhost:9092",
                "localhost:11001"
        );

        assertEquals(ClusterConfig.DEFAULT_ACCOUNT_NAME, config.accountName());
        assertEquals(ClusterConfig.DEFAULT_REGION_NAME, config.regionName());
        assertEquals(ClusterConfig.DEFAULT_CLUSTER_NAME, config.clusterName());
        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("localhost:11001", config.jmxUrl());
        assertEquals(ClusterConfig.DEFAULT_COLLECTION_INTERVAL_MS, config.collectionIntervalMs());
    }

    @Test
    void testValidation_NullAccountName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(null, "us-east-1", "cluster", "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_BlankAccountName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("", "us-east-1", "cluster", "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_NullRegionName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig("prod-account", null, "cluster", "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_BlankRegionName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "", "cluster", "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_NullClusterName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", null, "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_BlankClusterName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "", "kafka:9092", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_NullBootstrapServers() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", null, "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_BlankBootstrapServers() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", "  ", "kafka:11001", 30_000, null)
        );
    }

    @Test
    void testValidation_NullJmxUrl() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", "kafka:9092", null, 30_000, null)
        );
    }

    @Test
    void testValidation_BlankJmxUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", "kafka:9092", "", 30_000, null)
        );
    }

    @Test
    void testValidation_InvalidCollectionInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", "kafka:9092", "kafka:11001", 0, null)
        );

        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig("prod-account", "us-east-1", "cluster", "kafka:9092", "kafka:11001", -1000, null)
        );
    }

    @Test
    void testMultipleMSKClusters() {
        // Demonstrates monitoring multiple MSK clusters across accounts and regions
        ClusterConfig prodUSEast = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000, null);

        ClusterConfig prodEUWest = new ClusterConfig(
                "prod-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                30_000, null);

        ClusterConfig staging = ClusterConfig.of(
                "staging-account",
                "us-east-1",
                "test-cluster",
                "b-1.test.kafka.us-east-1.amazonaws.com:9092",
                "b-1.test.kafka.us-east-1.amazonaws.com:11001"
        );

        // Each has unique account/region/cluster combination
        assertNotEquals(prodUSEast.accountName(), staging.accountName());
        assertNotEquals(prodUSEast.regionName(), prodEUWest.regionName());
    }

    @Test
    void testMSKPortConvention() {
        // MSK exposes Kafka on port 9092 (or 9094 for TLS) and JMX on port 11001
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "my-cluster",
                "b-1.mycluster.xyz.kafka.us-east-1.amazonaws.com:9092",
                "b-1.mycluster.xyz.kafka.us-east-1.amazonaws.com:11001",  // JMX on 11001
                30_000,
                null
        );

        assertTrue(config.jmxUrl().contains(":11001"), "MSK JMX should use port 11001");
    }

    @Test
    void testExtractBrokerId_MSKFormat() {
        // AWS MSK broker naming: b-N.cluster-id.kafka.region.amazonaws.com
        assertEquals("b-1", ClusterConfig.extractBrokerId("b-1.transactions-xyz123.kafka.us-east-1.amazonaws.com"));
        assertEquals("b-2", ClusterConfig.extractBrokerId("b-2.transactions-xyz123.kafka.us-east-1.amazonaws.com"));
        assertEquals("b-3", ClusterConfig.extractBrokerId("b-3.analytics-abc456.kafka.eu-west-1.amazonaws.com"));
    }

    @Test
    void testExtractBrokerId_SimpleHostname() {
        // Non-MSK brokers (localhost, simple hostnames)
        assertEquals("localhost", ClusterConfig.extractBrokerId("localhost"));
        assertEquals("broker1", ClusterConfig.extractBrokerId("broker1"));
        assertEquals("kafka-broker-1", ClusterConfig.extractBrokerId("kafka-broker-1.example.com"));
    }

    @Test
    void testExtractBrokerId_Validation() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.extractBrokerId(null));
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.extractBrokerId(""));
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.extractBrokerId("   "));
    }

    @Test
    void testGetClusterName_UsesCortexNameComposition() {
        Cortex cortex = CortexRuntime.cortex();
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000, null);

        Name clusterName = config.getClusterName(cortex);

        // Verify hierarchical structure using toString()
        assertEquals("prod-account.us-east-1.transactions-cluster", clusterName.toString());

        // Verify it's a proper Name object, not a string
        assertInstanceOf(Name.class, clusterName);
    }

    @Test
    void testGetBrokerName_UsesCortexNameComposition() {
        Cortex cortex = CortexRuntime.cortex();
        ClusterConfig config = new ClusterConfig(
                "staging-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                30_000, null);

        // Test with full MSK hostname
        Name broker1Name = config.getBrokerName(cortex, "b-1.analytics.kafka.eu-west-1.amazonaws.com");
        assertEquals("staging-account.eu-west-1.analytics-cluster.b-1", broker1Name.toString());

        // Test with simple broker ID
        Name broker2Name = config.getBrokerName(cortex, "b-2");
        assertEquals("staging-account.eu-west-1.analytics-cluster.b-2", broker2Name.toString());

        // Verify they're proper Name objects
        assertInstanceOf(Name.class, broker1Name);
        assertInstanceOf(Name.class, broker2Name);
    }

    @Test
    void testGetClusterName_NullCortex() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001",
                30_000, null);

        assertThrows(NullPointerException.class, () -> config.getClusterName(null));
    }

    @Test
    void testGetBrokerName_NullCortex() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001",
                30_000, null);

        assertThrows(NullPointerException.class, () -> config.getBrokerName(null, "b-1"));
    }

    @Test
    void testNameComposition_MultipleClustersSameCortex() {
        // Demonstrates that using the same Cortex instance for multiple clusters
        // creates independent Name hierarchies
        Cortex cortex = CortexRuntime.cortex();

        ClusterConfig usEast = new ClusterConfig(
                "prod-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000, null);

        ClusterConfig euWest = new ClusterConfig(
                "prod-account", "eu-west-1", "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                30_000, null);

        Name usEastBroker = usEast.getBrokerName(cortex, "b-1");
        Name euWestBroker = euWest.getBrokerName(cortex, "b-1");

        // Verify they create different hierarchical Names
        assertEquals("prod-account.us-east-1.transactions-cluster.b-1", usEastBroker.toString());
        assertEquals("prod-account.eu-west-1.analytics-cluster.b-1", euWestBroker.toString());

        // Verify they're different Name instances
        assertNotEquals(usEastBroker, euWestBroker);
    }

    // ========== Health Thresholds Tests (Story 1.2) ==========

    @Test
    void testHealthThresholds_DefaultsAppliedWhenNull() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001",
                30_000,
                null  // null should trigger defaults
        );

        assertNotNull(config.healthThresholds(), "healthThresholds should not be null");
        assertEquals(HealthThresholds.withDefaults(), config.healthThresholds());
        assertEquals(0.75, config.healthThresholds().heapStable());
        assertEquals(0.90, config.healthThresholds().heapDegraded());
        assertEquals(0.70, config.healthThresholds().cpuStable());
        assertEquals(0.85, config.healthThresholds().cpuDegraded());
    }

    @Test
    void testHealthThresholds_CustomThresholds() {
        HealthThresholds customThresholds = new HealthThresholds(0.80, 0.95, 0.75, 0.90);

        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001",
                30_000,
                customThresholds
        );

        assertEquals(customThresholds, config.healthThresholds());
        assertEquals(0.80, config.healthThresholds().heapStable());
        assertEquals(0.95, config.healthThresholds().heapDegraded());
        assertEquals(0.75, config.healthThresholds().cpuStable());
        assertEquals(0.90, config.healthThresholds().cpuDegraded());
    }

    @Test
    void testFactoryMethod_Of_DefaultsHealthThresholds() {
        ClusterConfig config = ClusterConfig.of(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001"
        );

        assertEquals(HealthThresholds.withDefaults(), config.healthThresholds());
    }

    @Test
    void testFactoryMethod_Of_CustomHealthThresholds() {
        HealthThresholds customThresholds = new HealthThresholds(0.60, 0.75, 0.60, 0.75);

        ClusterConfig config = ClusterConfig.of(
                "prod-account",
                "us-east-1",
                "cluster",
                "kafka:9092",
                "kafka:11001",
                customThresholds
        );

        assertEquals(customThresholds, config.healthThresholds());
        assertEquals(0.60, config.healthThresholds().heapStable());
    }

    @Test
    void testFactoryMethod_WithDefaults_DefaultsHealthThresholds() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "localhost:9092",
                "localhost:11001"
        );

        assertEquals(HealthThresholds.withDefaults(), config.healthThresholds());
    }

    @Test
    void testBackwardCompatibility_ExistingCodeStillWorks() {
        // This test verifies that code written before Story 1.2 still works
        // by using factory methods that don't require health thresholds

        ClusterConfig config1 = ClusterConfig.withDefaults("kafka:9092", "kafka:11001");
        assertNotNull(config1.healthThresholds());

        ClusterConfig config2 = ClusterConfig.of(
                "prod", "us-east-1", "cluster", "kafka:9092", "kafka:11001"
        );
        assertNotNull(config2.healthThresholds());

        // Both should use default thresholds
        assertEquals(HealthThresholds.withDefaults(), config1.healthThresholds());
        assertEquals(HealthThresholds.withDefaults(), config2.healthThresholds());
    }

    @Test
    void testHighThroughputCluster_CustomThresholds() {
        // Use case: High-throughput cluster tolerates higher heap usage
        HealthThresholds highThroughputThresholds = new HealthThresholds(
                0.80,  // heapStable - tolerate 80% as STABLE
                0.95,  // heapDegraded
                0.75,  // cpuStable
                0.90   // cpuDegraded
        );

        ClusterConfig config = ClusterConfig.of(
                "prod-account",
                "us-east-1",
                "high-throughput-cluster",
                "b-1.kafka:9092",
                "b-1.kafka:11001",
                highThroughputThresholds
        );

        assertEquals(0.80, config.healthThresholds().heapStable());
        assertEquals(0.95, config.healthThresholds().heapDegraded());
    }

    @Test
    void testMissionCriticalCluster_CustomThresholds() {
        // Use case: Mission-critical cluster with lower DEGRADED threshold
        HealthThresholds missionCriticalThresholds = new HealthThresholds(
                0.60,  // heapStable - stricter threshold
                0.75,  // heapDegraded - trigger DEGRADED earlier
                0.60,  // cpuStable
                0.75   // cpuDegraded
        );

        ClusterConfig config = ClusterConfig.of(
                "prod-account",
                "us-east-1",
                "mission-critical-cluster",
                "b-1.kafka:9092",
                "b-1.kafka:11001",
                missionCriticalThresholds
        );

        assertEquals(0.60, config.healthThresholds().heapStable());
        assertEquals(0.75, config.healthThresholds().heapDegraded());
    }

    @Test
    void testImmutability_HealthThresholds() {
        HealthThresholds thresholds1 = new HealthThresholds(0.70, 0.85, 0.65, 0.80);
        ClusterConfig config = ClusterConfig.of(
                "prod", "us-east-1", "cluster", "kafka:9092", "kafka:11001", thresholds1
        );

        // HealthThresholds is a record, so it's immutable
        assertEquals(thresholds1, config.healthThresholds());

        // Verify record properties
        assertInstanceOf(HealthThresholds.class, config.healthThresholds());
    }
}
