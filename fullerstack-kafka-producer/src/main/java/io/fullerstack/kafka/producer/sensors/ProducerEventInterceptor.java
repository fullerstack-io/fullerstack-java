package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.producer.models.ProducerEventMetrics;
import io.humainary.substrates.api.Substrates.Pipe;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka ProducerInterceptor that captures producer-broker interaction events as rich metrics.
 * <p>
 * Unlike the previous Services.Service approach (which lost all contextual data), this interceptor
 * emits {@link ProducerEventMetrics} records containing full context: topic, partition, offset,
 * latency, exceptions, and metadata.
 *
 * <h3>Architecture Pattern (Matching BrokerMetrics):</h3>
 * <pre>
 * ProducerEventInterceptor
 *   → emit(ProducerEventMetrics)  [Rich domain data]
 *   → Cell<ProducerEventMetrics, ServiceSignal>
 *   → ProducerEventComposer
 *   → emit(ServiceSignal)  [Semiotic signal]
 *   → Observers
 * </pre>
 *
 * <h3>Lifecycle Tracking:</h3>
 * <pre>
 * 1. Application calls producer.send(record)
 *    → onSend() → ProducerEventMetrics.call(producerId, topic, partition)
 *
 * 2. Broker acknowledges write
 *    → onAcknowledgement(metadata, null)
 *    → ProducerEventMetrics.succeeded(producerId, metadata, latency)
 *
 * 3. Send fails (timeout, network error, etc.)
 *    → onAcknowledgement(metadata, exception)
 *    → ProducerEventMetrics.failed(producerId, topic, partition, exception, latency)
 * </pre>
 *
 * <h3>Configuration:</h3>
 * <pre>{@code
 * // Runtime creates Cell with ProducerEventComposer
 * Cell<ProducerEventMetrics, ServiceSignal> producerCell = circuit.cell(
 *     new ProducerEventComposer(),
 *     observerPipe
 * );
 *
 * // Configure producer with interceptor and Pipe
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:9092");
 * props.put("client.id", "my-producer");
 * props.put("interceptor.classes", ProducerEventInterceptor.class.getName());
 * props.put(ProducerEventInterceptor.METRICS_PIPE_KEY, producerCell);  // Cell IS-A Pipe
 *
 * KafkaProducer<String, String> producer = new KafkaProducer<>(props);
 * }</pre>
 *
 * <h3>Latency Tracking:</h3>
 * Uses in-flight map to correlate onSend() with onAcknowledgement() for accurate latency measurement.
 * <p>
 * Key format: {@code "topic:partition"} (best effort - partitions may not be assigned at send time)
 *
 * <h3>Error Handling:</h3>
 * All interceptor logic is wrapped in try-catch to prevent failures from breaking the producer.
 * Errors are logged but do not propagate.
 *
 * @param <K> Producer record key type
 * @param <V> Producer record value type
 *
 * @author Fullerstack
 * @see ProducerEventMetrics
 * @see io.fullerstack.kafka.producer.composers.ProducerEventComposer
 */
public class ProducerEventInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerEventInterceptor.class);

    /**
     * Configuration key for injecting the metrics Pipe (typically a Cell).
     * <p>
     * Value must be a {@link Pipe}{@code <ProducerEventMetrics>} instance.
     */
    public static final String METRICS_PIPE_KEY = "fullerstack.metrics.pipe";

    private String producerId;
    private Pipe<ProducerEventMetrics> metricsPipe;

    /**
     * Tracks in-flight requests for latency measurement.
     * <p>
     * Key: "topic:partition" (partition may be -1 if not yet assigned)
     * Value: Send timestamp (nanoTime)
     */
    private final Map<String, Long> inFlightRequests = new ConcurrentHashMap<>();

    /**
     * Configure the interceptor with producer properties.
     * <p>
     * Extracts:
     * <ul>
     *   <li>{@code client.id} → producerId</li>
     *   <li>{@code fullerstack.metrics.pipe} → Pipe instance for metrics</li>
     * </ul>
     *
     * @param configs Producer configuration map
     */
    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs) {
        // Extract producer ID from client.id config
        Object clientIdObj = configs.get("client.id");
        this.producerId = clientIdObj != null ? clientIdObj.toString() : "unknown-producer";

        // Extract Pipe<ProducerEventMetrics> from config
        Object pipeObj = configs.get(METRICS_PIPE_KEY);
        if (pipeObj instanceof Pipe) {
            this.metricsPipe = (Pipe<ProducerEventMetrics>) pipeObj;
            logger.info("ProducerEventInterceptor configured for producer: {} with metrics pipe",
                producerId);
        } else {
            logger.warn("ProducerEventInterceptor configured without metrics pipe for producer: {}. " +
                "Metrics will not be emitted. Set '{}' in producer config.",
                producerId, METRICS_PIPE_KEY);
        }
    }

    /**
     * Called when producer initiates a send operation (before network transmission).
     * <p>
     * Emits ProducerEventMetrics with CALL signal and records send time for latency tracking.
     *
     * @param record Producer record being sent
     * @return Original record (unmodified - this interceptor is read-only)
     */
    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (metricsPipe == null) {
            return record;  // Not configured, pass through
        }

        try {
            // Record send time for latency tracking
            String key = requestKey(record.topic(), record.partition());
            inFlightRequests.put(key, System.nanoTime());

            // Emit CALL metrics
            ProducerEventMetrics metrics = ProducerEventMetrics.call(
                producerId,
                record.topic(),
                record.partition() != null ? record.partition() : -1
            );

            metricsPipe.emit(metrics);

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
     * Emits ProducerEventMetrics with SUCCEEDED (exception == null) or FAILED (exception != null).
     * Includes latency measurement if send time was recorded.
     *
     * @param metadata Record metadata (topic, partition, offset)
     * @param exception Null for successful ack, non-null for failures
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (metricsPipe == null) {
            return;  // Not configured
        }

        try {
            // Calculate latency
            String key = requestKey(metadata.topic(), metadata.partition());
            Long sendTime = inFlightRequests.remove(key);
            long latencyMs = sendTime != null
                ? (System.nanoTime() - sendTime) / 1_000_000
                : 0L;

            ProducerEventMetrics metrics;

            if (exception == null) {
                // ACK received - SUCCEEDED
                metrics = ProducerEventMetrics.succeeded(
                    producerId,
                    metadata,
                    latencyMs
                );

                logger.trace("Producer {} SUCCEEDED for topic {} partition {} offset {} latency={}ms",
                    producerId, metadata.topic(), metadata.partition(), metadata.offset(), latencyMs);

            } else {
                // Send failed - FAILED
                metrics = ProducerEventMetrics.failed(
                    producerId,
                    metadata.topic(),
                    metadata.partition(),
                    exception,
                    latencyMs
                );

                logger.debug("Producer {} FAILED for topic {} partition {} latency={}ms error={}",
                    producerId, metadata.topic(), metadata.partition(), latencyMs,
                    exception.getClass().getSimpleName());
            }

            metricsPipe.emit(metrics);

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
        inFlightRequests.clear();
        logger.info("ProducerEventInterceptor closed for producer: {}", producerId);
    }

    /**
     * Generate key for in-flight request tracking.
     *
     * @param topic Topic name
     * @param partition Partition (may be null or -1)
     * @return Key string "topic:partition"
     */
    private String requestKey(String topic, Integer partition) {
        int p = partition != null ? partition : -1;
        return topic + ":" + p;
    }
}
