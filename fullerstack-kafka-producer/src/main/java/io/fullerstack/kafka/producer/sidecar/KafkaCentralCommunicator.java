package io.fullerstack.kafka.producer.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

/**
 * Kafka-based implementation of CentralCommunicator.
 * <p>
 * Sends speech act messages to central platform via Kafka topic.
 * Messages are serialized to JSON for platform-agnostic communication.
 *
 * @since 1.0.0
 */
public class KafkaCentralCommunicator implements CentralCommunicator, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaCentralCommunicator.class);

    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Kafka-based central communicator.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param topic topic name for speech acts
     */
    public KafkaCentralCommunicator(String bootstrapServers, String topic) {
        this.topic = topic;
        this.objectMapper = new ObjectMapper();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");
        props.put("retries", 3);
        props.put("max.in.flight.requests.per.connection", 1);

        this.producer = new KafkaProducer<>(props);
        logger.info("KafkaCentralCommunicator initialized: topic={}, bootstrap={}", topic, bootstrapServers);
    }

    @Override
    public void sendDirective(DirectiveMessage message) {
        try {
            Map<String, Object> payload = Map.of(
                "speechAct", "REQUEST",
                "source", message.sourceAgent(),
                "contextAgent", message.contextAgent(),
                "requestType", message.requestType(),
                "description", message.description(),
                "suggestedActions", message.suggestedActions(),
                "urgency", message.urgency(),
                "timestamp", message.timestamp()
            );

            String json = objectMapper.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(topic, message.sourceAgent(), json));

            logger.info("[KAFKA] Sent REQUEST: {} - {}", message.requestType(), message.description());
        } catch (Exception e) {
            logger.error("Failed to send directive", e);
        }
    }

    @Override
    public void sendInform(InformMessage message) {
        try {
            // Build payload with optional metadata
            Map<String, Object> payload;
            if (message.metadata() != null && !message.metadata().isEmpty()) {
                payload = Map.of(
                    "speechAct", "REPORT",
                    "source", message.sourceAgent(),
                    "contextAgent", message.contextAgent(),
                    "information", message.information(),
                    "timestamp", message.timestamp(),
                    "metadata", message.metadata()  // ← Include metadata if present
                );
            } else {
                // Backward compatible - no metadata
                payload = Map.of(
                    "speechAct", "REPORT",
                    "source", message.sourceAgent(),
                    "contextAgent", message.contextAgent(),
                    "information", message.information(),
                    "timestamp", message.timestamp()
                );
            }

            String json = objectMapper.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(topic, message.sourceAgent(), json));

            logger.info("[KAFKA] Sent REPORT: {}", message.information());
        } catch (Exception e) {
            logger.error("Failed to send inform", e);
        }
    }

    @Override
    public void close() {
        if (producer != null) {
            logger.info("Closing KafkaCentralCommunicator");
            producer.close();
        }
    }
}
