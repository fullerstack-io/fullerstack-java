package io.fullerstack.kafka.core.actors;

/**
 * Manages Kafka producer and consumer configuration updates at runtime.
 *
 * <p>This interface abstracts dynamic configuration management for Kafka clients,
 * allowing Actors to adjust producer/consumer settings in response to operational
 * conditions (buffer overflow, lag, throughput issues).
 *
 * <h3>Implementation Notes:</h3>
 * <ul>
 *   <li>Config updates should be applied to running producers/consumers</li>
 *   <li>Some configs require client restart (e.g., bootstrap.servers)</li>
 *   <li>Other configs can be updated dynamically (e.g., linger.ms, batch.size)</li>
 *   <li>Implementation should track original values for restoration</li>
 *   <li>Thread-safe implementation required for concurrent Actor access</li>
 * </ul>
 *
 * <h3>Supported Producer Configs (Dynamic):</h3>
 * <ul>
 *   <li><b>max.in.flight.requests.per.connection</b>: Controls request pipelining (1-5)</li>
 *   <li><b>linger.ms</b>: Batching delay (0-100ms)</li>
 *   <li><b>batch.size</b>: Maximum batch size in bytes</li>
 *   <li><b>buffer.memory</b>: Total memory for buffering</li>
 *   <li><b>compression.type</b>: Compression algorithm (none, gzip, snappy, lz4, zstd)</li>
 * </ul>
 *
 * <h3>Supported Consumer Configs (Dynamic):</h3>
 * <ul>
 *   <li><b>fetch.min.bytes</b>: Minimum fetch size</li>
 *   <li><b>fetch.max.wait.ms</b>: Maximum fetch wait time</li>
 *   <li><b>max.poll.records</b>: Maximum records per poll</li>
 * </ul>
 *
 * <h3>Example Implementation:</h3>
 * <pre>{@code
 * public class KafkaConfigManagerImpl implements KafkaConfigManager {
 *     private final Map<String, KafkaProducer<?, ?>> producers;
 *     private final Map<String, Map<String, Object>> originalConfigs;
 *
 *     @Override
 *     public int getProducerConfig(String producerId, String configKey) {
 *         KafkaProducer<?, ?> producer = producers.get(producerId);
 *         if (producer == null) {
 *             throw new IllegalArgumentException("Unknown producer: " + producerId);
 *         }
 *
 *         // Access producer metrics to get current config value
 *         // (Kafka doesn't expose configs directly via API)
 *         Map<String, Object> configs = originalConfigs.get(producerId);
 *         return (int) configs.getOrDefault(configKey, 0);
 *     }
 *
 *     @Override
 *     public void updateProducerConfig(String producerId, String configKey, String configValue) {
 *         // Note: Most producer configs require creating a new producer instance
 *         // This is a limitation of Kafka's client API design
 *         // Alternative: Use Kafka AdminClient to update broker-side quotas
 *     }
 * }
 * }</pre>
 *
 * <h3>Design Considerations:</h3>
 * <p><b>IMPORTANT</b>: Kafka's producer/consumer APIs do NOT support true runtime
 * configuration updates. Most config changes require recreating the client instance.
 * Implementations should either:
 * <ol>
 *   <li>Recreate producers/consumers with new configs (may cause brief downtime)</li>
 *   <li>Use Kafka AdminClient to update broker-side quotas (preferred for throttling)</li>
 *   <li>Use JMX to update certain metrics (limited support)</li>
 * </ol>
 *
 * <p>For ThrottleActor use case, consider using Kafka quotas via AdminClient instead
 * of updating producer configs directly.
 *
 * @see ThrottleActor
 * @since 1.0.0
 */
public interface KafkaConfigManager {

    /**
     * Gets current producer configuration value.
     *
     * @param producerId  Producer identifier (e.g., "producer-1")
     * @param configKey   Configuration key (e.g., "max.in.flight.requests.per.connection")
     * @return Current configuration value as integer
     * @throws IllegalArgumentException if producerId is unknown
     * @throws IllegalArgumentException if configKey is not numeric
     */
    int getProducerConfig(String producerId, String configKey);

    /**
     * Updates producer configuration value.
     *
     * <p><b>Important</b>: Depending on the configuration key, this may require
     * recreating the producer instance. The implementation should handle this
     * transparently, ensuring minimal disruption to ongoing operations.
     *
     * @param producerId   Producer identifier (e.g., "producer-1")
     * @param configKey    Configuration key (e.g., "max.in.flight.requests.per.connection")
     * @param configValue  New configuration value as string (will be converted to appropriate type)
     * @throws java.lang.Exception if update fails (producer not found, invalid config, etc.)
     */
    void updateProducerConfig(String producerId, String configKey, String configValue) throws java.lang.Exception;

    /**
     * Gets current consumer configuration value.
     *
     * @param consumerId  Consumer identifier (e.g., "consumer-1")
     * @param configKey   Configuration key (e.g., "max.poll.records")
     * @return Current configuration value as integer
     * @throws IllegalArgumentException if consumerId is unknown
     * @throws IllegalArgumentException if configKey is not numeric
     */
    int getConsumerConfig(String consumerId, String configKey);

    /**
     * Updates consumer configuration value.
     *
     * <p><b>Important</b>: Depending on the configuration key, this may require
     * recreating the consumer instance. The implementation should handle this
     * transparently, ensuring minimal disruption to ongoing operations.
     *
     * @param consumerId   Consumer identifier (e.g., "consumer-1")
     * @param configKey    Configuration key (e.g., "max.poll.records")
     * @param configValue  New configuration value as string (will be converted to appropriate type)
     * @throws java.lang.Exception if update fails (consumer not found, invalid config, etc.)
     */
    void updateConsumerConfig(String consumerId, String configKey, String configValue) throws java.lang.Exception;
}
