package io.fullerstack.kafka.consumer.services;

import io.humainary.substrates.ext.serventis.ext.services.Services;
import io.humainary.substrates.ext.serventis.ext.services.Services.Service;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Services API for consumer poll() operations (self-monitoring).
 * <p>
 * <b>Critical Insight from Humainary Article:</b>
 * Services API is for <b>SELF-MONITORING internal operations</b>, not monitoring external services.
 * <p>
 * This wrapper monitors the consumer's OWN poll() operation, emitting signals about:
 * <ul>
 *   <li>Operation initiation (call)</li>
 *   <li>Operation success (succeeded - even if 0 records)</li>
 *   <li>Operation failure (failed - exception thrown)</li>
 * </ul>
 *
 * <h3>Services API Pattern:</h3>
 * <ul>
 *   <li><b>call()</b> - "I am initiating this operation" (RELEASE orientation)</li>
 *   <li><b>succeeded()</b> - "My operation succeeded" (even if no records fetched)</li>
 *   <li><b>failed()</b> - "My operation failed"</li>
 * </ul>
 *
 * <h3>Why Services API Matters:</h3>
 * The Service can <b>SUBSCRIBE to its OWN health Monitor</b> for closed-loop feedback!
 * <pre>
 * Layer 1: ConsumerPollService → Services (call, succeeded, failed)
 *                                    ↓
 * Layer 2: PollHealthMonitor → Monitors (assess poll success rate)
 *                                    ↓
 * Layer 4a: ConsumerSelfRegulator → Agent pauses if failure rate high
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.health"));
 *
 * ConsumerPollService<String, String> pollService = new ConsumerPollService<>(
 *     consumer,
 *     circuit
 * );
 *
 * // Poll with self-monitoring
 * ConsumerRecords<String, String> records = pollService.poll(Duration.ofMillis(100));
 *
 * // Services signals emitted:
 * // 1. call() - "I am calling poll()"
 * // 2. succeeded() - "I completed poll()" (regardless of record count)
 *
 * // Process records
 * records.forEach(record -> {
 *     System.out.println("Received: " + record.value());
 * });
 *
 * // ... later ...
 * pollService.close();
 * }</pre>
 *
 * @param <K> consumer key type
 * @param <V> consumer value type
 * @see io.humainary.substrates.ext.serventis.ext.services.Services
 * @see Service
 */
public class ConsumerPollService<K, V> implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerPollService.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final KafkaConsumer<K, V> consumer;
    private final Conduit<Service, Services.Sign> services;
    private final Service pollService;

    /**
     * Creates a new consumer poll service with self-monitoring.
     *
     * @param consumer Kafka consumer to wrap
     * @param circuit parent circuit for services conduit
     */
    public ConsumerPollService(
        KafkaConsumer<K, V> consumer,
        Circuit circuit
    ) {
        this.cortex = cortex();
        this.consumer = Objects.requireNonNull(consumer, "consumer cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create Services conduit using Services.composer
        this.services = circuit.conduit(
            cortex.name("services"),
            Services::composer
        );

        // Get Service instrument for poll() operation
        this.pollService = services.channel(cortex.name("consumer.poll"));

        logger.info("ConsumerPollService created - self-monitoring enabled for poll() operations");
    }

    /**
     * Poll with Service self-monitoring.
     * <p>
     * Pattern:
     * 1. Emit call() - "I am initiating poll()"
     * 2. Call consumer.poll()
     * 3. Emit succeeded() (even if 0 records) or failed() (on exception)
     *
     * @param timeout poll timeout duration
     * @return consumer records (may be empty)
     */
    public ConsumerRecords<K, V> poll(Duration timeout) {
        // STEP 1: Emit CALL signal (RELEASE orientation: "I am calling")
        pollService.call();

        logger.debug("[SERVICES] poll() CALL emitted with timeout: {}", timeout);

        try {
            // STEP 2: Execute actual poll operation
            ConsumerRecords<K, V> records = consumer.poll(timeout);

            // STEP 3a: Emit SUCCEEDED signal
            // Note: Success even if 0 records (no data != failure)
            pollService.succeeded();

            if (records.isEmpty()) {
                logger.debug("[SERVICES] poll() SUCCEEDED - 0 records fetched (no data available)");
            } else {
                logger.debug("[SERVICES] poll() SUCCEEDED - {} records fetched", records.count());
            }

            return records;

        } catch (Exception e) {
            // STEP 3b: Emit FAILED signal
            pollService.failed();

            logger.error("[SERVICES] poll() FAILED - error: {}", e.getMessage());

            throw e;
        }
    }

    /**
     * Get services conduit for Layer 2 monitoring.
     * <p>
     * Allows creating a PollHealthMonitor that subscribes to poll() operation signals:
     * <pre>{@code
     * PollHealthMonitor monitor = new PollHealthMonitor(
     *     circuit,
     *     pollService.services()  // Subscribe to call/succeeded/failed
     * );
     * }</pre>
     *
     * @return services conduit emitting poll() operation signals
     */
    public Conduit<Service, Services.Sign> services() {
        return services;
    }

    /**
     * Get underlying Kafka consumer (for direct access if needed).
     * <p>
     * Use cases:
     * <ul>
     *   <li>Subscribe to topics: consumer.subscribe(...)</li>
     *   <li>Commit offsets: consumer.commitSync()</li>
     *   <li>Pause/resume partitions: consumer.pause(...)</li>
     * </ul>
     *
     * @return wrapped Kafka consumer
     */
    public KafkaConsumer<K, V> getConsumer() {
        return consumer;
    }

    @Override
    public void close() {
        logger.info("Shutting down consumer poll service");
        // Note: Don't close consumer here - let caller manage consumer lifecycle
        logger.info("Consumer poll service stopped (consumer lifecycle managed by caller)");
    }
}
