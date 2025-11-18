package io.fullerstack.kafka.demo;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Kafka producer for JMX monitoring demonstration.
 * <p>
 * This producer runs independently with JMX enabled, allowing external monitoring
 * applications to collect metrics via JMX.
 * <p>
 * <b>Usage:</b>
 * <pre>
 * # Run with JMX enabled:
 * java -Dcom.sun.management.jmxremote \
 *      -Dcom.sun.management.jmxremote.port=11001 \
 *      -Dcom.sun.management.jmxremote.authenticate=false \
 *      -Dcom.sun.management.jmxremote.ssl=false \
 *      -Dcom.sun.management.jmxremote.local.only=false \
 *      -cp target/fullerstack-kafka-demo-1.0.0-SNAPSHOT.jar \
 *      io.fullerstack.kafka.demo.StandaloneProducer
 * </pre>
 */
public class StandaloneProducer {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneProducer.class);

    public static void main(String[] args) {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
        String clientId = System.getenv().getOrDefault("CLIENT_ID", "producer-1");
        String topic = System.getenv().getOrDefault("TOPIC", "observability-demo-topic");
        int ratePerSecond = Integer.parseInt(System.getenv().getOrDefault("RATE", "10"));

        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║         Standalone Kafka Producer (JMX Enabled)                ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Bootstrap: {}", bootstrap);
        logger.info("Client ID: {}", clientId);
        logger.info("Topic: {}", topic);
        logger.info("Rate: {} msg/sec", ratePerSecond);
        logger.info("");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.METRICS_RECORDING_LEVEL_CONFIG, "DEBUG");

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        AtomicLong counter = new AtomicLong(0);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down producer...");
            producer.close();
            logger.info("Sent {} messages total", counter.get());
        }));

        logger.info("✅ Producer started - sending messages to '{}'", topic);
        logger.info("💡 Monitor via JMX on port specified in -Dcom.sun.management.jmxremote.port");
        logger.info("Press Ctrl+C to stop...");
        logger.info("");

        long sleepMs = 1000 / ratePerSecond;

        while (true) {
            try {
                long count = counter.incrementAndGet();
                String key = "key-" + count;
                String value = String.format("{\"id\":%d,\"timestamp\":%d,\"data\":\"message-%d\"}",
                    count, System.currentTimeMillis(), count);

                ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);

                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("Failed to send message {}: {}", count, exception.getMessage());
                    } else if (count % 100 == 0) {
                        logger.debug("Sent message {} to partition {} offset {}",
                            count, metadata.partition(), metadata.offset());
                    }
                });

                Thread.sleep(sleepMs);

            } catch (InterruptedException e) {
                logger.info("Producer interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error sending message: {}", e.getMessage());
            }
        }

        producer.close();
        logger.info("Producer stopped - sent {} messages", counter.get());
    }
}
