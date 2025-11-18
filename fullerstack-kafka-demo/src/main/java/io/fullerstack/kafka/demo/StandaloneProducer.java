package io.fullerstack.kafka.demo;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Kafka producer with dynamic JMX control.
 * <p>
 * Exposes ProducerControlMBean for runtime rate adjustment without restart.
 * <p>
 * <b>Usage:</b>
 * <pre>
 * # Run with JMX enabled:
 * java -Dcom.sun.management.jmxremote \
 *      -Dcom.sun.management.jmxremote.port=11001 \
 *      -Dcom.sun.management.jmxremote.authenticate=false \
 *      -Dcom.sun.management.jmxremote.ssl=false \
 *      -Dcom.sun.management.jmxremote.rmi.port=11001 \
 *      -cp target/kafka-observability-demo.jar \
 *      io.fullerstack.kafka.demo.StandaloneProducer
 * </pre>
 */
public class StandaloneProducer implements ProducerControlMBean {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneProducer.class);

    private final AtomicInteger ratePerSecond;
    private final AtomicLong messageCounter;
    private final long startTime;

    public StandaloneProducer(int initialRate) {
        this.ratePerSecond = new AtomicInteger(initialRate);
        this.messageCounter = new AtomicLong(0);
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public void setRate(int messagesPerSecond) {
        if (messagesPerSecond < 1 || messagesPerSecond > 100000) {
            throw new IllegalArgumentException("Rate must be between 1 and 100000 msg/sec");
        }
        int oldRate = this.ratePerSecond.getAndSet(messagesPerSecond);
        logger.info("🔄 Rate changed: {} → {} msg/sec", oldRate, messagesPerSecond);
    }

    @Override
    public int getRate() {
        return ratePerSecond.get();
    }

    @Override
    public long getMessagesSent() {
        return messageCounter.get();
    }

    @Override
    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    public void run(String bootstrap, String clientId, String topic) {
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║     Standalone Kafka Producer (JMX Dynamic Control)            ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Bootstrap: {}", bootstrap);
        logger.info("Client ID: {}", clientId);
        logger.info("Topic: {}", topic);
        logger.info("Initial Rate: {} msg/sec", ratePerSecond.get());
        logger.info("JMX Control: io.fullerstack.kafka.demo:type=ProducerControl,name=producer-1");
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down producer...");
            producer.close();
            logger.info("Sent {} messages total", messageCounter.get());
        }));

        logger.info("✅ Producer started - sending messages to '{}'", topic);
        logger.info("💡 Use JMX to call setRate(int) for dynamic rate adjustment");
        logger.info("Press Ctrl+C to stop...");
        logger.info("");

        while (true) {
            try {
                long count = messageCounter.incrementAndGet();
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

                // Dynamically calculate sleep time based on current rate
                int currentRate = ratePerSecond.get();
                long sleepMs = 1000L / currentRate;

                // For very high rates, use sleep(0) + yield
                if (sleepMs == 0) {
                    Thread.yield();
                } else {
                    Thread.sleep(sleepMs);
                }

            } catch (InterruptedException e) {
                logger.info("Producer interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error sending message: {}", e.getMessage());
            }
        }

        producer.close();
        logger.info("Producer stopped - sent {} messages", messageCounter.get());
    }

    public static void main(String[] args) {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
        String clientId = System.getenv().getOrDefault("CLIENT_ID", "producer-1");
        String topic = System.getenv().getOrDefault("TOPIC", "observability-demo-topic");
        int initialRate = Integer.parseInt(System.getenv().getOrDefault("RATE", "10"));

        StandaloneProducer producerControl = new StandaloneProducer(initialRate);

        // Register MBean for JMX control (using StandardMBean wrapper)
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("io.fullerstack.kafka.demo:type=ProducerControl,name=" + clientId);

            // Wrap in StandardMBean to satisfy JMX naming conventions
            javax.management.StandardMBean mbean = new javax.management.StandardMBean(
                producerControl,
                ProducerControlMBean.class
            );

            mbs.registerMBean(mbean, name);
            logger.info("✅ Registered ProducerControl MBean: {}", name);
        } catch (Exception e) {
            logger.error("Failed to register MBean", e);
            System.exit(1);
        }

        // Run producer loop
        producerControl.run(bootstrap, clientId, topic);
    }
}
