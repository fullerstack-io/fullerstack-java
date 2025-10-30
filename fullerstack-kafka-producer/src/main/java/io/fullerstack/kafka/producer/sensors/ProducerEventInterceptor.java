package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.serventis.signals.ServiceSignal;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Id;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Kafka ProducerInterceptor that interprets producer-broker interactions as ServiceSignals (signal-first).
 * <p>
 * <b>Signal-First Architecture:</b>
 * This interceptor INTERPRETS send events at the point of observation (where context exists)
 * and emits ServiceSignals with embedded meaning, NOT raw data bags.
 *
 * <h3>Transformed Architecture:</h3>
 * <pre>
 * ProducerEventInterceptor
 *   → INTERPRET latency vs baseline
 *   → emit(ServiceSignal)  [Signal with meaning]
 *   → Observers (no Composer needed!)
 * </pre>
 *
 * <h3>Lifecycle Tracking:</h3>
 * <pre>
 * 1. Application calls producer.send(record)
 *    → onSend() → ServiceSignal.call(subject, metadata)
 *
 * 2. Broker acknowledges write
 *    → onAcknowledgement(metadata, null)
 *    → INTERPRET latency vs baseline
 *    → ServiceSignal.succeeded(subject, metadata with assessment)
 *
 * 3. Send fails (timeout, network error, etc.)
 *    → onAcknowledgement(metadata, exception)
 *    → ServiceSignal.failed(subject, metadata with error details)
 * </pre>
 *
 * <h3>Configuration:</h3>
 * <pre>{@code
 * // Create baseline service
 * BaselineService baselineService = new SimpleBaselineService();
 *
 * // Runtime creates Cell for ServiceSignals
 * Cell<ServiceSignal, ServiceSignal> producerCell = circuit.cell(
 *     Composer.pipe(),  // No Composer needed - signals already interpreted!
 *     observerPipe
 * );
 *
 * // Configure producer with interceptor, Pipe, and BaselineService
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:9092");
 * props.put("client.id", "my-producer");
 * props.put("interceptor.classes", ProducerEventInterceptor.class.getName());
 * props.put(ProducerEventInterceptor.SIGNAL_PIPE_KEY, producerCell);  // Cell IS-A Pipe
 * props.put(ProducerEventInterceptor.BASELINE_SERVICE_KEY, baselineService);
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
 * <h3>Latency Interpretation:</h3>
 * The interceptor uses BaselineService to interpret latency in context:
 * <ul>
 *   <li>Normal: latency ≤ baseline * 1.2</li>
 *   <li>Elevated: latency > baseline * 1.2</li>
 *   <li>Degraded: latency > baseline * 1.5</li>
 *   <li>Severely degraded: latency > baseline * 2.0</li>
 * </ul>
 *
 * @param <K> Producer record key type
 * @param <V> Producer record value type
 *
 * @author Fullerstack
 * @see ServiceSignal
 * @see BaselineService
 */
public class ProducerEventInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerEventInterceptor.class);

    /**
     * Configuration key for injecting the signal Pipe (typically a Cell).
     * <p>
     * Value must be a {@link Pipe}{@code <ServiceSignal>} instance.
     */
    public static final String SIGNAL_PIPE_KEY = "fullerstack.signal.pipe";

    /**
     * Configuration key for injecting the BaselineService.
     * <p>
     * Value must be a {@link BaselineService} instance.
     */
    public static final String BASELINE_SERVICE_KEY = "fullerstack.baseline.service";

    private String producerId;
    private Pipe<ServiceSignal> signalPipe;
    private BaselineService baselineService;

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
     *   <li>{@code fullerstack.signal.pipe} → Pipe instance for signals</li>
     *   <li>{@code fullerstack.baseline.service} → BaselineService for interpretation</li>
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

        // Extract Pipe<ServiceSignal> from config
        Object pipeObj = configs.get(SIGNAL_PIPE_KEY);
        if (pipeObj instanceof Pipe) {
            this.signalPipe = (Pipe<ServiceSignal>) pipeObj;
            logger.info("ProducerEventInterceptor configured for producer: {} with signal pipe",
                producerId);
        } else {
            logger.warn("ProducerEventInterceptor configured without signal pipe for producer: {}. " +
                "Signals will not be emitted. Set '{}' in producer config.",
                producerId, SIGNAL_PIPE_KEY);
        }

        // Extract BaselineService from config
        Object baselineObj = configs.get(BASELINE_SERVICE_KEY);
        if (baselineObj instanceof BaselineService) {
            this.baselineService = (BaselineService) baselineObj;
            logger.info("ProducerEventInterceptor configured for producer: {} with baseline service",
                producerId);
        } else {
            logger.warn("ProducerEventInterceptor configured without baseline service for producer: {}. " +
                "Latency interpretation will be limited. Set '{}' in producer config.",
                producerId, BASELINE_SERVICE_KEY);
        }
    }

    /**
     * Called when producer initiates a send operation (before network transmission).
     * <p>
     * Emits ServiceSignal.CALL and records send time for latency tracking.
     *
     * @param record Producer record being sent
     * @return Original record (unmodified - this interceptor is read-only)
     */
    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (signalPipe == null) {
            return record;  // Not configured, pass through
        }

        try {
            // Record send time for latency tracking
            String key = requestKey(record.topic(), record.partition());
            inFlightRequests.put(key, System.nanoTime());

            // Create Subject for producer->topic interaction
            Subject subject = createSubject(producerId, record.topic(), record.partition());

            // Emit ServiceSignal.CALL
            Map<String, String> metadata = new HashMap<>();
            metadata.put("producer_id", producerId);
            metadata.put("topic", record.topic());
            metadata.put("partition", String.valueOf(record.partition() != null ? record.partition() : -1));

            ServiceSignal signal = ServiceSignal.call(subject, metadata);
            signalPipe.emit(signal);

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
     * Emits ServiceSignal.SUCCEEDED (exception == null) or ServiceSignal.FAILED (exception != null).
     * INTERPRETS latency vs baseline to add assessment to signal payload.
     *
     * @param metadata Record metadata (topic, partition, offset)
     * @param exception Null for successful ack, non-null for failures
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (signalPipe == null) {
            return;  // Not configured
        }

        try {
            // Calculate latency
            String key = requestKey(metadata.topic(), metadata.partition());
            Long sendTime = inFlightRequests.remove(key);
            long latencyMs = sendTime != null
                ? (System.nanoTime() - sendTime) / 1_000_000
                : 0L;

            // Create Subject for producer->topic interaction
            Subject subject = createSubject(producerId, metadata.topic(), metadata.partition());

            // Build metadata with interpretation
            Map<String, String> signalMetadata = new HashMap<>();
            signalMetadata.put("producer_id", producerId);
            signalMetadata.put("topic", metadata.topic());
            signalMetadata.put("partition", String.valueOf(metadata.partition()));
            signalMetadata.put("latency_ms", String.valueOf(latencyMs));

            if (exception == null) {
                // ACK received - SUCCEEDED
                signalMetadata.put("offset", String.valueOf(metadata.offset()));

                // INTERPRET latency vs baseline (where context exists)
                if (baselineService != null) {
                    long expectedLatency = baselineService.getExpectedProducerLatency(producerId, metadata.topic());
                    String trend = baselineService.getTrend(producerId + ":" + metadata.topic(), "latency", java.time.Duration.ofMinutes(5));

                    signalMetadata.put("expected_latency_ms", String.valueOf(expectedLatency));
                    signalMetadata.put("latency_trend", trend);

                    // Add interpretation
                    if (latencyMs > expectedLatency * 2.0) {
                        signalMetadata.put("assessment", "Severely degraded performance: " + latencyMs + "ms vs " + expectedLatency + "ms baseline");
                    } else if (latencyMs > expectedLatency * 1.5) {
                        signalMetadata.put("assessment", "Degraded performance: " + latencyMs + "ms vs " + expectedLatency + "ms baseline");
                    } else if (latencyMs > expectedLatency * 1.2) {
                        signalMetadata.put("assessment", "Elevated latency: " + latencyMs + "ms vs " + expectedLatency + "ms baseline");
                    } else {
                        signalMetadata.put("assessment", "Normal performance");
                    }

                    // Record observation for future baselines
                    baselineService.recordObservation(producerId + ":" + metadata.topic(), "latency", latencyMs, java.time.Instant.now());
                } else {
                    // No baseline - just note the latency
                    signalMetadata.put("assessment", "Latency: " + latencyMs + "ms (no baseline available)");
                }

                ServiceSignal signal = ServiceSignal.succeeded(subject, signalMetadata);
                signalPipe.emit(signal);

                logger.trace("Producer {} SUCCEEDED for topic {} partition {} offset {} latency={}ms",
                    producerId, metadata.topic(), metadata.partition(), metadata.offset(), latencyMs);

            } else {
                // Send failed - FAILED
                signalMetadata.put("error_type", exception.getClass().getSimpleName());
                signalMetadata.put("error_message", exception.getMessage() != null ? exception.getMessage() : "No message");
                signalMetadata.put("assessment", "Send failed: " + exception.getClass().getSimpleName());

                ServiceSignal signal = ServiceSignal.failed(subject, signalMetadata);
                signalPipe.emit(signal);

                logger.debug("Producer {} FAILED for topic {} partition {} latency={}ms error={}",
                    producerId, metadata.topic(), metadata.partition(), latencyMs,
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

    /**
     * Create a Subject for producer->topic signal emission.
     * <p>
     * In full Circuit context, Subject would come from Cell.subject().
     * This is a simplified version for standalone sensor usage.
     *
     * @param producerId Producer identifier
     * @param topic Topic name
     * @param partition Partition number (may be null)
     * @return Subject wrapping the producer->topic interaction
     */
    @SuppressWarnings("unchecked")
    private Subject createSubject(final String producerId, final String topic, final Integer partition) {
        // Create a unique name for this producer->topic->partition interaction
        int p = partition != null ? partition : -1;
        final String entityName = producerId + ".topic." + topic + ".partition." + p;

        return new Subject() {
            @Override
            public Id id() {
                return null; // No specific ID
            }

            @Override
            public Name name() {
                // Use cortex().name() per M18 API
                return cortex().name(entityName);
            }

            @Override
            public Class<ServiceSignal> type() {
                return ServiceSignal.class;
            }

            @Override
            public State state() {
                return null; // No state - Subject is used for signal identity only
            }

            @Override
            public int compareTo(Object o) {
                if (!(o instanceof Subject)) return -1;
                return name().toString().compareTo(((Subject) o).name().toString());
            }
        };
    }
}
