package io.fullerstack.kafka.demo.sidecar;

import io.fullerstack.kafka.producer.sidecar.CentralCommunicator.InformMessage;
import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Sends periodic heartbeat messages to central platform for sidecar registration persistence.
 * <p>
 * **Purpose:**
 * - Keep sidecar registered in central's SidecarRegistry (updates lastSeenMs)
 * - Enable central platform restart recovery (sidecars re-register from buffered heartbeats)
 * - Provide liveness signal for health monitoring
 * <p>
 * **Production Pattern:**
 * - Same as Kafka Connect worker heartbeats
 * - Same as Kafka Streams instance heartbeats
 * - Kafka producer handles retries if central is down
 * - Messages buffered in Kafka if central is restarting
 * <p>
 * **Resilience:**
 * - Heartbeat interval: 10 seconds (keeps sidecar ACTIVE in 30s timeout window)
 * - Kafka producer auto-retries on failure (no data loss)
 * - Virtual thread (lightweight, non-blocking)
 *
 * @since 1.0.0
 */
public class SidecarHeartbeatSender implements Runnable, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SidecarHeartbeatSender.class);
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;  // 10 seconds

    private final String sidecarId;
    private final String sidecarType;
    private final String jmxEndpoint;
    private final KafkaCentralCommunicator communicator;
    private volatile boolean running = true;

    /**
     * Creates a new heartbeat sender with explicit metadata.
     *
     * @param sidecarId sidecar identifier (e.g., "producer-sidecar-1")
     * @param sidecarType sidecar type (e.g., "producer", "consumer")
     * @param jmxEndpoint JMX endpoint for monitoring (e.g., "localhost:11001")
     * @param communicator Kafka communicator for sending messages to central
     */
    public SidecarHeartbeatSender(
        String sidecarId,
        String sidecarType,
        String jmxEndpoint,
        KafkaCentralCommunicator communicator
    ) {
        this.sidecarId = sidecarId;
        this.sidecarType = sidecarType;
        this.jmxEndpoint = jmxEndpoint;
        this.communicator = communicator;
    }

    @Override
    public void run() {
        logger.info("💓 Heartbeat sender started for sidecar: {} (interval: {}ms)", sidecarId, HEARTBEAT_INTERVAL_MS);

        while (running) {
            try {
                // Send heartbeat REPORT message
                sendHeartbeat();

                logger.trace("💓 Heartbeat sent from sidecar: {}", sidecarId);

                // Sleep until next heartbeat
                Thread.sleep(HEARTBEAT_INTERVAL_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Heartbeat sender interrupted for sidecar: {}", sidecarId);
                break;

            } catch (Exception e) {
                logger.warn("Failed to send heartbeat from sidecar {}: {}", sidecarId, e.getMessage());
                // Continue - Kafka producer will retry
                // Don't break on temporary failures (network hiccups, Kafka unavailable, etc.)
            }
        }

        logger.info("Heartbeat sender stopped for sidecar: {}", sidecarId);
    }

    /**
     * Sends a heartbeat REPORT message to central platform with explicit metadata.
     * <p>
     * Message format:
     * <pre>
     * {
     *   "speechAct": "REPORT",
     *   "source": "producer-sidecar-1",
     *   "contextAgent": "producer-sidecar-1.heartbeat",
     *   "information": "HEARTBEAT",
     *   "timestamp": 1700000000000,
     *   "metadata": {
     *     "type": "producer",
     *     "jmxEndpoint": "localhost:11001",
     *     "hostname": "kafka-producer-pod-1"  // Optional, from system
     *   }
     * }
     * </pre>
     */
    private void sendHeartbeat() {
        // Build metadata with explicit sidecar information
        Map<String, Object> metadata = Map.of(
            "type", sidecarType,
            "jmxEndpoint", jmxEndpoint,
            "hostname", getHostname()
        );

        InformMessage heartbeat = new InformMessage(
            sidecarId,                      // sourceAgent
            sidecarId + ".heartbeat",       // contextAgent
            "HEARTBEAT",                    // information
            System.currentTimeMillis(),     // timestamp
            metadata                        // ← Explicit metadata (NO inference needed!)
        );

        communicator.sendInform(heartbeat);
    }

    /**
     * Get system hostname for metadata.
     */
    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Stops the heartbeat sender gracefully.
     */
    public void stop() {
        logger.info("Stopping heartbeat sender for sidecar: {}", sidecarId);
        running = false;
    }

    @Override
    public void close() {
        stop();
    }
}
