package io.fullerstack.kafka.demo;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple Kafka producer that generates test messages for observability demo.
 * <p>
 * Creates a producer with client-id "producer-1" and continuously sends messages
 * to a test topic. This generates JMX metrics that the ProducerBufferMonitor can observe.
 */
public class SimpleKafkaProducer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SimpleKafkaProducer.class);

    private final KafkaProducer<String, String> producer;
    private final ScheduledExecutorService scheduler;
    private final String topic;
    private volatile boolean running = false;
    private long messageCount = 0;

    public SimpleKafkaProducer(String bootstrapServers, String clientId, String topic) {
        this.topic = topic;

        // Configure producer
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Observability-friendly settings
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");  // Small batches
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");  // 16KB batches
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");  // 32MB buffer

        // Enable JMX metrics
        props.put(ProducerConfig.METRICS_RECORDING_LEVEL_CONFIG, "DEBUG");

        this.producer = new KafkaProducer<>(props);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-producer-" + clientId);
            t.setDaemon(true);
            return t;
        });

        logger.info("Created Kafka producer: clientId={}, topic={}", clientId, topic);
    }

    /**
     * Starts sending messages at the specified rate.
     *
     * @param messagesPerSecond number of messages to send per second
     */
    public void start(int messagesPerSecond) {
        if (running) {
            logger.warn("Producer is already running");
            return;
        }

        running = true;
        long periodMs = 1000 / messagesPerSecond;

        scheduler.scheduleAtFixedRate(
            this::sendMessage,
            0,
            periodMs,
            TimeUnit.MILLISECONDS
        );

        logger.info("Started producer: sending {} messages/second to topic '{}'", messagesPerSecond, topic);
    }

    private void sendMessage() {
        if (!running) {
            return;
        }

        try {
            String key = "key-" + messageCount;
            String value = "Test message " + messageCount + " at " + System.currentTimeMillis();

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    logger.error("Failed to send message {}", messageCount, exception);
                } else {
                    if (messageCount % 100 == 0) {
                        logger.debug("Sent message {} to partition {} offset {}",
                            messageCount, metadata.partition(), metadata.offset());
                    }
                }
            });

            messageCount++;

        } catch (Exception e) {
            logger.error("Error sending message", e);
        }
    }

    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        producer.flush();
        logger.info("Stopped producer: sent {} total messages", messageCount);
    }

    @Override
    public void close() {
        stop();
        producer.close();
        logger.info("Closed producer");
    }

    public long getMessageCount() {
        return messageCount;
    }
}
