package io.fullerstack.serventis.config;

import io.fullerstack.substrates.config.HierarchicalConfig;

/**
 * Configurable health assessment thresholds for monitoring.
 * <p>
 * Defines thresholds for STABLE/DEGRADED condition transitions based on:
 * - Heap usage percentage (0.0 - 1.0)
 * - CPU usage percentage (0.0 - 1.0)
 * <p>
 * Thresholds must satisfy: STABLE &lt; DEGRADED for both heap and CPU.
 * <p>
 * <strong>Threshold Guidance:</strong>
 * <ul>
 *   <li><strong>Heap STABLE (0.75 default):</strong> Below this, heap usage is healthy</li>
 *   <li><strong>Heap DEGRADED (0.90 default):</strong> Above this, heap pressure is critical</li>
 *   <li><strong>CPU STABLE (0.70 default):</strong> Below this, CPU usage is healthy</li>
 *   <li><strong>CPU DEGRADED (0.85 default):</strong> Above this, CPU saturation is critical</li>
 * </ul>
 * <p>
 * <strong>Condition Assessment Logic:</strong>
 * <ul>
 *   <li><strong>STABLE:</strong> Both heap and CPU below STABLE thresholds</li>
 *   <li><strong>DEGRADED:</strong> Either heap or CPU above DEGRADED thresholds</li>
 *   <li><strong>DOWN:</strong> Both heap and CPU above DEGRADED thresholds (critical)</li>
 * </ul>
 * <p>
 * <strong>Use Case Examples:</strong>
 * <ul>
 *   <li><strong>High-throughput systems:</strong> May tolerate 80% heap as STABLE</li>
 *   <li><strong>Mission-critical systems:</strong> May want 60% heap as DEGRADED threshold</li>
 *   <li><strong>Development environments:</strong> May relax CPU thresholds to 0.80/0.95</li>
 * </ul>
 * <p>
 * <strong>Usage with Framework Configuration:</strong>
 * <pre>
 * // Recommended: Use framework configuration (multi-layer overrides)
 * ServentisConfig config = ConfigProvider.getConfig();
 * HealthThresholds thresholds = HealthThresholds.fromConfig(config);
 *
 * // Alternative: Use defaults
 * HealthThresholds defaults = HealthThresholds.withDefaults();
 *
 * // Testing: Use custom values
 * HealthThresholds custom = new HealthThresholds(
 *     0.80,  // heapStable - tolerate higher heap
 *     0.95,  // heapDegraded
 *     0.75,  // cpuStable
 *     0.90   // cpuDegraded
 * );
 * </pre>
 *
 * <p>
 * <strong>Runtime Configuration Overrides:</strong>
 * <pre>
 * # System properties
 * java -Dserventis.health.thresholds.heap.stable=0.60 -jar app.jar
 *
 * # Environment variables
 * export SERVENTIS_HEALTH_THRESHOLDS_HEAP_STABLE=0.60
 * </pre>
 *
 * @param heapStable Heap usage threshold for STABLE condition (0.0 - 1.0, e.g., 0.75 = 75%)
 * @param heapDegraded Heap usage threshold for DEGRADED condition (0.0 - 1.0, e.g., 0.90 = 90%)
 * @param cpuStable CPU usage threshold for STABLE condition (0.0 - 1.0, e.g., 0.70 = 70%)
 * @param cpuDegraded CPU usage threshold for DEGRADED condition (0.0 - 1.0, e.g., 0.85 = 85%)
 */
