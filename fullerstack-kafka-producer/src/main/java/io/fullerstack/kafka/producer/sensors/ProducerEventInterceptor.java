package io.fullerstack.kafka.producer.sensors;

import io.humainary.modules.serventis.services.api.Services;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Kafka ProducerInterceptor implementation that uses Humainary {@link Services.Service} interface.
 * <p>
 * Captures producer-broker interaction lifecycle events by calling methods on the {@link Services.Service}
 * interface, which emits {@link Services.Signal} enums (CALL, SUCCEEDED, FAILED, RETRY) into the
 * Substrates signal pipeline.
 *
 * <h3>Lifecycle Tracking:</h3>
 * <pre>
 * 1. Application calls producer.send(record)
 *    → onSend() → service.call() → Services.Signal.CALL emitted
 *
 * 2. Broker acknowledges write
 *    → onAcknowledgement(metadata, null) → service.succeeded() → Services.Signal.SUCCEEDED emitted
 *
 * 3. Send fails (timeout, network error, etc.)
 *    → onAcknowledgement(metadata, exception) → service.failed() → Services.Signal.FAILED emitted
 * </pre>
 *
 * <h3>Signal Semantics (Humainary Serventis):</h3>
 * <ul>
 *   <li><b>CALL</b> (RELEASE orientation) - Producer initiates send (present tense)</li>
 *   <li><b>SUCCEEDED</b> (RECEIPT orientation) - Broker acknowledged in past (past participle)</li>
 *   <li><b>FAILED</b> (RECEIPT orientation) - Broker rejected in past (past participle)</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <pre>{@code
 * // Runtime creates Services.Service from ServicesComposer
 * Cell<Services.Service, Services.Signal> cell = circuit.cell(
 *     new ServicesComposer(),
 *     signalPipe
 * );
 * Services.Service service = cell.pipe();
 *
 * // Configure producer with interceptor and service
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:9092");
 * props.put("client.id", "my-producer");
 * props.put("interceptor.classes", ProducerEventInterceptor.class.getName());
 * props.put("fullerstack.service", service);  // Inject Services.Service
 *
 * KafkaProducer<String, String> producer = new KafkaProducer<>(props);
 * }</pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * Interceptor.call()
 *   → Services.Signal.CALL
 *   → ServicesComposer emits signal
 *   → SignalEnrichmentComposer adds Subject/VectorClock
 *   → ServiceSignal
 *   → Observers
 * </pre>
 *
 * <h3>Error Handling:</h3>
 * All interceptor logic is wrapped in try-catch to prevent failures from breaking the producer.
 * Errors are logged but do not propagate.
 *
 * @param <K> Producer record key type
 * @param <V> Producer record value type
 *
 * @author Fullerstack
 * @see Services.Service
 * @see Services.Signal
 * @see io.fullerstack.serventis.composers.ServicesComposer
 */
public class ProducerEventInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerEventInterceptor.class);

    /**
     * Configuration key for injecting the Humainary Services.Service interface.
     * <p>
     * Value must be a {@link Services.Service} instance created by the runtime.
     */
    public static final String SERVICE_CONFIG_KEY = "fullerstack.service";

    private String producerId;
    private Services.Service service;

    /**
     * Configure the interceptor with producer properties.
     * <p>
     * Extracts:
     * <ul>
     *   <li>{@code client.id} → producerId</li>
     *   <li>{@code fullerstack.service} → Services.Service instance</li>
     * </ul>
     *
     * @param configs Producer configuration map
     */
    @Override
    public void configure(Map<String, ?> configs) {
        // Extract producer ID from client.id config
        Object clientIdObj = configs.get("client.id");
        this.producerId = clientIdObj != null ? clientIdObj.toString() : "unknown-producer";

        // Extract Services.Service from config
        Object serviceObj = configs.get(SERVICE_CONFIG_KEY);
        if (serviceObj instanceof Services.Service) {
            this.service = (Services.Service) serviceObj;
            logger.info("ProducerEventInterceptor configured for producer: {} with Services.Service",
                producerId);
        } else {
            logger.warn("ProducerEventInterceptor configured without Services.Service for producer: {}. " +
                "Signals will not be emitted. Set '{}' in producer config.",
                producerId, SERVICE_CONFIG_KEY);
        }
    }

    /**
     * Called when producer initiates a send operation (before network transmission).
     * <p>
     * Emits {@link Services.Signal#CALL} (RELEASE orientation - present tense).
     *
     * @param record Producer record being sent
     * @return Original record (unmodified - this interceptor is read-only)
     */
    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (service == null) {
            return record;  // Not configured, pass through
        }

        try {
            // Emit CALL signal (RELEASE orientation - producer initiates)
            service.call();

            logger.trace("Producer {} CALL for topic {} partition {}",
                producerId, record.topic(), record.partition());

        } catch (Exception e) {
            // CRITICAL: Don't let interceptor errors break the producer
            logger.error("Error in ProducerEventInterceptor.onSend for producer {}: {}",
                producerId, e.getMessage(), e);
        }

        return record;  // Must return record (possibly modified, but we don't modify)
    }

    /**
     * Called after broker responds (either acknowledgement or failure).
     * <p>
     * Emits either {@link Services.Signal#SUCCEEDED} (exception == null) or
     * {@link Services.Signal#FAILED} (exception != null).
     * Both use RECEIPT orientation (past tense - broker's action happened in the past).
     *
     * @param metadata Record metadata (topic, partition, offset)
     * @param exception Null for successful ack, non-null for failures
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (service == null) {
            return;  // Not configured
        }

        try {
            if (exception == null) {
                // ACK received - SUCCEEDED (RECEIPT orientation - broker succeeded in past)
                service.succeeded();

                logger.trace("Producer {} SUCCEEDED for topic {} partition {} offset {}",
                    producerId, metadata.topic(), metadata.partition(), metadata.offset());

            } else {
                // Send failed - FAILED (RECEIPT orientation - broker failed in past)
                service.failed();

                logger.debug("Producer {} FAILED for topic {} partition {}: {}",
                    producerId, metadata.topic(), metadata.partition(),
                    exception.getClass().getSimpleName());
            }

        } catch (Exception e) {
            // CRITICAL: Don't let interceptor errors break the producer
            logger.error("Error in ProducerEventInterceptor.onAcknowledgement for producer {}: {}",
                producerId, e.getMessage(), e);
        }
    }

    /**
     * Close the interceptor and clean up resources.
     */
    @Override
    public void close() {
        logger.info("ProducerEventInterceptor closed for producer: {}", producerId);
    }
}
