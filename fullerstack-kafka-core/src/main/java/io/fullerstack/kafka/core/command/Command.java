package io.fullerstack.kafka.core.command;

/**
 * Control commands that flow downward through the Cell hierarchy.
 *
 * <p>Commands enable top-down coordination and control, complementing
 * the bottom-up observability signals (Monitors.Sign).
 *
 * <p><b>Bidirectional Flow Pattern:</b>
 * <ul>
 *   <li><b>Upward (Child → Parent)</b>: Monitors.Sign (DEGRADED, STABLE, etc.) - Observability</li>
 *   <li><b>Downward (Parent → Child)</b>: Command (THROTTLE, SHUTDOWN, etc.) - Control</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li><b>THROTTLE</b>: Cluster overload → throttle all producers</li>
 *   <li><b>CIRCUIT_OPEN</b>: Broker failure → circuit break all topics</li>
 *   <li><b>MAINTENANCE</b>: Planned maintenance → read-only mode</li>
 *   <li><b>SHUTDOWN</b>: Emergency shutdown → coordinated stop</li>
 *   <li><b>RESUME</b>: Recovery complete → restore normal operations</li>
 * </ul>
 *
 * <p><b>Example - Adaptive Throttling:</b>
 * <pre>{@code
 * // Cluster health degrades
 * clusterCell.emit(Monitors.Sign.DEGRADED);  // Upward: signal health issue
 *
 * // Actor decides to throttle
 * clusterCell.command(Command.THROTTLE);     // Downward: cascade to all partitions
 *
 * // All partitions receive THROTTLE and apply backpressure
 * }</pre>
 */
public enum Command {
    /**
     * Throttle operations to reduce load.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Cluster/broker resource exhaustion (CPU, memory, disk)</li>
     *   <li>Queue overflow conditions</li>
     *   <li>Network saturation</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Reduce producer throughput</li>
     *   <li>Apply backpressure</li>
     *   <li>Increase batch delays</li>
     * </ul>
     */
    THROTTLE,

    /**
     * Open circuit breaker to prevent cascading failures.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Broker/partition becomes unavailable</li>
     *   <li>Excessive error rates detected</li>
     *   <li>Dependency failures</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Reject new requests immediately</li>
     *   <li>Return fast-fail responses</li>
     *   <li>Prevent timeout accumulation</li>
     * </ul>
     */
    CIRCUIT_OPEN,

    /**
     * Close circuit breaker to resume normal operations.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Health checks passing</li>
     *   <li>Recovery period elapsed</li>
     *   <li>Manual intervention</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Allow requests through</li>
     *   <li>Monitor for stability</li>
     *   <li>Gradual ramp-up</li>
     * </ul>
     */
    CIRCUIT_CLOSE,

    /**
     * Enter maintenance mode (read-only).
     *
     * <p>Triggers:
     * <ul>
     *   <li>Planned maintenance window</li>
     *   <li>Configuration updates</li>
     *   <li>Migration operations</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Accept reads only</li>
     *   <li>Reject write operations</li>
     *   <li>Flush pending writes</li>
     * </ul>
     */
    MAINTENANCE,

    /**
     * Resume normal operations after maintenance.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Maintenance complete</li>
     *   <li>Configuration validated</li>
     *   <li>Health checks passing</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Accept all operations</li>
     *   <li>Resume write traffic</li>
     *   <li>Return to normal mode</li>
     * </ul>
     */
    RESUME,

    /**
     * Emergency shutdown - stop all operations.
     *
     * <p>Triggers:
     * <ul>
     *   <li>Critical security issue</li>
     *   <li>Data corruption detected</li>
     *   <li>Catastrophic failure</li>
     * </ul>
     *
     * <p>Actions:
     * <ul>
     *   <li>Stop accepting all traffic</li>
     *   <li>Flush critical data</li>
     *   <li>Coordinated shutdown</li>
     * </ul>
     */
    SHUTDOWN
}
