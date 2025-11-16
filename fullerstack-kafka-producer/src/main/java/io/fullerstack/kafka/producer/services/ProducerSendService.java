package io.fullerstack.kafka.producer.services;

import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.ext.serventis.ext.Services.Service;
import io.humainary.substrates.ext.serventis.ext.Services.Dimension;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Services API for producer send() operations (self-monitoring).
 * <p>
 * <b>Critical Insight from Humainary Article:</b>
 * Services API is for <b>SELF-MONITORING internal operations</b>, not monitoring external services.
 * <p>
 * <b>Wrong Usage:</b> Monitoring external Kafka brokers
 * <pre>{@code
 * // ❌ WRONG: Monitoring external service
 * Service kafkaBroker = services.channel(cortex().name("broker-1"));
 * kafkaBroker.call();  // NO! This monitors the broker, not our operation
 * }</pre>
 *
 * <b>Correct Usage:</b> Kafka client operations monitoring themselves
 * <pre>{@code
 * // ✅ CORRECT: Self-monitoring our send() operation
 * Service sendOperation = services.channel(cortex().name("producer.send"));
 * sendOperation.call();      // "I am calling send()"
 * producer.send(...);
 * sendOperation.succeeded(); // "I succeeded"
 * }</pre>
 *
 * <h3>Services API Pattern (CALLER/CALLEE dimensions):</h3>
 * <ul>
 *   <li><b>call(CALLER)</b> - "I am initiating this operation" (caller perspective)</li>
 *   <li><b>success(CALLER)</b> - "My operation succeeded"</li>
 *   <li><b>fail(CALLER)</b> - "My operation failed"</li>
 *   <li><b>retry(CALLER)</b> - "I am retrying the operation"</li>
 * </ul>
 *
 * <h3>Why Services API Matters:</h3>
 * The Service can <b>SUBSCRIBE to its OWN health Monitor</b> for closed-loop feedback!
 * <pre>
 * Layer 1: ProducerSendService → Services (call, succeeded, failed)
 *                                    ↓
 * Layer 2: SendHealthMonitor → Monitors (assess send success rate)
 *                                    ↓
 * Layer 4a: ProducerSelfRegulator → Agent throttles if failure rate high
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("producer.health"));
 *
 * ProducerSendService<String, String> sendService = new ProducerSendService<>(
 *     producer,
 *     circuit
 * );
 *
 * // Send with self-monitoring
 * sendService.send(
 *     new ProducerRecord<>("topic", "key", "value"),
 *     (metadata, exception) -> {
 *         if (exception == null) {
 *             System.out.println("Sent to partition: " + metadata.partition());
 *         }
 *     }
 * );
 *
 * // Services signals emitted:
 * // 1. call() - "I am calling send()"
 * // 2. succeeded() or failed() - based on callback result
 *
 * // ... later ...
 * sendService.close();
 * }</pre>
 *
 * @param <K> producer key type
 * @param <V> producer value type
 * @see io.humainary.substrates.ext.serventis.ext.services.Services
 * @see Service
 */
public class ProducerSendService<K, V> implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerSendService.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final KafkaProducer<K, V> producer;
    private final Conduit<Service, Services.Signal> services;
    private final Service sendService;

    /**
     * Creates a new producer send service with self-monitoring.
     *
     * @param producer Kafka producer to wrap
     * @param circuit parent circuit for services conduit
     */
    public ProducerSendService(
        KafkaProducer<K, V> producer,
        Circuit circuit
    ) {
        this.cortex = cortex();
        this.producer = Objects.requireNonNull(producer, "producer cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create Services conduit using Services.composer
        this.services = circuit.conduit(
            cortex.name("services"),
            Services::composer
        );

        // Get Service instrument for send() operation
        this.sendService = services.percept(cortex.name("producer.send"));

        logger.info("ProducerSendService created - self-monitoring enabled for send() operations");
    }

    /**
     * Send record with Service self-monitoring.
     * <p>
     * Pattern:
     * 1. Emit call() - "I am initiating send()"
     * 2. Call producer.send()
     * 3. In callback: emit succeeded() or failed()
     *
     * @param record producer record to send
     * @param userCallback optional user callback (called after service monitoring)
     */
    public void send(ProducerRecord<K, V> record, Callback userCallback) {
        // STEP 1: Emit CALL signal (CALLER dimension: "I am calling")
        sendService.call(Dimension.CALLER);

        logger.debug("[SERVICES] send() CALL emitted for topic: {}", record.topic());

        try {
            // STEP 2: Execute actual send operation
            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    // STEP 3a: Emit SUCCESS signal (CALLER dimension: my call succeeded)
                    sendService.success(Dimension.CALLER);

                    logger.debug("[SERVICES] send() SUCCEEDED - topic: {}, partition: {}, offset: {}",
                        metadata.topic(), metadata.partition(), metadata.offset());

                } else {
                    // STEP 3b: Emit FAIL signal (CALLER dimension: my call failed)
                    sendService.fail(Dimension.CALLER);

                    logger.warn("[SERVICES] send() FAILED - topic: {}, error: {}",
                        record.topic(), exception.getMessage());
                }

                // STEP 4: Call user callback if provided
                if (userCallback != null) {
                    userCallback.onCompletion(metadata, exception);
                }
            });

        } catch (java.lang.Exception e) {
            // STEP 3c: Emit FAIL signal for synchronous failures (CALLER dimension)
            sendService.fail(Dimension.CALLER);

            logger.error("[SERVICES] send() FAILED (sync) - topic: {}, error: {}",
                record.topic(), e.getMessage());

            throw e;
        }
    }

    /**
     * Send record without user callback (for simple fire-and-forget usage).
     *
     * @param record producer record to send
     */
    public void send(ProducerRecord<K, V> record) {
        send(record, null);
    }

    /**
     * Get services conduit for Layer 2 monitoring.
     * <p>
     * Allows creating a SendHealthMonitor that subscribes to send() operation signals:
     * <pre>{@code
     * SendHealthMonitor monitor = new SendHealthMonitor(
     *     circuit,
     *     sendService.services()  // Subscribe to call/succeeded/failed
     * );
     * }</pre>
     *
     * @return services conduit emitting send() operation signals
     */
    public Conduit<Service, Services.Signal> services() {
        return services;
    }

    /**
     * Get underlying Kafka producer (for direct access if needed).
     *
     * @return wrapped Kafka producer
     */
    public KafkaProducer<K, V> getProducer() {
        return producer;
    }

    @Override
    public void close() {
        logger.info("Shutting down producer send service");
        // Note: Don't close producer here - let caller manage producer lifecycle
        logger.info("Producer send service stopped (producer lifecycle managed by caller)");
    }
}
