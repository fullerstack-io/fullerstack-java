package io.fullerstack.kafka.broker.receptors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Probes;
import io.humainary.substrates.ext.serventis.ext.Probes.Probe;
import io.humainary.substrates.ext.serventis.ext.Resources;
import io.humainary.substrates.ext.serventis.ext.Resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 1 (OBSERVE): Polls JMX network metrics and emits raw Serventis signals.
 *
 * <p><b>Responsibilities (OBSERVATION ONLY):</b>
 * <ul>
 *   <li>Poll JMX MBeans at regular intervals</li>
 *   <li>Emit raw signals via Serventis instruments (Gauges, Counters, Probes, Resources)</li>
 *   <li>NO interpretation, NO assessment, NO decision-making</li>
 * </ul>
 *
 * <p><b>Thresholds (for signal selection, NOT interpretation):</b>
 * <ul>
 *   <li>Throttle time >1000ms → OVERFLOW signal</li>
 *   <li>Throttle time >100ms → INCREMENT signal</li>
 *   <li>Throttle time <100ms → DECREMENT signal</li>
 *   <li>Quota usage >100% → INCREMENT counter</li>
 *   <li>Network thread wait >50% → DENY signal</li>
 *   <li>Network thread wait >25% → TIMEOUT signal</li>
 *   <li>Network thread wait <25% → GRANT signal</li>
 * </ul>
 *
 * <p><b>Signal Flow:</b>
 * <pre>
 * JMX MBeans → NetworkMetricsObserver → Gauges/Counters/Probes/Resources
 *                                            ↓
 *                                    (Layer 2 Monitors subscribe here)
 * </pre>
 *
 * <p><b>JMX MBeans Polled:</b>
 * <ul>
 *   <li>kafka.network:type=RequestMetrics,name=ThrottleTimeMs,request=*</li>
 *   <li>kafka.server:type=ClientQuotaManager,user=*,client-id=*</li>
 *   <li>kafka.network:type=SocketServer,name=failed-authentication-total</li>
 *   <li>kafka.server:type=BrokerTopicMetrics,name=FailedAuthenticationRate</li>
 *   <li>kafka.network:type=SocketServer,name=NetworkProcessorAvgWaitPercent</li>
 * </ul>
 */
