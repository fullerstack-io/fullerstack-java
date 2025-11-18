package io.fullerstack.kafka.demo;

/**
 * Demo configuration
 *
 * @param kafkaBootstrap Kafka bootstrap servers (e.g., "localhost:9092,localhost:9093,localhost:9094")
 * @param jmxUrl JMX URL for producer metrics (e.g., "localhost:11001")
 * @param mode Demo mode (FULL, PRODUCER, CONSUMER, BROKER)
 */
public record DemoConfig(
    String kafkaBootstrap,
    String jmxUrl,
    DemoMode mode
) {

    public static DemoConfig fromArgs(String... args) {
        String kafkaBootstrap = System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP",
            "localhost:9092,localhost:9093,localhost:9094"
        );

        String jmxUrl = System.getenv().getOrDefault(
            "JMX_URL",
            "localhost:11001"
        );

        String modeStr = System.getenv().getOrDefault(
            "DEMO_MODE",
            "FULL"
        );

        DemoMode mode = DemoMode.valueOf(modeStr.toUpperCase());

        return new DemoConfig(kafkaBootstrap, jmxUrl, mode);
    }
}
