/**
 * Cell hierarchy infrastructure for Kafka topology Sign aggregation.
 *
 * <p>4-level hierarchy: Cluster → Broker → Topic → Partition
 * <p>All cells use Cell&lt;Monitors.Sign, Monitors.Sign&gt; (uniform typing)
 * <p>Uses identity composers (Composer.pipe()) for passthrough transformation
 * <p>Child emissions flow upward automatically to parent outlets
 *
 * @see HierarchyManager
 */
package io.fullerstack.kafka.core.hierarchy;
