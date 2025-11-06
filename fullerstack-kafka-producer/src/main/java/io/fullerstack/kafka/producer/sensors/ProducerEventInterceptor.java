package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Probes;
import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.*;

/**
 * Kafka ProducerInterceptor that emits Probes (Layer 1) and Services signals (Layer 2).
 * <p>
 * <b>Semiotic Observability Architecture (Substrates RC1):</b>
 * <pre>
 * Raw Signals → Conditions → Situations → Actions
 *     ↓             ↓            ↓
 *   Probes →    Monitors →   Reporters
 *
 * Layer 1 (Probes): Raw communication observations
 *   → Outcome (SUCCESS/FAILURE) × Origin (CLIENT/SERVER) × Operation (SEND/RECEIVE)
 *
 * Layer 2 (Services): Service lifecycle semantics
 *   → CALL (initiate) → SUCCESS/FAIL (outcome)
 *   → RELEASE orientation: Self-perspective ("I am calling", "I succeeded")
 * </pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * 1. onSend(record)
 *    → Probe: send(CLIENT, SUCCESS)       [Layer 1: Attempting send]
 *    → Service: call()                    [Layer 2: "I am calling"]
 *
 * 2. onAcknowledgement(metadata, null)
 *    → Probe: receive(CLIENT, SUCCESS)    [Layer 1: Got acknowledgement]
 *    → Service: success()                 [Layer 2: "I succeeded"]
 *    → Record latency for baseline
 *
 * 3. onAcknowledgement(metadata, exception)
 *    → Probe: send(CLIENT, FAILURE)       [Layer 1: Send failed]
 *    → Service: fail()                    [Layer 2: "I failed"]
 * </pre>
 *
 * <h3>Configuration (RC1 Pattern):</h3>
 * <pre>{@code
 * // Runtime creates Probes and Services instruments
 * Circuit circuit = Cortex.circuit(Cortex.name("kafka"));
 *
 * // Probe for raw observations
 * Probes.Probe probe = circuit
 *     .conduit(Probes::composer)
 *     .channel(Cortex.name("producer.operations"));
 *
 * // Service for lifecycle semantics
 * Services.Service service = circuit
 *     .conduit(Services::composer)
 *     .channel(Cortex.name("producer.calls"));
 *
 * // Configure producer with instruments
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:9092");
 * props.put("client.id", "my-producer");
 * props.put("interceptor.classes", ProducerEventInterceptor.class.getName());
 * props.put(ProducerEventInterceptor.PROBE_KEY, probe);
 * props.put(ProducerEventInterceptor.SERVICE_KEY, service);
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
 * <h3>Layer Separation:</h3>
 * <ul>
 *   <li><b>This interceptor (Layers 1-2):</b> Emits raw observations and service semantics</li>
 *   <li><b>Monitor aggregators (Layer 3):</b> Analyze patterns, assess conditions (STABLE/DEGRADED/DOWN)</li>
 *   <li><b>Reporter assessors (Layer 4):</b> Determine situations (NORMAL/ELEVATED/CRITICAL)</li>
 * </ul>
 *
 * @param <K> Producer record key type
 * @param <V> Producer record value type
 *
 * @author Fullerstack
 * @see Probes
 * @see Services
 */
