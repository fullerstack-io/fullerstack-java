package io.fullerstack.kafka.core.demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates a realistic Kafka cluster with producers, consumers, and resource usage.
 *
 * <p>This simulator creates realistic behavior including:
 * <ul>
 *   <li>Producer throughput with configurable rates</li>
 *   <li>Consumer lag that can grow/shrink</li>
 *   <li>Resource usage (CPU, memory, disk) that fluctuates</li>
 *   <li>Partition queues with overflow conditions</li>
 *   <li>Broker health that can degrade under load</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Demo the complete OODA loop with realistic scenarios</li>
 *   <li>Show throttling commands actually reducing load</li>
 *   <li>Demonstrate circuit breakers preventing cascading failures</li>
 *   <li>Visualize recovery after degradation</li>
 * </ul>
 */
public class KafkaClusterSimulator implements AutoCloseable {

    private final String clusterName;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ProducerSimulator> producers = new ConcurrentHashMap<>();
    private final Map<String, PartitionSimulator> partitions = new ConcurrentHashMap<>();
    private final Map<String, BrokerSimulator> brokers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Metrics
    private final AtomicLong totalMessagesProduced = new AtomicLong(0);
    private final AtomicLong totalMessagesConsumed = new AtomicLong(0);

    public KafkaClusterSimulator(String clusterName) {
        this.clusterName = clusterName;
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    /**
     * Starts the cluster simulation.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            System.out.println("🚀 Starting Kafka Cluster Simulator: " + clusterName);

            // Start all simulators
            producers.values().forEach(ProducerSimulator::start);
            partitions.values().forEach(PartitionSimulator::start);
            brokers.values().forEach(BrokerSimulator::start);

            System.out.println("✅ Cluster simulation started");
        }
    }

    /**
     * Adds a producer to the simulation.
     */
    public ProducerSimulator addProducer(String producerId, String targetPartition, int messagesPerSecond) {
        ProducerSimulator producer = new ProducerSimulator(
            producerId,
            targetPartition,
            messagesPerSecond,
            scheduler,
            this::onMessageProduced
        );
        producers.put(producerId, producer);
        System.out.printf("➕ Added producer: %s → %s @ %d msg/s%n", producerId, targetPartition, messagesPerSecond);
        return producer;
    }

    /**
     * Adds a partition to the simulation.
     */
    public PartitionSimulator addPartition(String partitionId, int maxQueueSize) {
        PartitionSimulator partition = new PartitionSimulator(partitionId, maxQueueSize, scheduler);
        partitions.put(partitionId, partition);
        System.out.printf("➕ Added partition: %s (max queue: %d)%n", partitionId, maxQueueSize);
        return partition;
    }

    /**
     * Adds a broker to the simulation.
     */
    public BrokerSimulator addBroker(String brokerId) {
        BrokerSimulator broker = new BrokerSimulator(brokerId, scheduler);
        brokers.put(brokerId, broker);
        System.out.printf("➕ Added broker: %s%n", brokerId);
        return broker;
    }

    /**
     * Returns a producer by ID.
     */
    public ProducerSimulator getProducer(String producerId) {
        return producers.get(producerId);
    }

    /**
     * Returns a partition by ID.
     */
    public PartitionSimulator getPartition(String partitionId) {
        return partitions.get(partitionId);
    }

    /**
     * Returns a broker by ID.
     */
    public BrokerSimulator getBroker(String brokerId) {
        return brokers.get(brokerId);
    }

    /**
     * Returns cluster metrics.
     */
    public ClusterMetrics getMetrics() {
        long currentProduced = totalMessagesProduced.get();
        long currentConsumed = totalMessagesConsumed.get();
        long totalLag = partitions.values().stream()
            .mapToLong(PartitionSimulator::getQueueDepth)
            .sum();

        return new ClusterMetrics(
            producers.size(),
            partitions.size(),
            brokers.size(),
            currentProduced,
            currentConsumed,
            totalLag
        );
    }

    /**
     * Prints current cluster state.
     */
    public void printStatus() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("📊 CLUSTER STATUS: " + clusterName);
        System.out.println("═".repeat(80));

        ClusterMetrics metrics = getMetrics();
        System.out.printf("Messages: Produced=%d, Consumed=%d, Lag=%d%n",
            metrics.totalProduced(), metrics.totalConsumed(), metrics.totalLag());

        System.out.println("\n🏭 PRODUCERS:");
        producers.values().forEach(p -> {
            System.out.printf("  %s: rate=%d msg/s, throttled=%s, total=%d%n",
                p.getProducerId(),
                p.getCurrentRate(),
                p.isThrottled() ? "YES" : "NO",
                p.getTotalProduced()
            );
        });

        System.out.println("\n📦 PARTITIONS:");
        partitions.values().forEach(p -> {
            System.out.printf("  %s: queue=%d/%d (%.1f%%), overflowing=%s%n",
                p.getPartitionId(),
                p.getQueueDepth(),
                p.getMaxQueueSize(),
                p.getQueueUtilization() * 100,
                p.isOverflowing() ? "YES" : "NO"
            );
        });

        System.out.println("\n🖥️  BROKERS:");
        brokers.values().forEach(b -> {
            System.out.printf("  %s: CPU=%.1f%%, Memory=%.1f%%, Health=%s%n",
                b.getBrokerId(),
                b.getCpuUsage() * 100,
                b.getMemoryUsage() * 100,
                b.getHealth()
            );
        });

        System.out.println("═".repeat(80) + "\n");
    }

    private void onMessageProduced(String producerId, String partition) {
        totalMessagesProduced.incrementAndGet();
        PartitionSimulator p = partitions.get(partition);
        if (p != null) {
            p.enqueue();
        }
    }

    @Override
    public void close() {
        System.out.println("🛑 Shutting down cluster simulator...");
        running.set(false);
        producers.values().forEach(ProducerSimulator::stop);
        partitions.values().forEach(PartitionSimulator::stop);
        brokers.values().forEach(BrokerSimulator::stop);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("✅ Cluster simulator stopped");
    }

    /**
     * Cluster metrics snapshot.
     */
    public record ClusterMetrics(
        int producers,
        int partitions,
        int brokers,
        long totalProduced,
        long totalConsumed,
        long totalLag
    ) {}

    /**
     * Callback for message production.
     */
    @FunctionalInterface
    public interface MessageCallback {
        void onMessage(String producerId, String partition);
    }
}
