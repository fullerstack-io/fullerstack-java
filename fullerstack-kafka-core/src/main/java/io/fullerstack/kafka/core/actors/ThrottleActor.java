package io.fullerstack.kafka.core.actors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Actor that throttles producer throughput when buffer overflow is detected.
 *
 * <p>ThrottleActor extends {@link BaseActor} to provide automated throttling when
 * ProducerHealthReporter instances emit CRITICAL urgency signs. This implements the
 * ACT phase of the OODA loop for producer backpressure management.
 *
 * <h3>Percept-Based Architecture:</h3>
 * <pre>
 * ProducerHealthReporter (Percept) → emits Reporters.Sign.CRITICAL
 *                                  ↓
 *                   ThrottleActor subscribes & filters (multiple producers)
 *                                  ↓
 *                   Update producer configs:
 *                   - max.in.flight.requests ÷ 2
 *                   - linger.ms × 2 (more batching)
 *                                  ↓
 *                 Actor (Percept) → emits Actors.Sign.DELIVER or DENY
 * </pre>
 *
 * <h3>Hierarchical Name Filtering:</h3>
 * <p>ThrottleActor uses hierarchical Name matching to subscribe to multiple producer health reporters:
 * <pre>{@code
 * // Hierarchical naming pattern: "producer.{producerId}.health"
 * // Examples: "producer.producer-1.health", "producer.producer-2.health"
 *
 * // Filtering logic using Name hierarchy:
 * if (reporterName.depth() == 3 &&
 *     "producer".equals(reporterName.extremity().value()) &&
 *     "health".equals(reporterName.value())) {
 *     // Extract producer ID from middle segment
 *     String producerId = reporterName.enclosure().get().value(); // e.g., "producer-1"
 *     throttleProducer(producerId);
 * }
 * }</pre>
 *
 * <h3>Throttling Strategy:</h3>
 * <ul>
 *   <li><b>Reduce max.in.flight.requests.per.connection</b>: Cuts request pipelining by 50%,
 *       reducing memory pressure and allowing broker to catch up</li>
 *   <li><b>Increase linger.ms</b>: Doubles batching delay (capped at 100ms), improving
 *       compression and reducing request rate</li>
 * </ul>
 *
 * <h3>Rate Limiting:</h3>
 * Maximum 1 throttle action per 5 minutes (300 seconds) to prevent excessive
 * config churn during sustained overload. Subsequent CRITICAL signs within the
 * rate limit window will emit DENY speech acts.
 *
 * <h3>Error Handling:</h3>
 * <ul>
 *   <li>Config update failure → Throws exception, emits DENY sign</li>
 *   <li>Invalid producer ID → Logged as error, throws exception</li>
 *   <li>Config retrieval failure → Propagated to caller</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create circuits
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Reporters.Reporter, Reporters.Sign> reporters = reporterCircuit.conduit(
 *     cortex().name("reporters"),
 *     Reporters::composer
 * );
 *
 * Circuit actorCircuit = cortex().circuit(cortex().name("actors"));
 * Conduit<Actors.Actor, Actors.Sign> actors = actorCircuit.conduit(
 *     cortex().name("actors"),
 *     Actors::composer
 * );
 *
 * // Create KafkaConfigManager (implementation-specific)
 * KafkaConfigManager configManager = new KafkaConfigManagerImpl(producerRegistry);
 *
 * // Create ThrottleActor
 * ThrottleActor throttleActor = new ThrottleActor(
 *     reporters,
 *     actors,
 *     configManager
 * );
 *
 * // Actor automatically subscribes to ALL producer health reporters
 * // and throttles producers on CRITICAL signs
 * }</pre>
 *
 * @see BaseActor
 * @see KafkaConfigManager
 * @since 1.0.0
 */
public class ThrottleActor extends BaseActor {

    private static final Logger logger = LoggerFactory.getLogger(ThrottleActor.class);

    /**
     * Rate limit: 1 throttle per 5 minutes (300 seconds).
     * Prevents excessive config changes during sustained buffer overflow.
     */
    private static final long RATE_LIMIT_MS = 300_000; // 5 minutes

    /**
     * Reduction factor for max.in.flight.requests.per.connection.
     * Current value: 50% reduction (divide by 2).
     */
    private static final int MAX_INFLIGHT_REDUCTION_FACTOR = 2;

    /**
     * Increase factor for linger.ms batching delay.
     * Current value: 2x increase (more aggressive batching).
     */
    private static final int LINGER_INCREASE_FACTOR = 2;

    /**
     * Maximum linger.ms value in milliseconds.
     * Prevents excessive latency from over-batching.
     */
    private static final int MAX_LINGER_MS = 100;

    private final KafkaConfigManager configManager;
    private final Subscription subscription;

