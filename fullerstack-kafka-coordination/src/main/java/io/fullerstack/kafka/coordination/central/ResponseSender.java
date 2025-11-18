package io.fullerstack.kafka.coordination.central;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

/**
 * Sends speech act responses from central platform back to sidecars.
 * <p>
 * Serializes responses to JSON and publishes to Kafka response topic.
 * Sidecars subscribe to this topic to receive central platform responses.
 *
 * @since 1.0.0
 */
public class ResponseSender implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ResponseSender.class);

    private final KafkaProducer<String, String> producer;
    private final String responseTopic;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new response sender.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param responseTopic topic for sending responses
     */
    public ResponseSender(String bootstrapServers, String responseTopic) {
        this.responseTopic = responseTopic;
        this.objectMapper = new ObjectMapper();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");
        props.put("retries", 3);
        props.put("max.in.flight.requests.per.connection", 1);

        this.producer = new KafkaProducer<>(props);
        logger.info("ResponseSender initialized: topic={}, bootstrap={}", responseTopic, bootstrapServers);
    }

    /**
     * Sends a speech act response to a specific sidecar.
     *
     * @param targetSidecar the sidecar agent ID to send response to
     * @param speechAct the speech act type (ACKNOWLEDGE, PROMISE, DELIVER, DENY, etc.)
     * @param content the message content
     */
    public void send(String targetSidecar, String speechAct, String content) {
        try {
            Map<String, Object> message = Map.of(
                "speechAct", speechAct,
                "source", "central",
                "target", targetSidecar,
                "content", content,
                "timestamp", System.currentTimeMillis()
            );

            String json = objectMapper.writeValueAsString(message);
            producer.send(new ProducerRecord<>(responseTopic, targetSidecar, json));

            logger.info("[SENT] {} to {}: {}", speechAct, targetSidecar, content);
        } catch (Exception e) {
            logger.error("Failed to send response to {}", targetSidecar, e);
        }
    }

    @Override
    public void close() {
        if (producer != null) {
            logger.info("Closing ResponseSender");
            producer.close();
        }
    }
}
