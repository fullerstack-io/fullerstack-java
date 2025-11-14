/**
 * Bridge pattern implementation for Monitor conduit to Cell hierarchy.
 *
 * <p>This package provides the bridge between Layer 2 (ORIENT) Monitor conduits
 * and the Cell hierarchy used for aggregation and Layer 3 (DECIDE) reporting.
 *
 * <h2>Architecture Overview</h2>
 *
 * <p>The bridge pattern decouples Monitor emissions from Cell hierarchy:
 * <pre>
 * Layer 2 (ORIENT): 103 Monitor classes → Monitor conduits
 *                                              ↓
 *                                       MonitorCellBridge
 *                                              ↓ (parses names using Name API, routes Signs)
 *                                       Cell Hierarchy
 *                                              ↓
 * Layer 3 (DECIDE): Reporters subscribe to Cells
 * </pre>
 *
 * <h2>Key Components</h2>
 *
 * <dl>
 *   <dt>{@link io.fullerstack.kafka.core.bridge.MonitorCellBridge}</dt>
 *   <dd>Subscribes to Monitor conduit, forwards Monitors.Sign to appropriate cells using
 *   Name.depth() and Name.iterator() to parse entity names</dd>
 * </dl>
 *
 * <h2>Name API Parsing</h2>
 *
 * <p>The bridge uses Substrates Name API to parse entity names:
 * <ul>
 *   <li><b>Name.depth()</b> - determines hierarchy level (1=broker/cluster, 2=topic, 3=partition)</li>
 *   <li><b>Name.iterator()</b> - extracts components in REVERSE order (deepest → root)</li>
 *   <li><b>Name.value()</b> - gets the string value of each component</li>
 * </ul>
 *
 * <h2>Signal Flow Example</h2>
 *
 * <pre>{@code
 * // 1. Monitor emits Signal to conduit
 * Monitors.Monitor monitor = monitors.percept(cortex().name("broker-1.orders.p0"));
 * monitor.degraded(Monitors.Dimension.CONFIRMED);
 * // → Signal{ sign=DEGRADED, dimension=CONFIRMED } emitted to conduit
 *
 * // 2. Bridge subscribes to conduit, receives Signal
 * bridge.handleMonitorSignal(subject, registrar);
 * // → Parses "broker-1.orders.p0" using Name.depth() = 3
 * // → Uses Name.iterator() to extract: ["p0", "orders", "broker-1"] (reverse order)
 *
 * // 3. Bridge forwards Sign to partition cell
 * Cell<Monitors.Sign, Monitors.Sign> partitionCell =
 *     hierarchy.getPartitionCell("broker-1", "orders", "p0");
 * partitionCell.emit(Monitors.Sign.DEGRADED);
 *
 * // 4. Sign flows upward through hierarchy
 * // Partition → Topic → Broker → Cluster
 *
 * // 5. Reporters subscribe to cells
 * clusterCell.subscribe(subscriber); // Receives aggregated DEGRADED sign
 * }</pre>
 *
 * <h2>Entity Name Formats</h2>
 *
 * <p>The bridge recognizes these entity name patterns:
 * <ul>
 *   <li><b>Partition:</b> "broker-1.orders.p0" (depth=3, components=["p0", "orders", "broker-1"])</li>
 *   <li><b>Topic:</b> "broker-1.orders" (depth=2, components=["orders", "broker-1"])</li>
 *   <li><b>Broker:</b> "broker-1" (depth=1, components=["broker-1"])</li>
 *   <li><b>Cluster:</b> "cluster" (depth=1, special keyword)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Setup Monitor conduit (Layer 2)
 * Circuit monitorCircuit = cortex().circuit(cortex().name("monitors"));
 * Conduit<Monitors.Monitor, Monitors.Signal> monitors =
 *     monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);
 *
 * // Setup Cell hierarchy
 * HierarchyManager hierarchy = new HierarchyManager("prod-cluster");
 *
 * // Create and start bridge
 * MonitorCellBridge bridge = new MonitorCellBridge(monitors, hierarchy);
 * bridge.start();
 *
 * // Now all Monitor signals automatically flow to Cell hierarchy
 * // Reporters can subscribe to cells for aggregated health
 *
 * // Cleanup
 * bridge.close();
 * hierarchy.close();
 * monitorCircuit.close();
 * }</pre>
 *
 * @since 1.0.0
 * @see io.fullerstack.kafka.core.hierarchy
 */
package io.fullerstack.kafka.core.bridge;