public record HealthThresholds(
    double heapStable,
    double heapDegraded,
    double cpuStable,
    double cpuDegraded
) {
    /**
     * Compact constructor with validation.
     * <p>
     * Validates:
     * - All thresholds are in range [0.0, 1.0]
     * - heapStable &lt; heapDegraded
     * - cpuStable &lt; cpuDegraded
     *
     * @throws IllegalArgumentException if validation fails
     */
    public HealthThresholds {
        // Validate range [0.0, 1.0]
        if (heapStable < 0.0 || heapStable > 1.0) {
            throw new IllegalArgumentException(
                "heapStable must be in range [0.0, 1.0], got: " + heapStable
            );
        }
        if (heapDegraded < 0.0 || heapDegraded > 1.0) {
            throw new IllegalArgumentException(
                "heapDegraded must be in range [0.0, 1.0], got: " + heapDegraded
            );
        }
        if (cpuStable < 0.0 || cpuStable > 1.0) {
            throw new IllegalArgumentException(
                "cpuStable must be in range [0.0, 1.0], got: " + cpuStable
            );
        }
        if (cpuDegraded < 0.0 || cpuDegraded > 1.0) {
            throw new IllegalArgumentException(
                "cpuDegraded must be in range [0.0, 1.0], got: " + cpuDegraded
            );
        }

        // Validate stable < degraded
        if (heapStable >= heapDegraded) {
            throw new IllegalArgumentException(
                "heapStable (" + heapStable + ") must be < heapDegraded (" + heapDegraded + ")"
            );
        }
        if (cpuStable >= cpuDegraded) {
            throw new IllegalArgumentException(
                "cpuStable (" + cpuStable + ") must be < cpuDegraded (" + cpuDegraded + ")"
            );
        }
    }

    /**
     * Create HealthThresholds from global configuration.
     *
     * <p>This is the recommended way to create HealthThresholds in production code.
     * It leverages the hierarchical configuration system (circuit → global → defaults).
     *
     * <p>Example:
     * <pre>
     * HierarchicalConfig config = HierarchicalConfig.global();
     * HealthThresholds thresholds = HealthThresholds.fromConfig(config);
     * </pre>
     *
     * @param config Hierarchical configuration
     * @return HealthThresholds with values from config
     */
    public static HealthThresholds fromConfig(HierarchicalConfig config) {
        return new HealthThresholds(
            config.getDouble("health.thresholds.heap.stable"),
            config.getDouble("health.thresholds.heap.degraded"),
            config.getDouble("health.thresholds.cpu.stable"),
            config.getDouble("health.thresholds.cpu.degraded")
        );
    }

    /**
     * Create HealthThresholds for a specific circuit.
     *
     * <p>Uses circuit-specific overrides if configured, otherwise falls back to global defaults.
     *
     * <p>Example:
     * <pre>
     * HierarchicalConfig config = HierarchicalConfig.forCircuit("broker-health");
     * HealthThresholds brokerThresholds = HealthThresholds.forCircuit("broker-health");
     * // Uses config_broker-health.properties if it exists, otherwise config.properties
     * </pre>
     *
     * @param circuitName Circuit name (e.g., "broker-health")
     * @return HealthThresholds with circuit-specific values (or global fallback)
     */
    public static HealthThresholds forCircuit(String circuitName) {
        HierarchicalConfig config = HierarchicalConfig.forCircuit(circuitName);
        return fromConfig(config);
    }

    /**
     * Create HealthThresholds for a specific container within a circuit.
     *
     * <p>Uses container-specific overrides if configured, otherwise falls back to
     * circuit defaults, then global defaults.
     *
     * <p>Example:
     * <pre>
     * HealthThresholds brokerThresholds = HealthThresholds.forContainer(
     *     "broker-health", "brokers"
     * );
     * // Uses config_broker-health-brokers.properties if it exists,
     * // otherwise config_broker-health.properties, otherwise config.properties
     * </pre>
     *
     * @param circuitName Circuit name (e.g., "broker-health")
     * @param containerName Container name (e.g., "brokers")
     * @return HealthThresholds with container-specific values (or circuit/global fallback)
     */
    public static HealthThresholds forContainer(String circuitName, String containerName) {
        HierarchicalConfig config = HierarchicalConfig.forContainer(circuitName, containerName);
        return fromConfig(config);
    }

    /**
     * Creates HealthThresholds with production-ready defaults.
     * <p>
     * Default values:
     * <ul>
     *   <li>Heap: STABLE at 75%, DEGRADED at 90%</li>
     *   <li>CPU: STABLE at 70%, DEGRADED at 85%</li>
     * </ul>
     * <p>
     * These defaults are based on:
     * - Heap: JVM GC typically struggles above 75% sustained usage, critical above 90%
     * - CPU: System performance degrades above 70% CPU, critical above 85%
     *
     * <p><strong>Note:</strong> Prefer using {@link #fromConfig(HierarchicalConfig)} to leverage
     * multi-layer configuration overrides.
     *
     * @return HealthThresholds with production-ready defaults
     */
    public static HealthThresholds withDefaults() {
        return new HealthThresholds(
            0.75,  // heapStable - 75% heap usage
            0.90,  // heapDegraded - 90% heap usage
            0.70,  // cpuStable - 70% CPU usage
            0.85   // cpuDegraded - 85% CPU usage
        );
    }
}
