package io.fullerstack.kafka.consumer.sensors;

import io.humainary.serventis.services.Services;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.humainary.substrates.api.Substrates.*;

/**
 * Kafka ConsumerInterceptor that emits Services signals (Layer 2) for consumer lifecycle events.
 * <p>
 * <b>Semiotic Observability Architecture (Substrates RC1):</b>
 * <pre>
 * Layer 2 (Services): Service lifecycle semantics
 *   → CALL (initiate poll) → SUCCESS/FAIL (outcome)
 *   → RELEASE orientation: Self-perspective ("I am polling", "I succeeded")
 * </pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * 1. onConsume(records)
 *    → Service: call()        [Layer 2: "I am polling"]
 *    → Service: success()     [Layer 2: "I succeeded" - if records received]
 *    → Service: fail()        [Layer 2: "I failed" - if timeout/exception]
 *
 * 2. onCommit(offsets)
 *    → Service: success()     [Layer 2: "Commit succeeded"]
 *    → Service: fail()        [Layer 2: "Commit failed" - if exception]
 * </pre>
 *
 * <h3>Configuration (RC1 Pattern):</h3>
 * <pre>{@code
 * // Runtime creates Services instrument
 * Circuit circuit = Cortex.circuit(Cortex.name("kafka"));
 *
 * // Service for lifecycle semantics
 * Services.Service service = circuit
 *     .conduit(Services::composer)
 *     .channel(Cortex.name("consumer.operations"));
 *
 * // Configure consumer with instrument
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:9092");
 * props.put("group.id", "my-group");
 * props.put("client.id", "my-consumer");
 * props.put("interceptor.classes", ConsumerEventInterceptor.class.getName());
 * props.put(ConsumerEventInterceptor.SERVICE_KEY, service);
 *
 * KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
 * }</pre>
 *
 * <h3>Poll Lifecycle:</h3>
 * <ul>
 *   <li><b>onConsume():</b> Called after poll() returns - emits CALL + SUCCESS/FAIL</li>
 *   <li><b>onCommit():</b> Called before commit completes - emits SUCCESS/FAIL</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * All interceptor logic is wrapped in try-catch to prevent failures from breaking the consumer.
 * Errors are logged but do not propagate.
 *
 * <h3>Rebalance Events:</h3>
 * Use {@link ConsumerRebalanceListenerAdapter} for SUSPEND/RESUME signals during rebalance.
 *
 * <h3>Layer Separation:</h3>
 * <ul>
 *   <li><b>This interceptor (Layer 2):</b> Emits raw service lifecycle events</li>
 *   <li><b>Monitor aggregators (Layer 3):</b> Analyze patterns (poll rate, success rate)</li>
 *   <li><b>Reporter assessors (Layer 4):</b> Determine situations (consumer lag, rebalance storms)</li>
 * </ul>
 *
 * @param <K> Consumer record key type
 * @param <V> Consumer record value type
 *
 * @author Fullerstack
 * @see Services
 * @see ConsumerRebalanceListenerAdapter
 */
