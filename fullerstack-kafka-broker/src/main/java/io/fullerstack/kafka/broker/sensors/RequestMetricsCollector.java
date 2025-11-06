package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.RequestMetrics;
import io.fullerstack.kafka.broker.models.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects request metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose request metrics via JMX MBeans in the pattern:
 * <pre>
 * kafka.network:type=RequestMetrics,name={MetricName},request={RequestType}
 * </pre>
 *
 * <p><b>Metrics Collected:</b>
 * <ul>
 *   <li><b>RequestsPerSec</b> - Request rate (Count attribute)</li>
 *   <li><b>TotalTimeMs</b> - Average total request time (Mean attribute)</li>
 *   <li><b>RequestQueueTimeMs</b> - Average time in request queue (Mean)</li>
 *   <li><b>ResponseQueueTimeMs</b> - Average time in response queue (Mean)</li>
 *   <li><b>ErrorsPerSec</b> - Error rate (Count attribute)</li>
 * </ul>
 *
 * <p><b>Request Types:</b>
 * <ul>
 *   <li>Produce - Producer write requests</li>
 *   <li>Fetch - Consumer/follower read requests</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * RequestMetricsCollector collector = new RequestMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool,
 *     100.0  // 100ms SLA threshold
 * );
 *
 * List<RequestMetrics> metrics = collector.collect("broker-1");
 * for (RequestMetrics m : metrics) {
 *     if (m.isSlaViolation()) {
 *         alert("SLA violation: " + m.requestType());
 *     }
 * }
 * }</pre>
 *
 * @see RequestMetrics
 * @see RequestType
 * @see JmxConnectionPool
 */
public class RequestMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(RequestMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;
    private final double slaThresholdMs;

    /**
     * Creates a new request metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @param slaThresholdMs SLA threshold for request latency (milliseconds)
     * @throws NullPointerException     if jmxUrl or connectionPool is null
     * @throws IllegalArgumentException if slaThresholdMs is negative
     */
    public RequestMetricsCollector(
        String jmxUrl,
        JmxConnectionPool connectionPool,
        double slaThresholdMs
    ) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
        if (slaThresholdMs < 0) {
            throw new IllegalArgumentException("slaThresholdMs must be >= 0, got: " + slaThresholdMs);
        }
        this.slaThresholdMs = slaThresholdMs;
    }

    /**
     * Collects metrics for all monitored request types.
     * <p>
     * Returns a list of RequestMetrics, one for each request type that is available
     * (typically PRODUCE and FETCH).
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return list of request metrics (1-2 items depending on availability)
     * @throws Exception if JMX connection or MBean query fails
     */
    public List<RequestMetrics> collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        List<RequestMetrics> metrics = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // Collect metrics for each request type
        for (RequestType type : List.of(RequestType.PRODUCE, RequestType.FETCH)) {
            try {
                RequestMetrics metric = collectRequestType(mbsc, brokerId, type, timestamp);
                metrics.add(metric);
            } catch (Exception e) {
                logger.debug("Failed to collect {} request metrics for {}: {}",
                    type.name(), brokerId, e.getMessage());
                // Continue with other request types
            }
        }

        return metrics;
    }

    /**
     * Collects metrics for a specific request type.
     */
    private RequestMetrics collectRequestType(
        MBeanServerConnection mbsc,
        String brokerId,
        RequestType type,
        long timestamp
    ) throws Exception {
        String requestName = type.getJmxRequestName();

        // Collect all metrics for this request type
        double requestsPerSec = getRequestRate(mbsc, requestName);
        double totalTimeMs = getTotalTime(mbsc, requestName);
        double requestQueueTimeMs = getRequestQueueTime(mbsc, requestName);
        double responseQueueTimeMs = getResponseQueueTime(mbsc, requestName);
        double errorsPerSec = getErrorRate(mbsc, requestName);

        return new RequestMetrics(
            brokerId,
            type,
            requestsPerSec,
            totalTimeMs,
            requestQueueTimeMs,
            responseQueueTimeMs,
            errorsPerSec,
            slaThresholdMs,
            timestamp
        );
    }

    /**
     * Gets request rate (requests/sec) for a request type.
     */
    private double getRequestRate(MBeanServerConnection mbsc, String requestName) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.network:type=RequestMetrics,name=RequestsPerSec,request=" + requestName
        );

        // Try Count attribute (total count)
        try {
            Number count = (Number) mbsc.getAttribute(name, "Count");
            return count.doubleValue();
        } catch (Exception e) {
            logger.debug("Count attribute not available for {}, trying OneMinuteRate", requestName);
            // Fallback to rate metric
            Number rate = (Number) mbsc.getAttribute(name, "OneMinuteRate");
            return rate.doubleValue();
        }
    }

    /**
     * Gets average total time (ms) for a request type.
     */
    private double getTotalTime(MBeanServerConnection mbsc, String requestName) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=" + requestName
        );
        Number mean = (Number) mbsc.getAttribute(name, "Mean");
        return mean.doubleValue();
    }

    /**
     * Gets average request queue time (ms) for a request type.
     */
    private double getRequestQueueTime(MBeanServerConnection mbsc, String requestName) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.network:type=RequestMetrics,name=RequestQueueTimeMs,request=" + requestName
        );
        Number mean = (Number) mbsc.getAttribute(name, "Mean");
        return mean.doubleValue();
    }

    /**
     * Gets average response queue time (ms) for a request type.
     */
    private double getResponseQueueTime(MBeanServerConnection mbsc, String requestName) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.network:type=RequestMetrics,name=ResponseQueueTimeMs,request=" + requestName
        );
        Number mean = (Number) mbsc.getAttribute(name, "Mean");
        return mean.doubleValue();
    }

    /**
     * Gets error rate (errors/sec) for a request type.
     */
    private double getErrorRate(MBeanServerConnection mbsc, String requestName) throws Exception {
        try {
            ObjectName name = new ObjectName(
                "kafka.network:type=RequestMetrics,name=ErrorsPerSec,request=" + requestName
            );
            Number count = (Number) mbsc.getAttribute(name, "Count");
            return count.doubleValue();
        } catch (Exception e) {
            // ErrorsPerSec may not exist if no errors - return 0
            logger.debug("ErrorsPerSec not available for {}, assuming 0", requestName);
            return 0.0;
        }
    }
}
