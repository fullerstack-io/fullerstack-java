package io.fullerstack.kafka.broker.models;

/**
 * Enum representing the different types of thread pools in a Kafka broker.
 *
 * <p>Kafka brokers use multiple thread pools that can become exhausted under
 * heavy load. Monitoring these pools enables detection of resource saturation
 * before complete broker failure.
 *
 * @see ThreadPoolMetrics
 */
public enum ThreadPoolType {
    /**
     * Network threads handle network I/O operations (accept connections, read requests, write responses).
     * Configured via {@code num.network.threads} broker property (default: 3).
     * <p>
     * JMX MBean: {@code kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent}
     */
    NETWORK("Network Threads"),

    /**
     * I/O threads process requests (produce, fetch, metadata) from request queue.
     * Configured via {@code num.io.threads} broker property (default: 8).
     * <p>
     * JMX MBean: {@code kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent}
     */
    IO("I/O Threads"),

    /**
     * Log cleaner threads compact log segments (only if log compaction enabled).
     * <p>
     * JMX MBean: {@code kafka.log:type=LogCleaner,name=cleaner-recopy-percent}
     */
    LOG_CLEANER("Log Cleaner Threads");

    private final String displayName;

    ThreadPoolType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this thread pool type.
     *
     * @return display name (e.g., "network", "io", "log-cleaner")
     */
    public String displayName() {
        return displayName;
    }
}
