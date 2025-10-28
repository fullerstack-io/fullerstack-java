package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.kafka.core.config.ConsumerSensorConfig;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;
import io.fullerstack.serventis.config.HealthThresholds;

import java.util.Objects;

/**
 * Configuration for KafkaObservabilityRuntime.
 * <p>
 * Aggregates all sensor configurations needed to monitor a Kafka cluster.
 *
 * @param clusterName           Unique cluster identifier (e.g., "production", "staging")
 * @param brokerSensorConfig    Configuration for broker monitoring
 * @param producerSensorConfig  Configuration for producer monitoring
 * @param consumerSensorConfig  Configuration for consumer monitoring
 * @param healthThresholds      Health assessment thresholds for composers
 */
public record KafkaObsConfig(
        String clusterName,
        BrokerSensorConfig brokerSensorConfig,
        ProducerSensorConfig producerSensorConfig,
        ConsumerSensorConfig consumerSensorConfig,
        HealthThresholds healthThresholds
) {
    /**
     * Compact constructor with validation.
     */
    public KafkaObsConfig {
        Objects.requireNonNull(clusterName, "clusterName cannot be null");
        Objects.requireNonNull(brokerSensorConfig, "brokerSensorConfig cannot be null");
        Objects.requireNonNull(producerSensorConfig, "producerSensorConfig cannot be null");
        Objects.requireNonNull(consumerSensorConfig, "consumerSensorConfig cannot be null");
        Objects.requireNonNull(healthThresholds, "healthThresholds cannot be null");

        if (clusterName.isBlank()) {
            throw new IllegalArgumentException("clusterName cannot be blank");
        }
    }

    /**
     * Create config with default health thresholds.
     *
     * @param clusterName    Cluster name
     * @param brokerConfig   Broker sensor config
     * @param producerConfig Producer sensor config
     * @param consumerConfig Consumer sensor config
     * @return KafkaObsConfig instance with default thresholds
     */
    public static KafkaObsConfig of(
            String clusterName,
            BrokerSensorConfig brokerConfig,
            ProducerSensorConfig producerConfig,
            ConsumerSensorConfig consumerConfig
    ) {
        return new KafkaObsConfig(clusterName, brokerConfig, producerConfig, consumerConfig, HealthThresholds.withDefaults());
    }

    /**
     * Create config with custom health thresholds.
     *
     * @param clusterName      Cluster name
     * @param brokerConfig     Broker sensor config
     * @param producerConfig   Producer sensor config
     * @param consumerConfig   Consumer sensor config
     * @param healthThresholds Custom health thresholds
     * @return KafkaObsConfig instance
     */
    public static KafkaObsConfig of(
            String clusterName,
            BrokerSensorConfig brokerConfig,
            ProducerSensorConfig producerConfig,
            ConsumerSensorConfig consumerConfig,
            HealthThresholds healthThresholds
    ) {
        return new KafkaObsConfig(clusterName, brokerConfig, producerConfig, consumerConfig, healthThresholds);
    }
}
