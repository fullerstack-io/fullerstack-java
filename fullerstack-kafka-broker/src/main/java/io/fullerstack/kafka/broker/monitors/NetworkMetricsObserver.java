package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.NetworkMetrics;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Emits Counters.Sign for network I/O metrics using Serventis RC1 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This observer emits signals with Counters API vocabulary (INCREMENT/OVERFLOW), NOT interpretations.
 *
 * <h3>Network I/O Metrics (5 metrics):</h3>
 * <ul>
 *   <li><b>Request Rate</b> - Total requests per second → Counters.INCREMENT</li>
 *   <li><b>Response Rate</b> - Total responses per second → Counters.INCREMENT</li>
 *   <li><b>Bytes In Rate</b> - Incoming bytes per second → Counters.INCREMENT + OVERFLOW (saturation)</li>
 *   <li><b>Bytes Out Rate</b> - Outgoing bytes per second → Counters.INCREMENT</li>
 *   <li><b>Messages In Rate</b> - Incoming messages per second → Counters.INCREMENT</li>
 * </ul>
 *
 * <h3>Signal Emission Logic:</h3>
 * <pre>
 * INCREMENT: Rate > 0 (activity detected)
 * OVERFLOW:  Bytes in rate exceeds saturation threshold (network congestion)
 * </pre>
 *
 * <p><b>Note:</b> This observer uses simple threshold logic for signal emission. Contextual
 * assessment using baselines, trends, and recommendations will be added in Epic 2
 * via Observers (Layer 4 - Semiosphere).
 */
public class NetworkMetricsObserver {

    private static final Logger logger = LoggerFactory.getLogger(NetworkMetricsObserver.class);

    private final Name circuitName;
    private final Channel<Counters.Sign> requestChannel;
    private final Channel<Counters.Sign> responseChannel;
    private final Channel<Counters.Sign> bytesInChannel;
    private final Channel<Counters.Sign> bytesOutChannel;
    private final Channel<Counters.Sign> messagesInChannel;

    private final Counters.Counter requestCounter;
    private final Counters.Counter responseCounter;
    private final Counters.Counter bytesInCounter;
    private final Counters.Counter bytesOutCounter;
    private final Counters.Counter messagesInCounter;

    /**
     * Creates a NetworkMetricsObserver.
     *
     * @param circuitName         Circuit name for logging
     * @param requestChannel      Channel for request rate signals
     * @param responseChannel     Channel for response rate signals
     * @param bytesInChannel      Channel for bytes in signals
     * @param bytesOutChannel     Channel for bytes out signals
     * @param messagesInChannel   Channel for messages in signals
     * @throws NullPointerException if any parameter is null
     */
    public NetworkMetricsObserver(
        Name circuitName,
        Channel<Counters.Sign> requestChannel,
        Channel<Counters.Sign> responseChannel,
        Channel<Counters.Sign> bytesInChannel,
        Channel<Counters.Sign> bytesOutChannel,
        Channel<Counters.Sign> messagesInChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.requestChannel = Objects.requireNonNull(requestChannel, "requestChannel cannot be null");
        this.responseChannel = Objects.requireNonNull(responseChannel, "responseChannel cannot be null");
        this.bytesInChannel = Objects.requireNonNull(bytesInChannel, "bytesInChannel cannot be null");
        this.bytesOutChannel = Objects.requireNonNull(bytesOutChannel, "bytesOutChannel cannot be null");
        this.messagesInChannel = Objects.requireNonNull(messagesInChannel, "messagesInChannel cannot be null");

        // Get Counters from RC1 API
        this.requestCounter = Counters.composer(requestChannel);
        this.responseCounter = Counters.composer(responseChannel);
        this.bytesInCounter = Counters.composer(bytesInChannel);
        this.bytesOutCounter = Counters.composer(bytesOutChannel);
        this.messagesInCounter = Counters.composer(messagesInChannel);
    }

    /**
     * Emits Counters.Sign for network I/O metrics.
     *
     * @param metrics Network metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(NetworkMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            // 1. Request Rate - increment if requests detected
            if (metrics.requestRate() > 0) {
                requestCounter.increment();
            }

            // 2. Response Rate - increment if responses detected
            if (metrics.responseRate() > 0) {
                responseCounter.increment();
            }

            // 3. Bytes In Rate - increment + overflow detection for saturation
            if (metrics.bytesInRate() > 0) {
                bytesInCounter.increment();
                if (metrics.isSaturated()) {
                    // Network saturation detected - emit OVERFLOW
                    bytesInCounter.overflow();
                }
            }

            // 4. Bytes Out Rate - increment if outgoing traffic detected
            if (metrics.bytesOutRate() > 0) {
                bytesOutCounter.increment();
            }

            // 5. Messages In Rate - increment if messages detected
            if (metrics.messagesInRate() > 0) {
                messagesInCounter.increment();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted network I/O signals for {}: " +
                    "requests={}/s, responses={}/s, bytesIn={}/s, bytesOut={}/s, messagesIn={}/s, saturated={}",
                    metrics.brokerId(),
                    (long) metrics.requestRate(),
                    (long) metrics.responseRate(),
                    (long) metrics.bytesInRate(),
                    (long) metrics.bytesOutRate(),
                    (long) metrics.messagesInRate(),
                    metrics.isSaturated());
            }
        } catch (Exception e) {
            logger.error("Failed to emit network I/O signals for {}: {}",
                metrics.brokerId(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }
}
