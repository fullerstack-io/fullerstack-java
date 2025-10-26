package io.fullerstack.substrates.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link HierarchicalConfig}.
 * <p>
 * Coverage:
 * - Global configuration (config.properties)
 * - Circuit-specific configuration (fallback to global)
 * - Container-specific configuration (fallback chain)
 * - System property overrides
 * - Type-safe getters (int, long, double, boolean, String)
 * - Default value handling
 * - Error handling (missing keys, invalid formats)
 */
class HierarchicalConfigTest {

    @AfterEach
    void clearSystemProperties() {
        // Clean up any system properties set during tests
        System.clearProperty("valve.queue-size");
        System.clearProperty("test.property");
        System.clearProperty("valve.shutdown-timeout-ms");
    }

    // =========================================================================
    // Global Configuration Tests
    // =========================================================================

    @Test
    void testGlobal_ReturnsGlobalConfig() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config).isNotNull();
        assertThat(config.context()).isEqualTo("global");
    }

    @Test
    void testGlobal_ReadsValveQueueSize() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("valve.queue-size")).isEqualTo(1000);
    }

    @Test
    void testGlobal_ReadsValveShutdownTimeout() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getLong("valve.shutdown-timeout-ms")).isEqualTo(5000L);
    }

    @Test
    void testGlobal_ReadsConduitBufferSize() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("conduit.buffer-size")).isEqualTo(256);
    }

    @Test
    void testGlobal_ReadsSignalBatchingEnabled() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getBoolean("signals.batching-enabled")).isTrue();
    }

    @Test
    void testGlobal_ReadsSignalBatchSize() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("signals.batch-size")).isEqualTo(100);
    }

    @Test
    void testGlobal_ReadsResourceAutoCleanup() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getBoolean("resources.auto-cleanup")).isTrue();
    }

    // =========================================================================
    // Circuit-Specific Configuration Tests
    // =========================================================================

    @Test
    void testForCircuit_FallsBackToGlobal() {
        // No circuit-specific properties file exists, should use global defaults
        HierarchicalConfig config = HierarchicalConfig.forCircuit("broker-health");

        assertThat(config.context()).isEqualTo("circuit:broker-health");
        assertThat(config.getInt("valve.queue-size")).isEqualTo(1000);
    }

    @Test
    void testForCircuit_NullCircuitName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forCircuit(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("circuitName cannot be null");
    }

    @Test
    void testForCircuit_BlankCircuitName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forCircuit(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("circuitName cannot be blank");

        assertThatThrownBy(() -> HierarchicalConfig.forCircuit("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("circuitName cannot be blank");
    }

    // =========================================================================
    // Container-Specific Configuration Tests
    // =========================================================================

    @Test
    void testForContainer_FallsBackToGlobal() {
        // No container-specific properties file exists, should use global defaults
        HierarchicalConfig config = HierarchicalConfig.forContainer("broker-health", "brokers");

        assertThat(config.context()).isEqualTo("container:broker-health/brokers");
        assertThat(config.getInt("valve.queue-size")).isEqualTo(1000);
    }

    @Test
    void testForContainer_NullCircuitName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forContainer(null, "brokers"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("circuitName cannot be null");
    }

    @Test
    void testForContainer_NullContainerName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forContainer("broker-health", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("containerName cannot be null");
    }

    @Test
    void testForContainer_BlankCircuitName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forContainer("", "brokers"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("circuitName cannot be blank");
    }

    @Test
    void testForContainer_BlankContainerName_ThrowsException() {
        assertThatThrownBy(() -> HierarchicalConfig.forContainer("broker-health", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("containerName cannot be blank");
    }

    // =========================================================================
    // System Property Override Tests
    // =========================================================================

    @Test
    void testSystemPropertyOverride_Int() {
        System.setProperty("valve.queue-size", "20000");

        HierarchicalConfig config = HierarchicalConfig.global();
        assertThat(config.getInt("valve.queue-size")).isEqualTo(20000);
    }

    @Test
    void testSystemPropertyOverride_Long() {
        System.setProperty("valve.shutdown-timeout-ms", "10000");

        HierarchicalConfig config = HierarchicalConfig.global();
        assertThat(config.getLong("valve.shutdown-timeout-ms")).isEqualTo(10000L);
    }

    @Test
    void testSystemPropertyOverride_Boolean() {
        System.setProperty("signals.batching-enabled", "false");

        HierarchicalConfig config = HierarchicalConfig.global();
        assertThat(config.getBoolean("signals.batching-enabled")).isFalse();
    }

    @Test
    void testSystemPropertyOverride_String() {
        System.setProperty("test.property", "override-value");

        HierarchicalConfig config = HierarchicalConfig.global();
        assertThat(config.getString("test.property", "default")).isEqualTo("override-value");
    }

    @Test
    void testSystemPropertyOverride_CircuitConfig() {
        System.setProperty("valve.queue-size", "30000");

        HierarchicalConfig config = HierarchicalConfig.forCircuit("broker-health");
        assertThat(config.getInt("valve.queue-size")).isEqualTo(30000);
    }

    // =========================================================================
    // Type-Safe Getter Tests
    // =========================================================================

    @Test
    void testGetInt_ValidValue() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("valve.queue-size")).isEqualTo(1000);
    }

    @Test
    void testGetInt_MissingKey_ThrowsException() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThatThrownBy(() -> config.getInt("nonexistent.key"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Missing config key 'nonexistent.key'");
    }

    @Test
    void testGetInt_WithDefault_ReturnsDefault() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("nonexistent.key", 9999)).isEqualTo(9999);
    }

    @Test
    void testGetLong_ValidValue() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getLong("valve.shutdown-timeout-ms")).isEqualTo(5000L);
    }

    @Test
    void testGetLong_WithDefault_ReturnsDefault() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getLong("nonexistent.key", 9999L)).isEqualTo(9999L);
    }

    @Test
    void testGetDouble_WithDefault_ReturnsDefault() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getDouble("nonexistent.key", 0.75)).isEqualTo(0.75);
    }

    @Test
    void testGetBoolean_ValidValue() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getBoolean("signals.batching-enabled")).isTrue();
        assertThat(config.getBoolean("resources.auto-cleanup")).isTrue();
    }

    @Test
    void testGetBoolean_WithDefault_ReturnsDefault() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getBoolean("nonexistent.key", false)).isFalse();
        assertThat(config.getBoolean("nonexistent.key", true)).isTrue();
    }

    @Test
    void testGetString_ValidValue() {
        HierarchicalConfig config = HierarchicalConfig.global();

        // Reading an int value as string should work
        assertThat(config.getString("valve.queue-size")).isEqualTo("1000");
    }

    @Test
    void testGetString_MissingKey_ThrowsException() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThatThrownBy(() -> config.getString("nonexistent.key"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Missing config key 'nonexistent.key'");
    }

    @Test
    void testGetString_WithDefault_ReturnsDefault() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getString("nonexistent.key", "default-value"))
            .isEqualTo("default-value");
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    void testGetInt_InvalidFormat_ThrowsException() {
        System.setProperty("test.property", "not-a-number");

        HierarchicalConfig config = HierarchicalConfig.global();

        assertThatThrownBy(() -> config.getInt("test.property"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Invalid int value for key 'test.property'")
            .hasMessageContaining("not-a-number");
    }

    @Test
    void testGetInt_InvalidFormat_WithDefault_ReturnsDefault() {
        System.setProperty("test.property", "not-a-number");

        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.getInt("test.property", 9999)).isEqualTo(9999);
    }

    @Test
    void testGetLong_InvalidFormat_ThrowsException() {
        System.setProperty("test.property", "not-a-number");

        HierarchicalConfig config = HierarchicalConfig.global();

        assertThatThrownBy(() -> config.getLong("test.property"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Invalid long value for key 'test.property'");
    }

    @Test
    void testGetDouble_InvalidFormat_ThrowsException() {
        System.setProperty("test.property", "not-a-number");

        HierarchicalConfig config = HierarchicalConfig.global();

        assertThatThrownBy(() -> config.getDouble("test.property"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Invalid double value for key 'test.property'");
    }

    // =========================================================================
    // Utility Method Tests
    // =========================================================================

    @Test
    void testContains_ExistingKey_ReturnsTrue() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.contains("valve.queue-size")).isTrue();
    }

    @Test
    void testContains_NonExistentKey_ReturnsFalse() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.contains("nonexistent.key")).isFalse();
    }

    @Test
    void testContains_SystemPropertyKey_ReturnsTrue() {
        System.setProperty("test.property", "value");

        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.contains("test.property")).isTrue();
    }

    @Test
    void testKeys_ReturnsAllKeys() {
        HierarchicalConfig config = HierarchicalConfig.global();

        assertThat(config.keys())
            .contains("valve.queue-size")
            .contains("valve.shutdown-timeout-ms")
            .contains("conduit.buffer-size")
            .contains("signals.batching-enabled");
    }

    @Test
    void testToString_ReturnsContextDescription() {
        HierarchicalConfig global = HierarchicalConfig.global();
        assertThat(global.toString()).isEqualTo("HierarchicalConfig[context=global]");

        HierarchicalConfig circuit = HierarchicalConfig.forCircuit("broker-health");
        assertThat(circuit.toString()).isEqualTo("HierarchicalConfig[context=circuit:broker-health]");

        HierarchicalConfig container = HierarchicalConfig.forContainer("broker-health", "brokers");
        assertThat(container.toString()).isEqualTo("HierarchicalConfig[context=container:broker-health/brokers]");
    }
}
