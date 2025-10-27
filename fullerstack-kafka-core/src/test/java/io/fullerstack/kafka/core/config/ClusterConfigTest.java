package io.fullerstack.kafka.core.config;

import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClusterConfig - AWS MSK hierarchical configuration.
 * <p>
 * Note: Health thresholds are now configured via properties files (config_broker-health.properties)
 * and loaded through HierarchicalConfig, not part of ClusterConfig.
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
                30_000
        );

        assertEquals("prod-account", config.accountName());
        assertEquals("us-east-1", config.regionName());
        assertEquals("transactions-cluster", config.clusterName());
        assertEquals("b-1.trans.xyz.kafka.us-east-1.amazonaws.com:9092,b-2.trans.xyz.kafka.us-east-1.amazonaws.com:9092",
                     config.bootstrapServers());
        assertEquals("b-1.trans.xyz.kafka.us-east-1.amazonaws.com:11001", config.jmxUrl());
        assertEquals(30_000, config.collectionIntervalMs());
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
        assertEquals("b-1.analytics.kafka.eu-west-1.amazonaws.com:9092", config.bootstrapServers());
        assertEquals("b-1.analytics.kafka.eu-west-1.amazonaws.com:11001", config.jmxUrl());
        assertEquals(30_000, config.collectionIntervalMs(), "Should use default collection interval");
    }

    @Test
    void testFactoryMethodWithDefaults() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "localhost:9092",
                "localhost:11001"
        );

        assertEquals("default-account", config.accountName());
        assertEquals("us-east-1", config.regionName());
        assertEquals("default-cluster", config.clusterName());
        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("localhost:11001", config.jmxUrl());
        assertEquals(30_000, config.collectionIntervalMs());
    }

    @Test
    void testValidation_NullAccountName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(
                        null,
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_BlankAccountName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "",
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_NullRegionName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(
                        "test-account",
                        null,
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_BlankRegionName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "",
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_NullClusterName() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        null,
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_BlankClusterName() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "",
                        "localhost:9092",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_NullBootstrapServers() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        null,
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_BlankBootstrapServers() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        "",
                        "localhost:11001",
                        30_000
                )
        );
    }

    @Test
    void testValidation_NullJmxUrl() {
        assertThrows(NullPointerException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        null,
                        30_000
                )
        );
    }

    @Test
    void testValidation_BlankJmxUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        "",
                        30_000
                )
        );
    }

    @Test
    void testValidation_InvalidCollectionInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        0
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                new ClusterConfig(
                        "test-account",
                        "us-east-1",
                        "test-cluster",
                        "localhost:9092",
                        "localhost:11001",
                        -1000
                )
        );
    }

    @Test
    void testMultipleMSKClusters() {
        ClusterConfig prod = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        ClusterConfig staging = new ClusterConfig(
                "staging-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                60_000
        );

        assertNotEquals(prod, staging);
        assertEquals("prod-account", prod.accountName());
        assertEquals("staging-account", staging.accountName());
        assertEquals(30_000, prod.collectionIntervalMs());
        assertEquals(60_000, staging.collectionIntervalMs());
    }

    @Test
    void testGetClusterName() {
        Cortex cortex = CortexRuntime.cortex();
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        Name clusterName = config.getClusterName(cortex);

        assertNotNull(clusterName);
        assertEquals("prod-account.us-east-1.transactions-cluster", clusterName.toString());
    }

    @Test
    void testExtractBrokerId_MSKFormat() {
        assertEquals("b-1", ClusterConfig.extractBrokerId("b-1.trans-xyz.kafka.us-east-1.amazonaws.com"));
        assertEquals("b-2", ClusterConfig.extractBrokerId("b-2.trans-xyz.kafka.us-east-1.amazonaws.com"));
        assertEquals("b-3", ClusterConfig.extractBrokerId("b-3.trans-xyz.kafka.us-east-1.amazonaws.com"));
    }

    @Test
    void testExtractBrokerId_LocalhostFormat() {
        // Localhost without dots remains unchanged
        assertEquals("localhost", ClusterConfig.extractBrokerId("localhost"));

        // IP addresses get split at first dot (not ideal for IPs, but this is designed for MSK broker names)
        // For localhost scenarios, use hostname "localhost" instead of IP
        assertEquals("127", ClusterConfig.extractBrokerId("127.0.0.1"));
    }

    @Test
    void testExtractBrokerId_InvalidInput() {
        assertThrows(IllegalArgumentException.class, () ->
                ClusterConfig.extractBrokerId(null)
        );

        assertThrows(IllegalArgumentException.class, () ->
                ClusterConfig.extractBrokerId("")
        );

        assertThrows(IllegalArgumentException.class, () ->
                ClusterConfig.extractBrokerId("   ")
        );
    }

    @Test
    void testGetBrokerName() {
        Cortex cortex = CortexRuntime.cortex();
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        // Test with full MSK hostname
        Name broker1Name = config.getBrokerName(cortex, "b-1.trans.kafka.us-east-1.amazonaws.com");
        assertEquals("prod-account.us-east-1.transactions-cluster.b-1", broker1Name.toString());

        // Test with simple broker ID
        Name broker2Name = config.getBrokerName(cortex, "b-2");
        assertEquals("prod-account.us-east-1.transactions-cluster.b-2", broker2Name.toString());
    }

    @Test
    void testGetBrokerName_MultipleAccounts() {
        Cortex cortex = CortexRuntime.cortex();

        ClusterConfig prodConfig = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        ClusterConfig stagingConfig = new ClusterConfig(
                "staging-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                30_000
        );

        Name prodBroker = prodConfig.getBrokerName(cortex, "b-1");
        Name stagingBroker = stagingConfig.getBrokerName(cortex, "b-1");

        assertNotEquals(prodBroker, stagingBroker);
        assertEquals("prod-account.us-east-1.transactions-cluster.b-1", prodBroker.toString());
        assertEquals("staging-account.eu-west-1.analytics-cluster.b-1", stagingBroker.toString());
    }

    @Test
    void testRecordEquality() {
        ClusterConfig config1 = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        ClusterConfig config2 = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000
        );

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testRecordInequality_DifferentAccount() {
        ClusterConfig config1 = ClusterConfig.of(
                "prod-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        ClusterConfig config2 = ClusterConfig.of(
                "staging-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        assertNotEquals(config1, config2);
    }

    @Test
    void testRecordInequality_DifferentRegion() {
        ClusterConfig config1 = ClusterConfig.of(
                "prod-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        ClusterConfig config2 = ClusterConfig.of(
                "prod-account", "eu-west-1", "transactions-cluster",
                "b-1.trans.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.trans.kafka.eu-west-1.amazonaws.com:11001"
        );

        assertNotEquals(config1, config2);
    }

    @Test
    void testRecordInequality_DifferentCluster() {
        ClusterConfig config1 = ClusterConfig.of(
                "prod-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        ClusterConfig config2 = ClusterConfig.of(
                "prod-account", "us-east-1", "analytics-cluster",
                "b-1.analytics.kafka.us-east-1.amazonaws.com:9092",
                "b-1.analytics.kafka.us-east-1.amazonaws.com:11001"
        );

        assertNotEquals(config1, config2);
    }
}