public class ProducerEventInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerEventInterceptor.class);

    /**
     * Configuration key for injecting the Probe instrument.
     * <p>
     * Value must be a {@link Probes.Probe} instance.
     */
    public static final String PROBE_KEY = "fullerstack.probe";

    /**
     * Configuration key for injecting the Service instrument.
     * <p>
     * Value must be a {@link Services.Service} instance.
     */
    public static final String SERVICE_KEY = "fullerstack.service";

    private String producerId;
    private Probes.Probe probe;
    private Services.Service service;

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
     * </ul>
     *
     * @param configs Producer configuration map
     */
    @Override
    public void configure ( Map < String, ? > configs ) {
      // Extract producer ID from client.id config
      Object clientIdObj = configs.get ( "client.id" );
      this.producerId = clientIdObj != null ? clientIdObj.toString () : "unknown-producer";

      // Extract Probe from config
      Object probeObj = configs.get ( PROBE_KEY );
      if ( probeObj instanceof Probes.Probe ) {
        this.probe = (Probes.Probe) probeObj;
        logger.info (
          "ProducerEventInterceptor configured for producer: {} with Probe",
          producerId
        );
      } else {
        logger.warn (
          "ProducerEventInterceptor configured without Probe for producer: {}. " +
          "Layer 1 observations will not be emitted. Set '{}' in producer config.",
          producerId,
          PROBE_KEY
        );
      }

      // Extract Service from config
      Object serviceObj = configs.get ( SERVICE_KEY );
      if ( serviceObj instanceof Services.Service ) {
        this.service = (Services.Service) serviceObj;
        logger.info (
          "ProducerEventInterceptor configured for producer: {} with Service",
          producerId
        );
      } else {
        logger.warn (
          "ProducerEventInterceptor configured without Service for producer: {}. " +
          "Layer 2 signals will not be emitted. Set '{}' in producer config.",
          producerId,
          SERVICE_KEY
        );
      }
    }

    /**
     * Called when producer initiates a send operation (before network transmission).
     * <p>
     * Emits Layer 1 Probe (SEND, CLIENT, SUCCESS) and Layer 2 Service (CALL) signals.
     *
     * @param record Producer record being sent
     * @return Original record (unmodified - this interceptor is read-only)
     */
    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        try {
            // Record send time for latency tracking
            String key = requestKey(record.topic(), record.partition());
            inFlightRequests.put(key, System.nanoTime());

            // Layer 1 (Probes): Raw observation - attempting to send
            if (probe != null) {
                probe.transmitted();
            }

            // Layer 2 (Services): Service-level semantics - calling broker
            if (service != null) {
                service.call();  // RELEASE orientation: "I am calling"
            }

            logger.trace("Producer {} CALL for topic {} partition {}",
                producerId, record.topic(), record.partition());

        } catch (java.lang.Exception e) {
            // CRITICAL: Don't let interceptor errors break the producer
            logger.error("Error in ProducerEventInterceptor.onSend for producer {}: {}",
                producerId, e.getMessage(), e);
        }

        return record;  // Must return record (possibly modified, but we don't modify)
    }

    /**
     * Called after broker responds (either acknowledgement or failure).
     * <p>
     * Emits Layer 1 Probe (RECEIVE SUCCESS/FAILURE) and Layer 2 Service (SUCCESS/FAIL) signals.
     * <p>
     * NOTE: Latency interpretation and baseline tracking remain for future Layer 3 Monitor aggregation.
     * In RC1, diagnostic metadata would be stored in Subject.state() rather than signal payload.
     *
     * @param metadata Record metadata (topic, partition, offset)
     * @param exception Null for successful ack, non-null for failures
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, java.lang.Exception exception) {
        try {
            // Calculate latency
            String key = requestKey(metadata.topic(), metadata.partition());
            Long sendTime = inFlightRequests.remove(key);
            long latencyMs = sendTime != null
                ? (System.nanoTime() - sendTime) / 1_000_000
                : 0L;

            if (exception == null) {
                // ACK received - SUCCESS

                // Layer 1 (Probes): Raw observation - received acknowledgement
                if (probe != null) {
                    probe.received();
                }

                // Layer 2 (Services): Service-level semantics - call succeeded
                if (service != null) {
                    service.success();  // RELEASE orientation: "I succeeded"
                }

                logger.trace ( "Producer {} SUCCESS for topic {} partition {} offset {} latency={}ms",
                  producerId, metadata.topic (), metadata.partition (), metadata.offset (), latencyMs );

            } else {
                // Send failed - FAILURE

                // Layer 1 (Probes): Raw observation - send failed
                if (probe != null) {
                    probe.failed();
                }

                // Layer 2 (Services): Service-level semantics - call failed
                if (service != null) {
                    service.fail();  // RELEASE orientation: "I failed"
                }

                logger.debug("Producer {} FAIL for topic {} partition {} latency={}ms error={}",
                    producerId, metadata.topic(), metadata.partition(), latencyMs,
                    exception.getClass().getSimpleName());
            }

        } catch (java.lang.Exception e) {
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