public class ConsumerEventInterceptor<K, V> implements ConsumerInterceptor<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerEventInterceptor.class);

    /**
     * Configuration key for injecting the Service instrument.
     * <p>
     * Value must be a {@link Services.Service} instance.
     */
    public static final String SERVICE_KEY = "fullerstack.service";

    /**
     * Configuration key for consumer group ID (extracted from Kafka config).
     */
    private static final String GROUP_ID_KEY = "group.id";

    /**
     * Configuration key for consumer client ID (extracted from Kafka config).
     */
    private static final String CLIENT_ID_KEY = "client.id";

    private String consumerId;
    private String consumerGroup;
    private Services.Service service;
    private Instant lastPollTime;

    /**
     * Configure the interceptor with consumer properties.
     * <p>
     * Extracts:
     * <ul>
     *   <li>{@code group.id} → consumerGroup</li>
     *   <li>{@code client.id} → consumerId</li>
     *   <li>{@code fullerstack.service} → Service instance for signals</li>
     * </ul>
     *
     * @param configs Consumer configuration map
     */
    @Override
    public void configure(Map<String, ?> configs) {
        // Extract consumer group ID
        Object groupIdObj = configs.get(GROUP_ID_KEY);
        this.consumerGroup = groupIdObj != null ? groupIdObj.toString() : "unknown-group";

        // Extract consumer ID from client.id config
        Object clientIdObj = configs.get(CLIENT_ID_KEY);
        this.consumerId = clientIdObj != null ? clientIdObj.toString() : "unknown-consumer";

        // Extract Service from config
        Object serviceObj = configs.get(SERVICE_KEY);
        if (serviceObj instanceof Services.Service) {
            this.service = (Services.Service) serviceObj;
            logger.info(
                "ConsumerEventInterceptor configured for consumer: {} group: {} with Service",
                consumerId,
                consumerGroup
            );
        } else {
            logger.warn(
                "ConsumerEventInterceptor configured without Service for consumer: {} group: {}. " +
                "Layer 2 signals will not be emitted. Set '{}' in consumer config.",
                consumerId,
                consumerGroup,
                SERVICE_KEY
            );
        }

        // Initialize poll time tracking
        this.lastPollTime = Instant.now();
    }

    /**
     * Called after poll() returns records (before application processes them).
     * <p>
     * Emits Layer 2 Service signals:
     * <ul>
     *   <li><b>CALL:</b> Consumer initiating poll</li>
     *   <li><b>SUCCESS:</b> Poll completed with records</li>
     *   <li><b>FAIL:</b> Poll timeout or no records after long wait</li>
     * </ul>
     *
     * <h3>Success/Failure Determination:</h3>
     * <ul>
     *   <li><b>SUCCESS:</b> Records returned, or empty poll within reasonable time</li>
     *   <li><b>FAIL:</b> Empty poll after >5 second timeout (indicates broker issues)</li>
     * </ul>
     *
     * @param records Consumer records returned from poll()
     * @return Original records (unmodified - this interceptor is read-only)
     */
    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        try {
            // Calculate poll latency
            Instant now = Instant.now();
            long pollLatencyMs = Duration.between(lastPollTime, now).toMillis();
            lastPollTime = now;

            // Layer 2 (Services): Consumer is polling
            if (service != null) {
                service.call();  // RELEASE orientation: "I am calling/polling"
            }

            // Determine success vs failure
            boolean isTimeout = records.isEmpty() && pollLatencyMs > 5000;

            if (isTimeout) {
                // Poll timed out - likely broker connectivity issue
                if (service != null) {
                    service.fail();  // RELEASE orientation: "I failed"
                }
                logger.debug("Consumer {} poll TIMEOUT: group={} latency={}ms",
                    consumerId, consumerGroup, pollLatencyMs);
            } else {
                // Poll succeeded (records or reasonable timeout)
                if (service != null) {
                    service.success();  // RELEASE orientation: "I succeeded"
                }
                logger.trace("Consumer {} poll SUCCESS: group={} records={} latency={}ms",
                    consumerId, consumerGroup, records.count(), pollLatencyMs);
            }

        } catch (Exception e) {
            // CRITICAL: Don't let interceptor errors break the consumer
            logger.error("Error in ConsumerEventInterceptor.onConsume for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }

        return records;  // Must return records (possibly modified, but we don't modify)
    }

    /**
     * Called before offset commit completes.
     * <p>
     * Emits Layer 2 Service signals:
     * <ul>
     *   <li><b>SUCCESS:</b> Commit operation initiated successfully</li>
     * </ul>
     *
     * <p><b>Note:</b> Kafka ConsumerInterceptor API does not provide commit failure callback.
     * Commit failures must be detected via consumer metrics or Epic 3 monitoring.
     *
     * @param offsets Map of offsets being committed
     */
    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        try {
            // Layer 2 (Services): Commit succeeded (onCommit only called for successful commits)
            if (service != null) {
                service.success();  // RELEASE orientation: "I succeeded"
            }

            logger.trace("Consumer {} commit SUCCESS: group={} offsets={}",
                consumerId, consumerGroup, offsets.size());

        } catch (Exception e) {
            // CRITICAL: Don't let interceptor errors break the consumer
            logger.error("Error in ConsumerEventInterceptor.onCommit for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }

    /**
     * Close the interceptor and clean up resources.
     */
    @Override
    public void close() {
        logger.info("ConsumerEventInterceptor closed for consumer: {} group: {}",
            consumerId, consumerGroup);
    }
}
