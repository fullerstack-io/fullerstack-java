/**
 * Domain models for Kafka broker monitoring.
 * <p>
 * This package contains the core domain models representing operational state
 * and metrics collected from Kafka brokers. These models form the input layer
 * (Layer 2: Serventis) before transformation into semantic monitoring signals.
 * <p>
 * <b>Key Classes:</b>
 * <ul>
 *   <li>{@link io.kafkaobs.broker.models.BrokerMetrics} - Immutable record containing
 *       13 JMX metrics representing broker operational health</li>
 * </ul>
 * <p>
 * <b>BrokerMetrics Fields:</b>
 * <pre>
 * Memory:
 *   - heapUsed (bytes)          - Current heap memory usage
 *   - heapMax (bytes)            - Maximum heap memory available
 *
 * CPU:
 *   - cpuUsage (0.0-1.0)        - Process CPU load percentage
 *
 * Throughput:
 *   - requestRate (req/sec)      - Messages in per second
 *   - byteInRate (bytes/sec)     - Incoming byte rate
 *   - byteOutRate (bytes/sec)    - Outgoing byte rate
 *
 * Controller:
 *   - activeControllers (0 or 1) - Controller status for this broker
 *
 * Replication:
 *   - underReplicatedPartitions  - Partitions below target replication
 *   - offlinePartitionsCount     - Partitions with no leader
 *
 * Thread Pools:
 *   - networkProcessorAvgIdlePercent (0-100) - Network thread idle %
 *   - requestHandlerAvgIdlePercent (0-100)   - Request handler idle %
 *
 * Latency:
 *   - fetchConsumerTotalTimeMs   - Fetch request latency
 *   - produceTotalTimeMs         - Produce request latency
 *
 * Metadata:
 *   - brokerId                   - Broker identifier (e.g., "b-1", "localhost")
 *   - timestamp                  - Collection timestamp (milliseconds)
 * </pre>
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 *   <li><b>Immutability</b>: All fields are final, record provides value semantics</li>
 *   <li><b>Validation</b>: Constructor validates non-null brokerId and reasonable ranges</li>
 *   <li><b>Type Safety</b>: Uses specific types (long, double, int) for metrics</li>
 *   <li><b>Zero Dependencies</b>: Pure domain model with no framework dependencies</li>
 * </ul>
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * BrokerMetrics metrics = new BrokerMetrics(
 *     "b-1",                      // brokerId
 *     500_000_000L,               // heapUsed (500MB)
 *     1_000_000_000L,             // heapMax (1GB)
 *     0.25,                       // cpuUsage (25%)
 *     1000L,                      // requestRate
 *     5_000_000L,                 // byteInRate
 *     5_000_000L,                 // byteOutRate
 *     1,                          // activeControllers
 *     0,                          // underReplicatedPartitions
 *     0,                          // offlinePartitionsCount
 *     95L,                        // networkProcessorAvgIdlePercent
 *     90L,                        // requestHandlerAvgIdlePercent
 *     10L,                        // fetchConsumerTotalTimeMs
 *     5L,                         // produceTotalTimeMs
 *     System.currentTimeMillis()  // timestamp
 * );
 *
 * // Record provides equals, hashCode, toString
 * System.out.println(metrics);
 * }</pre>
 * <p>
 * <b>Validation Rules:</b>
 * <ul>
 *   <li>brokerId: Must not be null or blank</li>
 *   <li>heapUsed: Must be &gt;= 0</li>
 *   <li>heapMax: Must be &gt; 0</li>
 *   <li>cpuUsage: Must be in range [0.0, 1.0]</li>
 *   <li>timestamp: Must be &gt; 0</li>
 * </ul>
 *
 * @see io.kafkaobs.broker.sensors.JmxMetricsCollector
 * @see io.kafkaobs.broker.composers.BrokerHealthCellComposer
 */
package io.fullerstack.kafka.broker.models;
