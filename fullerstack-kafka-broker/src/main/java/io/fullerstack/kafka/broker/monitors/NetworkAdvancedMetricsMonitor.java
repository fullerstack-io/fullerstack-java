package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
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

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static io.humainary.substrates.ext.serventis.ext.Monitors.Dimension.*;

/**
 * Emits Serventis signals for advanced network and security metrics (RC7).
 *
 * <p><b>Layer 2: OBSERVE + ORIENT Phase</b>
 * This monitor implements 5 advanced network/security metrics:
 * <ul>
 *   <li><b>Request Throttle Time</b> - Gauges per request type (Produce, Fetch, etc.)</li>
 *   <li><b>Quota Violations</b> - Counters + Monitors per client</li>
 *   <li><b>SSL Handshake Failures</b> - Probes (CONNECT/FAIL)</li>
 *   <li><b>SASL Authentication</b> - Probes (AUTHENTICATE/FAIL/SUCCESS)</li>
 *   <li><b>Network Thread Wait Time</b> - Resources (GRANT/DENY)</li>
 * </ul>
 *
 * <p><b>Thresholds:</b>
 * <pre>
 * Throttle Time OVERFLOW: >1000ms
 * Quota Violation: usage >100%
 * Network Thread DENY: wait >50%
 * </pre>
 *
 * <p><b>JMX MBeans:</b>
 * <ul>
 *   <li>kafka.network:type=RequestMetrics,name=ThrottleTimeMs,request=*</li>
 *   <li>kafka.server:type=ClientQuotaManager,user=*,client-id=*</li>
 *   <li>kafka.network:type=SocketServer,name=failed-authentication-total</li>
 *   <li>kafka.server:type=BrokerTopicMetrics,name=FailedAuthenticationRate</li>
 *   <li>kafka.network:type=SocketServer,name=NetworkProcessorAvgWaitPercent</li>
 * </ul>
 */
public class NetworkAdvancedMetricsMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NetworkAdvancedMetricsMonitor.class);

    // Thresholds
    private static final double THROTTLE_TIME_OVERFLOW_MS = 1000.0;
    private static final double THROTTLE_TIME_WARNING_MS = 100.0;
    private static final double NETWORK_THREAD_DENY_THRESHOLD = 0.50;  // 50% wait
    private static final double NETWORK_THREAD_TIMEOUT_THRESHOLD = 0.25;  // 25% wait

    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final ScheduledExecutorService scheduler;

    // Instruments - Throttle time per request type
    private final Map<String, Gauge> throttleTimeGauges = new ConcurrentHashMap<>();

    // Instruments - Quota violations per client
    private final Map<String, Counter> quotaViolationCounters = new ConcurrentHashMap<>();
    private final Map<String, Monitor> quotaMonitors = new ConcurrentHashMap<>();

    // Instruments - SSL/SASL probes
    private final Probe sslProbe;
    private final Probe saslProbe;

    // Instruments - Network thread resource
    private final Resource networkThreadResource;

    // Previous values for delta calculation
    private final Map<String, Long> previousSslFailures = new ConcurrentHashMap<>();

    /**
     * Creates a NetworkAdvancedMetricsMonitor.
     *
     * @param mbsc    MBeanServerConnection to Kafka broker JMX
     * @param circuit Circuit for Substrates infrastructure
     * @throws NullPointerException if any parameter is null
     */
    public NetworkAdvancedMetricsMonitor(
        MBeanServerConnection mbsc,
        Circuit circuit
    ) {
        this.mbsc = Objects.requireNonNull(mbsc, "mbsc cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create conduits - get channels from circuit
        var gaugesChannel = circuit.conduit(cortex().name("gauges"), Gauges::composer);
        var countersChannel = circuit.conduit(cortex().name("counters"), Counters::composer);
        var monitorsChannel = circuit.conduit(cortex().name("monitors"), Monitors::composer);
        var probesChannel = circuit.conduit(cortex().name("probes"), Probes::composer);
        var resourcesChannel = circuit.conduit(cortex().name("resources"), Resources::composer);

        // Initialize request type throttle gauges
        for (String reqType : new String[]{"Produce", "Fetch", "Metadata", "OffsetCommit"}) {
            throttleTimeGauges.put(reqType,
                gaugesChannel.get(cortex().name("throttle." + reqType.toLowerCase())));
        }

        // Initialize SSL/SASL probes
        this.sslProbe = probesChannel.get(cortex().name("ssl.handshake"));
        this.saslProbe = probesChannel.get(cortex().name("sasl.auth"));

        // Initialize network thread resource
        this.networkThreadResource = resourcesChannel.get(cortex().name("network.threads"));

        // Schedule polling every 10 seconds
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "network-advanced-metrics-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollNetworkMetrics, 0, 10, TimeUnit.SECONDS);

        logger.info("NetworkAdvancedMetricsMonitor started (polling every 10 seconds)");
    }

    /**
     * Polls all advanced network metrics and emits signals.
     */
    private void pollNetworkMetrics() {
        try {
            pollThrottleTime();
            pollQuotaViolations();
            pollSslMetrics();
            pollSaslMetrics();
            pollNetworkThreadWait();

        } catch (Exception e) {
            logger.error("Advanced network metrics polling failed", e);
        }
    }

    /**
     * Polls request throttle time metrics per request type.
     * Emits Gauges signals: INCREMENT (normal), OVERFLOW (>1000ms).
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

                if (max > THROTTLE_TIME_OVERFLOW_MS) {
                    // Excessive throttling - critical
                    gauge.overflow();
                    logger.warn("High throttle time for {}: max={}ms", requestType, max);

                } else if (mean > THROTTLE_TIME_WARNING_MS) {
                    // Moderate throttling - warning
                    gauge.increment();

                } else {
                    // Normal operation
                    gauge.decrement();
                }

            } catch (InstanceNotFoundException e) {
                logger.debug("Throttle metric not found for {}", requestType);
            } catch (Exception e) {
                logger.error("Failed to poll throttle time for {}: {}", requestType, e.getMessage());
            }
        }
    }

    /**
     * Polls quota violation metrics per client.
     * Emits Counters signals (INCREMENT on violation) and Monitors signals (DEGRADED when >100% quota).
     */
    private void pollQuotaViolations() {
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
                    // Note: JMX attributes vary by Kafka version - handle gracefully
                    Double quotaPct = null;
                    try {
                        quotaPct = (Double) mbsc.getAttribute(mbean, "quota-percentage");
                    } catch (Exception e) {
                        // Try alternative attribute name
                        try {
                            Double throttleTime = (Double) mbsc.getAttribute(mbean, "throttle-time");
                            // If throttle time exists, client is being throttled (quota exceeded)
                            if (throttleTime > 0) {
                                quotaPct = 110.0;  // Indicate violation
                            }
                        } catch (Exception e2) {
                            logger.debug("Quota attributes not available for {}: {}", clientId, e2.getMessage());
                            continue;
                        }
                    }

                    if (quotaPct == null) continue;

                    // Get or create instruments for this client
                    Counter violationCounter = quotaViolationCounters.computeIfAbsent(clientId,
                        id -> circuit.conduit(cortex().name("counters"), Counters::composer)
                            .get(cortex().name("quota.violations." + id)));

                    Monitor quotaMonitor = quotaMonitors.computeIfAbsent(clientId,
                        id -> circuit.conduit(cortex().name("monitors"), Monitors::composer)
                            .get(cortex().name("quota.health." + id)));

                    // Emit signals based on quota usage
                    if (quotaPct > 100.0) {
                        // Quota violation - increment counter and mark degraded
                        violationCounter.increment();
                        quotaMonitor.degraded(CONFIRMED);
                        logger.warn("Quota violation for client {}: {}%", clientId, quotaPct);

                    } else if (quotaPct > 90.0) {
                        // Approaching quota - warn via monitor
                        quotaMonitor.diverging(MEASURED);

                    } else {
                        // Normal operation
                        quotaMonitor.stable(CONFIRMED);
                    }

                } catch (Exception e) {
                    logger.debug("Failed to process quota metrics for MBean {}: {}", mbean, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to poll quota violations: {}", e.getMessage());
        }
    }

    /**
     * Polls SSL handshake failure metrics.
     * Emits Probes signals: OPERATION(CONNECT, CLIENT, FAIL).
     */
    private void pollSslMetrics() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.network:type=SocketServer,name=failed-authentication-total");

            Long failedAuth = (Long) mbsc.getAttribute(mbean, "Count");

            // Calculate delta from previous value
            Long previousFailed = previousSslFailures.get("ssl");
            if (previousFailed != null && failedAuth > previousFailed) {
                // New SSL failures detected
                long delta = failedAuth - previousFailed;
                for (int i = 0; i < Math.min(delta, 10); i++) {
                    sslProbe.failed();
                }
                logger.warn("SSL authentication failures detected: {} new failures (total: {})", delta, failedAuth);
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
     * Emits Probes signals: OPERATION(AUTHENTICATE, CLIENT, FAIL/SUCCESS).
     */
    private void pollSaslMetrics() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.server:type=BrokerTopicMetrics,name=FailedAuthenticationRate");

            Double failureRate = (Double) mbsc.getAttribute(mbean, "OneMinuteRate");

            if (failureRate > 0.0) {
                // SASL authentication failures detected
                saslProbe.failed();
                logger.warn("SASL authentication failure rate: {}/sec", failureRate);

            } else {
                // No failures - emit success signal
                saslProbe.succeeded();
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("SASL metrics not available (SASL may not be enabled)");
        } catch (Exception e) {
            logger.error("Failed to poll SASL metrics: {}", e.getMessage());
        }
    }

    /**
     * Polls network thread wait time percentage.
     * Emits Resources signals: GRANT (low wait), TIMEOUT (moderate), DENY (high wait = contention).
     */
    private void pollNetworkThreadWait() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.network:type=SocketServer,name=NetworkProcessorAvgWaitPercent");

            Double waitPercent = (Double) mbsc.getAttribute(mbean, "Value");

            if (waitPercent > NETWORK_THREAD_DENY_THRESHOLD) {
                // High contention - threads waiting a lot (>50%)
                networkThreadResource.deny();
                logger.warn("High network thread wait: {}%", waitPercent);

            } else if (waitPercent > NETWORK_THREAD_TIMEOUT_THRESHOLD) {
                // Moderate contention (>25%)
                networkThreadResource.timeout();

            } else {
                // Normal operation - threads available
                networkThreadResource.grant();
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("Network thread wait metric not available");
        } catch (Exception e) {
            logger.error("Failed to poll network thread wait: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down NetworkAdvancedMetricsMonitor...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("NetworkAdvancedMetricsMonitor stopped");
    }
}