    /**
     * Creates a new ThrottleActor.
     *
     * @param reporters        Reporters conduit to subscribe to
     * @param actors           Actors conduit for emitting speech acts
     * @param configManager    Kafka configuration manager for producer updates
     */
    public ThrottleActor(
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Conduit<Actors.Actor, Actors.Sign> actors,
        KafkaConfigManager configManager
    ) {
        super(actors, "throttle-actor", RATE_LIMIT_MS);

        this.configManager = configManager;

        // Subscribe to ALL producer health reporters using hierarchical Name filtering
        // Pattern: "producer.{producerId}.health"
        // Example: "producer.producer-1.health", "producer.producer-2.health"

        this.subscription = reporters.subscribe(cortex().subscriber(
            cortex().name("throttle-actor-subscriber"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
                Name reporterName = subject.name();

                // Filter: Register pipes only for producer health reporters
                if (isProducerHealthReporter(reporterName)) {
                    registrar.register(sign -> {
                        if (sign == Reporters.Sign.CRITICAL) {
                            handleProducerCritical(reporterName);
                        }
                    });
                }
            }
        ));

        logger.info("ThrottleActor initialized with 5-minute rate limit");
    }

    /**
     * Checks if a reporter Name represents a producer health reporter.
     *
     * <p>Uses hierarchical Name pattern matching:
     * <ul>
     *   <li>Extremity (root) must be "producer"</li>
     *   <li>Value (leaf) must be "health"</li>
     *   <li>Depth must be 3 (producer.{producerId}.health)</li>
     * </ul>
     *
     * @param reporterName The reporter Name to check
     * @return true if this is a producer health reporter
     */
    private boolean isProducerHealthReporter(Name reporterName) {
        // Hierarchical pattern: "producer.{producerId}.health"
        // Example: "producer.producer-1.health"
        return reporterName.depth() == 3 &&
               "producer".equals(reporterName.extremity().value()) &&
               "health".equals(reporterName.value());
    }

    /**
     * Handles CRITICAL signs from ProducerHealthReporter.
     *
     * @param reporterName Name of the reporter that emitted CRITICAL
     */
    private void handleProducerCritical(Name reporterName) {
        // Extract producer ID from Name
        // Current: "producer-1-health" → "producer-1"
        // TODO: After hierarchical migration: reporterName.enclosure().get().value()
        String producerId = extractProducerId(reporterName);

        String actionKey = "throttle:" + producerId;

        executeWithProtection(actionKey, () -> {
            logger.warn("Producer '{}' buffer overflow detected (CRITICAL), applying throttle", producerId);

            // Throttle max.in.flight.requests.per.connection
            throttleMaxInflight(producerId);

            // Increase linger.ms for more aggressive batching
            increaseLinger(producerId);

            logger.info("Producer '{}' throttling completed successfully", producerId);
        });
    }

    /**
     * Extracts producer ID from hierarchical reporter Name.
     *
     * <p>Navigates the Name hierarchy to extract the middle segment:
     * <pre>
     * reporterName = "producer.producer-1.health"
     *                   root     middle    leaf
     *                            ↑
     *                       enclosure().get()
     * </pre>
     *
     * @param reporterName Reporter name (e.g., "producer.producer-1.health")
     * @return Producer identifier (e.g., "producer-1")
     */
    private String extractProducerId(Name reporterName) {
        // Hierarchical pattern: "producer.{producerId}.health"
        // Navigate: health -> producer-1 (via enclosure())
        return reporterName.enclosure()
            .orElseThrow(() -> new IllegalStateException("Expected enclosure for: " + reporterName.path()))
            .value();
    }

    /**
     * Reduces max.in.flight.requests.per.connection by 50%.
     *
     * @param producerId Producer identifier
     * @throws java.lang.Exception if config retrieval or update fails
     */
    private void throttleMaxInflight(String producerId) throws java.lang.Exception {
        int oldMaxInflight = configManager.getProducerConfig(
            producerId,
            "max.in.flight.requests.per.connection"
        );

        // Reduce by 50%, but keep at least 1
        int newMaxInflight = Math.max(1, oldMaxInflight / MAX_INFLIGHT_REDUCTION_FACTOR);

        configManager.updateProducerConfig(
            producerId,
            "max.in.flight.requests.per.connection",
            String.valueOf(newMaxInflight)
        );

        logger.info("Producer '{}': max.in.flight.requests.per.connection {} → {}",
            producerId, oldMaxInflight, newMaxInflight);
    }

    /**
     * Increases linger.ms batching delay by 2x (capped at 100ms).
     *
     * @param producerId Producer identifier
     * @throws java.lang.Exception if config retrieval or update fails
     */
    private void increaseLinger(String producerId) throws java.lang.Exception {
        int oldLinger = configManager.getProducerConfig(producerId, "linger.ms");

        // Increase by 2x, but cap at 100ms to avoid excessive latency
        int newLinger = Math.min(MAX_LINGER_MS, oldLinger * LINGER_INCREASE_FACTOR);

        configManager.updateProducerConfig(
            producerId,
            "linger.ms",
            String.valueOf(newLinger)
        );

        logger.info("Producer '{}': linger.ms {} → {} ms (more batching)",
            producerId, oldLinger, newLinger);
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
        logger.info("ThrottleActor closed");
    }
}
