package io.fullerstack.kafka.demo;

/**
 * JMX MBean interface for controlling Kafka producer at runtime.
 * <p>
 * Allows external applications to dynamically adjust producer behavior
 * without restart.
 */
public interface ProducerControlMBean {

    /**
     * Set the producer message rate (messages per second).
     *
     * @param messagesPerSecond target rate (1-100000)
     */
    void setRate(int messagesPerSecond);

    /**
     * Get the current producer message rate.
     *
     * @return current rate in messages per second
     */
    int getRate();

    /**
     * Get the total number of messages sent since startup.
     *
     * @return total message count
     */
    long getMessagesSent();

    /**
     * Get producer uptime in seconds.
     *
     * @return seconds since startup
     */
    long getUptimeSeconds();
}
