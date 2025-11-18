package io.fullerstack.kafka.coordination.central;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * PRODUCTION: Listens for speech acts from sidecars via Kafka.
 * <p>
 * Receives REQUEST and REPORT speech acts, deserializes from JSON,
 * and routes to RequestHandler for processing.
 * <p>
 * **Auto-Discovery Integration:**
 * - Automatically registers sidecars when messages received
 * - Updates registry with each message (heartbeat tracking)
 * - Reads from topic start (earliest) to capture buffered registrations
 *
 * @since 1.0.0
 */
public class SpeechActListener implements Runnable, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SpeechActListener.class);

    private final KafkaConsumer<String, String> consumer;
    private final RequestHandler requestHandler;
    private final SidecarRegistry registry;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    /**
     * Creates a new speech act listener with auto-discovery.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param topic topic to consume speech acts from
     * @param requestHandler handler for processing requests
     * @param registry sidecar registry for auto-discovery
     */
    public SpeechActListener(
        String bootstrapServers,
        String topic,
        RequestHandler requestHandler,
        SidecarRegistry registry
    ) {
        this.requestHandler = requestHandler;
        this.registry = registry;
        this.objectMapper = new ObjectMapper();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "central-platform");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", "earliest");  // ✅ RESILIENCE FIX: Read buffered messages
        props.put("enable.auto.commit", "true");

        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));

        logger.info("SpeechActListener initialized: topic={}, bootstrap={}", topic, bootstrapServers);
        logger.info("Auto-discovery enabled with offset strategy: earliest");
    }

    @Override
    public void run() {
        logger.info("SpeechActListener started - waiting for speech acts...");

        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    processSpeechAct(record);
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("Error processing speech acts", e);
                }
            }
        }

        logger.info("SpeechActListener stopped");
    }

    private void processSpeechAct(ConsumerRecord<String, String> record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = objectMapper.readValue(record.value(), Map.class);
            String speechAct = (String) message.get("speechAct");
            String source = (String) message.get("source");

            logger.info("[RECEIVED] Speech act: {} from {} (key={})", speechAct, source, record.key());
            logger.debug("[RECEIVED] Full message: {}", message);

            // ✅ AUTO-DISCOVER SIDECAR (production feature)
            if (source != null) {
                String type = detectSidecarType(source);
                String jmxEndpoint = extractJmxEndpoint(message, source);
                registry.registerSidecar(source, type, jmxEndpoint);
            }

            // Process speech act (existing logic)
            switch (speechAct) {
                case "REQUEST" -> requestHandler.handleRequest(message);
                case "REPORT" -> requestHandler.handleReport(message);
                default -> logger.warn("Unknown speech act: {} from {}", speechAct, source);
            }
        } catch (Exception e) {
            logger.error("Failed to process speech act from record: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset(), e);
        }
    }

    /**
     * Detect sidecar type from sidecar ID.
     * <p>
     * Convention:
     * - "producer-*" → type = "producer"
     * - "consumer-*" → type = "consumer"
     * - Other → type = "unknown"
     */
    private String detectSidecarType(String sidecarId) {
        if (sidecarId.startsWith("producer-")) {
            return "producer";
        } else if (sidecarId.startsWith("consumer-")) {
            return "consumer";
        } else {
            return "unknown";
        }
    }

    /**
     * Extract JMX endpoint from message metadata or infer from sidecar ID.
     * <p>
     * Priority:
     * 1. Explicit "jmxEndpoint" field in message
     * 2. Infer from sidecar ID convention (producer-1 → localhost:11001)
     */
    private String extractJmxEndpoint(Map<String, Object> message, String sidecarId) {
        // Check for explicit JMX endpoint in message
        Object explicitJmx = message.get("jmxEndpoint");
        if (explicitJmx != null) {
            return explicitJmx.toString();
        }

        // Infer from sidecar ID using convention
        return inferJmxEndpoint(sidecarId);
    }

    /**
     * Infer JMX endpoint from sidecar ID using convention.
     * <p>
     * Supports formats:
     * - "producer-1" → localhost:11001
     * - "producer-sidecar-1" → localhost:11001
     * - "consumer-2" → localhost:11102
     * - "consumer-sidecar-2" → localhost:11102
     */
    private String inferJmxEndpoint(String sidecarId) {
        try {
            String type = detectSidecarType(sidecarId);

            // Extract numeric suffix - try last part after splitting on "-"
            String[] parts = sidecarId.split("-");
            if (parts.length >= 2) {
                // Try to parse the LAST part as a number (handles both "producer-1" and "producer-sidecar-1")
                String lastPart = parts[parts.length - 1];
                int number = Integer.parseInt(lastPart);

                int basePort = type.equals("producer") ? 11000 : 11100;
                int port = basePort + number;

                return "localhost:" + port;
            }
        } catch (Exception e) {
            logger.warn("Failed to infer JMX endpoint for {}: {}", sidecarId, e.getMessage());
        }

        // Fallback to default
        return "localhost:11001";
    }

    /**
     * Stops the listener gracefully.
     */
    public void stop() {
        logger.info("Stopping SpeechActListener...");
        running = false;
    }

    @Override
    public void close() {
        stop();
        if (consumer != null) {
            consumer.close();
        }
    }
}
