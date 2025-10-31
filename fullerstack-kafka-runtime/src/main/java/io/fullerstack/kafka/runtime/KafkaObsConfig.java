package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.kafka.core.config.ConsumerSensorConfig;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;

import java.util.Objects;

/**
 * Configuration for KafkaObservabilityRuntime.
 * <p>
 * Aggregates all sensor configurations needed to monitor a Kafka cluster.
 * <p>
 * <b>RC1 Note:</b> Health thresholds are now hardcoded in Composers per RC1 philosophy
 * (simple, opinionated defaults). No longer configurable.
 *
 * @param clusterName           Unique cluster identifier (e.g., "production", "staging")
 * @param brokerSensorConfig    Configuration for broker monitoring
 * @param producerSensorConfig  Configuration for producer monitoring
 * @param consumerSensorConfig  Configuration for consumer monitoring
 */
public record KafkaObsConfig (
  String clusterName,
  BrokerSensorConfig brokerSensorConfig,
  ProducerSensorConfig producerSensorConfig,
  ConsumerSensorConfig consumerSensorConfig
) {
  /**
   * Compact constructor with validation.
   */
  public KafkaObsConfig {
    Objects.requireNonNull ( clusterName, "clusterName cannot be null" );
    Objects.requireNonNull ( brokerSensorConfig, "brokerSensorConfig cannot be null" );
    Objects.requireNonNull ( producerSensorConfig, "producerSensorConfig cannot be null" );
    Objects.requireNonNull ( consumerSensorConfig, "consumerSensorConfig cannot be null" );

    if ( clusterName.isBlank () ) {
      throw new IllegalArgumentException ( "clusterName cannot be blank" );
    }
  }

  /**
   * Create config.
   *
   * @param clusterName    Cluster name
   * @param brokerConfig   Broker sensor config
   * @param producerConfig Producer sensor config
   * @param consumerConfig Consumer sensor config
   * @return KafkaObsConfig instance
   */
  public static KafkaObsConfig of (
    final String clusterName,
    final BrokerSensorConfig brokerConfig,
    final ProducerSensorConfig producerConfig,
    final ConsumerSensorConfig consumerConfig
  ) {
    return new KafkaObsConfig ( clusterName, brokerConfig, producerConfig, consumerConfig );
  }
}
