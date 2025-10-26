package io.fullerstack.substrates.spi;

import io.humainary.substrates.api.Substrates.Composer;

/**
 * Service Provider Interface for applications to provide composers for circuits.
 * <p>
 * Applications implement this interface to register their typed composers with the framework.
 * The framework discovers implementations via {@link java.util.ServiceLoader}.
 * <p>
 * <strong>Contract:</strong>
 * <ul>
 *   <li>Framework calls {@link #getComposer(String, String, ComponentType)} for each component</li>
 *   <li>Application returns typed composer (e.g., {@code Composer<BrokerMetrics, MonitorSignal>})</li>
 *   <li>Framework uses type-erased composer ({@code Composer<?, ?>}) for infrastructure</li>
 *   <li>Return {@code null} to use framework default (Composer.pipe())</li>
 * </ul>
 * <p>
 * <strong>Example Implementation:</strong>
 * <pre>
 * public class MyComposerProvider implements ComposerProvider {
 *     &#64;Override
 *     public Composer&lt;?, ?&gt; getComposer(String circuitName, String componentName, ComponentType type) {
 *         if (circuitName.equals("broker-health") &amp;&amp; componentName.equals("cluster-health")) {
 *             return new BrokerHealthCellComposer();  // Typed composer
 *         }
 *         return null;  // Use default
 *     }
 *
 *     &#64;Override
 *     public Class&lt;?&gt; getSignalType(String circuitName, String componentName) {
 *         return MonitorSignal.class;
 *     }
 * }
 * </pre>
 * <p>
 * <strong>Registration:</strong>
 * Create file: {@code META-INF/services/io.fullerstack.substrates.spi.ComposerProvider}
 * <pre>
 * com.example.MyComposerProvider
 * </pre>
 *
 * @see java.util.ServiceLoader
 * @see io.humainary.substrates.api.Substrates.Composer
 */
public interface ComposerProvider {

    /**
     * Get composer for a specific circuit component.
     * <p>
     * Called by the framework during circuit bootstrap to obtain composers
     * for containers, conduits, and cells.
     *
     * @param circuitName Circuit name (e.g., "broker-health", "partition-flow")
     * @param componentName Component name (e.g., "cluster-health", "brokers", "metrics")
     * @param type Component type (CELL, CONTAINER, or CONDUIT)
     * @return Composer instance, or null to use default (Composer.pipe())
     */
    Composer<?, ?> getComposer(String circuitName, String componentName, ComponentType type);

    /**
     * Get signal type for a circuit component.
     * <p>
     * Used for type introspection and validation. Optional - return null if not applicable.
     *
     * @param circuitName Circuit name
     * @param componentName Component name
     * @return Signal type class (e.g., MonitorSignal.class), or null if not applicable
     */
    default Class<?> getSignalType(String circuitName, String componentName) {
        return null;
    }

    /**
     * Component type enumeration.
     * <p>
     * Identifies the type of component being created:
     * <ul>
     *   <li><strong>CELL:</strong> Hierarchical signal aggregator (root or child)</li>
     *   <li><strong>CONTAINER:</strong> Dynamic collection of conduits (e.g., brokers, partitions)</li>
     *   <li><strong>CONDUIT:</strong> Signal pathway within a circuit</li>
     * </ul>
     */
    enum ComponentType {
        /**
         * Cell component - hierarchical signal aggregator.
         * <p>
         * Cells compose child signals into parent signals (e.g., broker metrics → cluster health).
         */
        CELL,

        /**
         * Container component - dynamic collection of conduits.
         * <p>
         * Containers manage multiple conduits that come and go (e.g., broker instances).
         */
        CONTAINER,

        /**
         * Conduit component - signal pathway.
         * <p>
         * Conduits route signals from producers to subscribers.
         */
        CONDUIT
    }
}