public class NetworkMetricsObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NetworkMetricsObserver.class);

    // Thresholds for SIGNAL SELECTION (not interpretation!)
    private static final double THROTTLE_TIME_OVERFLOW_MS = 1000.0;
    private static final double THROTTLE_TIME_INCREMENT_MS = 100.0;
    private static final double NETWORK_THREAD_DENY_THRESHOLD = 0.50;  // 50% wait
    private static final double NETWORK_THREAD_TIMEOUT_THRESHOLD = 0.25;  // 25% wait

    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final ScheduledExecutorService scheduler;

    // Layer 1 Conduits
    private final Conduit<Gauge, Gauges.Sign> gauges;
    private final Conduit<Counter, Counters.Sign> counters;
    private final Conduit<Probe, Probes.Sign> probes;
    private final Conduit<Resource, Resources.Sign> resources;

    // Instruments - Throttle time per request type
    private final Map<String, Gauge> throttleTimeGauges = new ConcurrentHashMap<>();

    // Instruments - Quota violations per client (RAW counters, no assessment)
    private final Map<String, Counter> quotaCounters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> quotaGauges = new ConcurrentHashMap<>();

    // Instruments - SSL/SASL probes
    private final Probe sslProbe;
    private final Probe saslProbe;

    // Instruments - Network thread resource
    private final Resource networkThreadResource;

    // Previous values for delta calculation
    private final Map<String, Long> previousSslFailures = new ConcurrentHashMap<>();

    /**
     * Creates a NetworkMetricsObserver (Layer 1 - OBSERVE).
     *
     * @param mbsc    MBeanServerConnection to Kafka broker JMX
     * @param circuit Circuit for Substrates infrastructure
     * @throws NullPointerException if any parameter is null
     */
    public NetworkMetricsObserver(
        MBeanServerConnection mbsc,
        Circuit circuit
    ) {
        this.mbsc = Objects.requireNonNull(mbsc, "mbsc cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create Layer 1 conduits
        this.gauges = circuit.conduit(cortex().name("gauges"), Gauges::composer);
        this.counters = circuit.conduit(cortex().name("counters"), Counters::composer);
        this.probes = circuit.conduit(cortex().name("probes"), Probes::composer);
        this.resources = circuit.conduit(cortex().name("resources"), Resources::composer);

        // Initialize request type throttle gauges
        for (String reqType : new String[]{"Produce", "Fetch", "Metadata", "OffsetCommit"}) {
            throttleTimeGauges.put(reqType,
                gauges.percept(cortex().name("network.throttle." + reqType.toLowerCase())));
        }

        // Initialize SSL/SASL probes
        this.sslProbe = probes.percept(cortex().name("network.ssl.handshake"));
        this.saslProbe = probes.percept(cortex().name("network.sasl.auth"));

        // Initialize network thread resource
        this.networkThreadResource = resources.percept(cortex().name("network.threads"));

        // Schedule polling every 10 seconds
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "network-metrics-receptor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollNetworkMetrics, 0, 10, TimeUnit.SECONDS);

        logger.info("NetworkMetricsObserver started (Layer 1 - OBSERVE, polling every 10 seconds)");
    }

    /**
     * Polls all network metrics and emits RAW signals (NO interpretation).
     */
    private void pollNetworkMetrics() {
        try {
            pollThrottleTime();
            pollQuotaUsage();
            pollSslMetrics();
            pollSaslMetrics();
            pollNetworkThreadWait();

        } catch (Exception e) {
            logger.error("Network metrics polling failed", e);
        }
    }

    /**
     * Polls request throttle time metrics per request type.
     * Emits Gauges signals based on OBSERVED values only (NO interpretation).
     */
    private void pollThrottleTime() {
        for (Map.Entry<String, Gauge> entry : throttleTimeGauges.entrySet()) {
            String requestType = entry.getKey();
            Gauge gauge = entry.getValue();

            try {
                ObjectName mbean = new ObjectName(
                    "kafka.network:type=RequestMetrics,name=ThrottleTimeMs,request=" + requestType);

                Double mean = (Double) mbsc.getAttribute(mbean, "Mean");
                Double max = (Double) mbsc.getAttribute(mbean, "Max");

                // Emit signal based on OBSERVED value (threshold for signal selection, NOT interpretation)
                if (max > THROTTLE_TIME_OVERFLOW_MS) {
                    gauge.overflow();  // Just reporting what we see
                } else if (mean > THROTTLE_TIME_INCREMENT_MS) {
                    gauge.increment();  // Just reporting what we see
                } else {
                    gauge.decrement();  // Just reporting what we see
                }

            } catch (InstanceNotFoundException e) {
                logger.debug("Throttle metric not found for {}", requestType);
            } catch (Exception e) {
                logger.error("Failed to poll throttle time for {}: {}", requestType, e.getMessage());
            }
        }
    }

    /**
     * Polls quota usage metrics per client.
     * Emits RAW signals only - NO assessment, NO "degraded" calls.
     * Layer 2 Monitors will subscribe and interpret these signals.
     */
    private void pollQuotaUsage() {
        try {
            // Query all client quota managers
            ObjectName pattern = new ObjectName("kafka.server:type=ClientQuotaManager,*");
            Set<ObjectName> mbeans = mbsc.queryNames(pattern, null);

            for (ObjectName mbean : mbeans) {
                try {
                    // Extract client-id from MBean name
                    String clientId = mbean.getKeyProperty("client-id");
                    if (clientId == null) continue;

                    // Get throttle rate (percentage of quota used)
                    Double quotaPct = null;
                    try {
                        quotaPct = (Double) mbsc.getAttribute(mbean, "quota-percentage");
                    } catch (Exception e) {
                        // Try alternative attribute name
                        try {
                            Double throttleTime = (Double) mbsc.getAttribute(mbean, "throttle-time");
                            if (throttleTime > 0) {
                                quotaPct = 110.0;  // Indicate observed throttling
                            }
                        } catch (Exception e2) {
                            logger.debug("Quota attributes not available for {}: {}", clientId, e2.getMessage());
                            continue;
                        }
                    }

                    if (quotaPct == null) continue;

                    // Get or create instruments for this client (Layer 1 instruments only)
                    Gauge quotaGauge = quotaGauges.computeIfAbsent(clientId,
                        id -> gauges.percept(cortex().name("network.quota.usage." + id)));

                    Counter quotaCounter = quotaCounters.computeIfAbsent(clientId,
                        id -> counters.percept(cortex().name("network.quota.violations." + id)));

                    // Emit RAW signals based on OBSERVED values (NO interpretation!)
                    if (quotaPct > 100.0) {
                        quotaGauge.overflow();  // Observed: quota exceeded
                        quotaCounter.increment();  // Count violations
                    } else if (quotaPct > 90.0) {
                        quotaGauge.increment();  // Observed: approaching quota
                    } else {
                        quotaGauge.decrement();  // Observed: normal usage
                    }

                    // NOTE: NO Monitor.degraded() call here! That's Layer 2 responsibility.
                    // Layer 2 Monitors will subscribe to these Gauge signals and decide on conditions.

                } catch (Exception e) {
                    logger.debug("Failed to process quota metrics for MBean {}: {}", mbean, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to poll quota usage: {}", e.getMessage());
        }
    }

    /**
     * Polls SSL handshake failure metrics.
     * Emits Probes signals for OBSERVED failures.
     */
    private void pollSslMetrics() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.network:type=SocketServer,name=failed-authentication-total");

            Long failedAuth = (Long) mbsc.getAttribute(mbean, "Count");

            // Calculate delta from previous value
            Long previousFailed = previousSslFailures.get("ssl");
            if (previousFailed != null && failedAuth > previousFailed) {
                // New SSL failures OBSERVED
                long delta = failedAuth - previousFailed;
                for (int i = 0; i < Math.min(delta, 10); i++) {
                    sslProbe.failed();  // Report observed failure
                }
                logger.debug("SSL authentication failures observed: {} new failures", delta);
            } else if (previousFailed != null) {
                // No new failures observed
                sslProbe.succeeded();
            }

            previousSslFailures.put("ssl", failedAuth);

        } catch (InstanceNotFoundException e) {
            logger.debug("SSL metrics not available (SSL may not be enabled)");
        } catch (Exception e) {
            logger.error("Failed to poll SSL metrics: {}", e.getMessage());
        }
    }

    /**
     * Polls SASL authentication metrics.
     * Emits Probes signals for OBSERVED authentication results.
     */
    private void pollSaslMetrics() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.server:type=BrokerTopicMetrics,name=FailedAuthenticationRate");

            Double failureRate = (Double) mbsc.getAttribute(mbean, "OneMinuteRate");

            // Report OBSERVED authentication results
            if (failureRate > 0.0) {
                saslProbe.failed();  // Observed failures
                logger.debug("SASL authentication failure rate observed: {}/sec", failureRate);
            } else {
                saslProbe.succeeded();  // Observed successes
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("SASL metrics not available (SASL may not be enabled)");
        } catch (Exception e) {
            logger.error("Failed to poll SASL metrics: {}", e.getMessage());
        }
    }

    /**
     * Polls network thread wait time percentage.
     * Emits Resources signals based on OBSERVED wait times.
     */
    private void pollNetworkThreadWait() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.network:type=SocketServer,name=NetworkProcessorAvgWaitPercent");

            Double waitPercent = (Double) mbsc.getAttribute(mbean, "Value");

            // Emit signal based on OBSERVED wait time (threshold for signal selection)
            if (waitPercent > NETWORK_THREAD_DENY_THRESHOLD) {
                networkThreadResource.deny();  // Observed high wait
            } else if (waitPercent > NETWORK_THREAD_TIMEOUT_THRESHOLD) {
                networkThreadResource.timeout();  // Observed moderate wait
            } else {
                networkThreadResource.grant();  // Observed low wait
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("Network thread wait metric not available");
        } catch (Exception e) {
            logger.error("Failed to poll network thread wait: {}", e.getMessage());
        }
    }

    /**
     * Get gauges conduit for Layer 2 components to subscribe to.
     *
     * @return Gauges conduit
     */
    public Conduit<Gauge, Gauges.Sign> gauges() {
        return gauges;
    }

    /**
     * Get counters conduit for Layer 2 components to subscribe to.
     *
     * @return Counters conduit
     */
    public Conduit<Counter, Counters.Sign> counters() {
        return counters;
    }

    /**
     * Get probes conduit for Layer 2 components to subscribe to.
     *
     * @return Probes conduit
     */
    public Conduit<Probe, Probes.Sign> probes() {
        return probes;
    }

    /**
     * Get resources conduit for Layer 2 components to subscribe to.
     *
     * @return Resources conduit
     */
    public Conduit<Resource, Resources.Sign> resources() {
        return resources;
    }

    @Override
    public void close() {
        logger.info("Shutting down NetworkMetricsObserver...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("NetworkMetricsObserver stopped");
    }
}
