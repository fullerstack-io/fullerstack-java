package io.fullerstack.kafka.core.command;

/**
 * Handler for commands received at a specific level in the hierarchy.
 *
 * <p>CommandHandlers are registered at each level (cluster, broker, topic, partition)
 * to respond to commands flowing downward through the Cell hierarchy.
 *
 * <p><b>Command Flow:</b>
 * <pre>
 * Actor issues Command.THROTTLE at cluster level
 *   ↓
 * Cluster CommandHandler receives THROTTLE
 *   ↓ Broadcasts to broker cells
 * Broker CommandHandlers receive THROTTLE
 *   ↓ Broadcast to topic cells
 * Topic CommandHandlers receive THROTTLE
 *   ↓ Broadcast to partition cells
 * Partition CommandHandlers receive THROTTLE
 *   ↓ Execute physical action (apply backpressure)
 * </pre>
 *
 * <p><b>Example - Partition-Level Handler:</b>
 * <pre>{@code
 * class PartitionCommandHandler implements CommandHandler {
 *     private final ProducerThrottler throttler;
 *
 *     @Override
 *     public void handle(Command command) {
 *         switch (command) {
 *             case THROTTLE -> throttler.enable();
 *             case RESUME -> throttler.disable();
 *             case CIRCUIT_OPEN -> circuitBreaker.open();
 *             case SHUTDOWN -> partition.stop();
 *         }
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface CommandHandler {
    /**
     * Handles a command received from the parent level.
     *
     * @param command the command to handle
     */
    void handle(Command command);
}
