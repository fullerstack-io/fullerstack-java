package io.fullerstack.kafka.producer.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Listens for speech act responses from central platform.
 * <p>
 * Receives responses to sidecar requests and updates local Actors conduit with
 * the central platform's speech acts (ACKNOWLEDGE, PROMISE, DELIVER, DENY, etc.)
 *
 * @since 1.0.0
 */
public class SidecarResponseListener implements Runnable, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SidecarResponseListener.class);

    private final KafkaConsumer<String, String> consumer;
    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final String sidecarId;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    /**
     * Creates a new sidecar response listener.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param responseTopic topic to consume responses from
     * @param actors local actor conduit (for emitting central responses)
     * @param sidecarId this sidecar's identifier
     */
    public SidecarResponseListener(
        String bootstrapServers,
        String responseTopic,
        Conduit<Actors.Actor, Actors.Sign> actors,
        String sidecarId
    ) {
        this.actors = actors;
        this.sidecarId = sidecarId;
        this.objectMapper = new ObjectMapper();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "sidecar-" + sidecarId);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", "latest");
        props.put("enable.auto.commit", "true");

        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(responseTopic));

        logger.info("SidecarResponseListener initialized: sidecar={}, topic={}", sidecarId, responseTopic);
    }

    @Override
    public void run() {
        logger.info("SidecarResponseListener started for sidecar: {}", sidecarId);

        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    // Filter to only process messages for this sidecar
                    if (sidecarId.equals(record.key())) {
                        processResponse(record);
                    }
                }
            } catch (java.lang.Exception e) {
                if (running) {
                    logger.error("Error processing responses", e);
                }
            }
        }

        logger.info("SidecarResponseListener stopped for sidecar: {}", sidecarId);
    }

    private void processResponse(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> message = objectMapper.readValue(record.value(), Map.class);
            String speechAct = (String) message.get("speechAct");
            String source = (String) message.get("source");
            String content = (String) message.get("content");

            logger.info("[RESPONSE] From {}: {} - {}", source, speechAct, content);

            // Get or create actor for this conversation
            Actors.Actor centralActor = actors.percept(cortex().name("central." + source));

            // Emit the speech act locally so AgentCoordinationBridge can react
            switch (speechAct) {
                case "ACKNOWLEDGE" -> {
                    centralActor.acknowledge();
                    logger.info("[RESPONSE] Central ACKNOWLEDGED: {}", content);
                }
                case "PROMISE" -> {
                    centralActor.promise();
                    logger.info("[RESPONSE] Central PROMISED: {}", content);
                }
                case "DELIVER" -> {
                    centralActor.deliver();
                    logger.info("[RESPONSE] Central DELIVERED: {}", content);
                }
                case "DENY" -> {
                    centralActor.deny();
                    logger.warn("[RESPONSE] Central DENIED: {}", content);
                }
                case "EXPLAIN" -> {
                    centralActor.explain();
                    logger.info("[RESPONSE] Central EXPLAINED: {}", content);
                }
                case "REPORT" -> {
                    centralActor.report();
                    logger.info("[RESPONSE] Central REPORTED: {}", content);
                }
                default -> logger.warn("Unknown speech act from central: {}", speechAct);
            }
        } catch (java.lang.Exception e) {
            logger.error("Failed to process response from record: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset(), e);
        }
    }

    /**
     * Stops the listener gracefully.
     */
    public void stop() {
        logger.info("Stopping SidecarResponseListener for sidecar: {}", sidecarId);
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
