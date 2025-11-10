package io.fullerstack.kafka.core.demo;

import io.fullerstack.kafka.core.system.KafkaObservabilitySystem;
import io.humainary.substrates.ext.serventis.ext.*;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Emits realistic metrics using all Serventis instrument types (REAL production APIs).
 *
 * <p>Demonstrates the complete semiotic hierarchy:
 * <pre>
 * LAYER 1 (OBSERVE - Raw Signals):
 *   Queues    → Producer buffer overflow, consumer lag
 *   Probes    → Network connection success/failure
 *   Services  → Request/response lifecycle
 *   Gauges    → JVM heap usage, disk usage
 *   Counters  → Message counts, error counts
 *   Resources → Thread pool capacity
 *   Caches    → Metadata cache hits/misses
 *
 * LAYER 2 (ORIENT - Condition Assessment):
 *   Monitors  → Aggregate raw signals into health conditions
 *
 * LAYER 3 (DECIDE - Urgency Assessment):
 *   Reporters → Assess urgency (WARNING, CRITICAL)
 *
 * LAYER 4 (ACT - Response):
 *   Actors    → Take automated actions
 * </pre>
 *
 * <p>This feeds realistic metrics into the OODA system to trigger
 * actual Monitor condition assessments and Actor responses.
 */
public class KafkaMetricsSimulator implements AutoCloseable {

    private final KafkaObservabilitySystem ooda;
    private final KafkaClusterSimulator cluster;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledFuture<?> metricsTask;

    public KafkaMetricsSimulator(
        KafkaObservabilitySystem ooda,
        KafkaClusterSimulator cluster,
        ScheduledExecutorService scheduler
    ) {
        this.ooda = ooda;
        this.cluster = cluster;
        this.scheduler = scheduler;
    }

    /**
     * Starts emitting realistic metrics using all Serventis instruments.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            System.out.println("\n📊 Starting metrics emission (ALL Serventis instruments)...");

            // Emit metrics every second
            metricsTask = scheduler.scheduleAtFixedRate(
                this::emitMetrics,
                0,
                1,
                TimeUnit.SECONDS
            );

            System.out.println("✅ Metrics emission started");
        }
    }

    /**
     * Emits realistic metrics from all instrument types.
     */
    private void emitMetrics() {
        emitQueueMetrics();
        emitProbeMetrics();
        emitServiceMetrics();
        emitGaugeMetrics();
        emitCounterMetrics();
        emitResourceMetrics();
        emitCacheMetrics();
        emitMonitorMetrics();  // Aggregate into Monitors
    }

    /**
     * Emits Queue metrics (buffer overflow, consumer lag).
     */
    private void emitQueueMetrics() {
        // Producer-1 buffer
        ProducerSimulator p1 = cluster.getProducer("producer-1");
        if (p1 != null) {
            Queues.Queue queue = ooda.getQueues().get(cortex().name("producer-1.buffer"));

            if (p1.isThrottled()) {
                queue.underflow();  // Buffer draining
            } else if (random.nextDouble() > 0.7) {
                queue.overflow();  // Buffer filling
            } else {
                queue.enqueue();  // Normal enqueue
            }
        }

        // Partition queue depths
        PartitionSimulator part = cluster.getPartition("broker-1.orders.p0");
        if (part != null && part.isOverflowing()) {
            Queues.Queue partQueue = ooda.getQueues().get(cortex().name("broker-1.orders.p0.queue"));
            partQueue.overflow();
        }
    }

    /**
     * Emits Probe metrics (connection health).
     */
    private void emitProbeMetrics() {
        // Broker connection probes
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Probes.Probe brokerProbe = ooda.getProbes().get(cortex().name("broker-1.connection"));

            if (broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
                // Connection failures during critical health
                brokerProbe.failed();
            } else {
                // Successful connections
                brokerProbe.transmitted();
            }
        }

        // Producer → Broker connection
        Probes.Probe producerProbe = ooda.getProbes().get(cortex().name("producer-1.broker.connection"));
        producerProbe.transmitted();
    }

    /**
     * Emits Service metrics (request/response lifecycle).
     */
    private void emitServiceMetrics() {
        // Producer send request
        Services.Service producerService = ooda.getServices().get(cortex().name("producer-1.send"));

        producerService.called();  // Request started

        // Simulate request completion based on broker health
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null && broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
            producerService.failed();  // Request failed
        } else {
            producerService.succeeded();  // Request succeeded
        }

        // Consumer fetch request
        Services.Service consumerService = ooda.getServices().get(cortex().name("consumer-1.fetch"));
        consumerService.called();
        consumerService.succeeded();  // Usually succeeds
    }

    /**
     * Emits Gauge metrics (JVM heap, CPU, disk usage).
     */
    private void emitGaugeMetrics() {
        // Broker JVM heap
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Gauges.Gauge heapGauge = ooda.getGauges().get(cortex().name("broker-1.jvm.heap"));

            double heapUsage = broker.getMemoryUsage();
            if (heapUsage > 0.85) {
                heapGauge.overflow();  // Above threshold
            } else if (heapUsage > 0.75) {
                heapGauge.increment();  // Rising
            } else if (heapUsage < 0.50) {
                heapGauge.decrement();  // Falling
            }

            // CPU gauge
            Gauges.Gauge cpuGauge = ooda.getGauges().get(cortex().name("broker-1.cpu"));
            double cpuUsage = broker.getCpuUsage();
            if (cpuUsage > 0.80) {
                cpuGauge.overflow();
            } else if (cpuUsage > 0.60) {
                cpuGauge.increment();
            }
        }

        // Disk usage
        Gauges.Gauge diskGauge = ooda.getGauges().get(cortex().name("broker-1.disk"));
        diskGauge.increment();  // Disk slowly growing
    }

    /**
     * Emits Counter metrics (message counts, error counts).
     */
    private void emitCounterMetrics() {
        // Producer message counter
        ProducerSimulator producer = cluster.getProducer("producer-1");
        if (producer != null && producer.getCurrentRate() > 0) {
            Counters.Counter msgCounter = ooda.getCounters().get(cortex().name("producer-1.messages"));
            msgCounter.increment();  // Increment by current rate
        }

        // Partition message counter
        PartitionSimulator part = cluster.getPartition("broker-1.orders.p0");
        if (part != null && part.getQueueDepth() > 0) {
            Counters.Counter partCounter = ooda.getCounters().get(cortex().name("broker-1.orders.p0.messages"));
            partCounter.increment();
        }

        // Error counter
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null && broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
            Counters.Counter errorCounter = ooda.getCounters().get(cortex().name("broker-1.errors"));
            errorCounter.increment();  // Errors during critical health
        }
    }

    /**
     * Emits Resource metrics (thread pool, connection pool capacity).
     */
    private void emitResourceMetrics() {
        // Broker request handler thread pool
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Resources.Resource threadPool = ooda.getResources().get(
                cortex().name("broker-1.request-handlers")
            );

            // Simulate thread pool capacity based on broker health
            if (broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
                threadPool.deny();  // Thread pool exhausted
            } else {
                threadPool.grant();  // Threads available
            }
        }
    }

    /**
     * Emits Cache metrics (metadata cache hit/miss rates).
     */
    private void emitCacheMetrics() {
        // Broker metadata cache
        Caches.Cache metadataCache = ooda.getCaches().get(cortex().name("broker-1.metadata-cache"));

        // Simulate cache hit/miss based on random distribution
        if (random.nextDouble() > 0.8) {
            metadataCache.miss();  // 20% miss rate
        } else {
            metadataCache.hit();   // 80% hit rate
        }
    }

    /**
     * Emits Monitor metrics (condition assessment from aggregated signals).
     *
     * <p>This is where raw instrument signals aggregate into health conditions.
     */
    private void emitMonitorMetrics() {
        // Aggregate broker health from all signals
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Monitors.Monitor brokerMonitor = ooda.getMonitors().get(
                cortex().name("broker-1.health")
            );

            switch (broker.getHealth()) {
                case HEALTHY -> brokerMonitor.stable(Monitors.Dimension.CONFIRMED);
                case WARNING -> brokerMonitor.converging(Monitors.Dimension.MEASURED);
                case DEGRADED -> brokerMonitor.degraded(Monitors.Dimension.MEASURED);
                case CRITICAL -> brokerMonitor.degraded(Monitors.Dimension.CONFIRMED);
            }
        }

        // Aggregate partition health from queue + service metrics
        PartitionSimulator part = cluster.getPartition("broker-1.orders.p0");
        if (part != null) {
            Monitors.Monitor partMonitor = ooda.getMonitors().get(
                cortex().name("broker-1.orders.p0")
            );

            if (part.isOverflowing()) {
                partMonitor.degraded(Monitors.Dimension.MEASURED);
            } else {
                partMonitor.stable(Monitors.Dimension.CONFIRMED);
            }
        }
    }

    /**
     * Prints current instrument metrics.
     */
    public void printInstrumentMetrics() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("📈 SERVENTIS INSTRUMENT METRICS (REAL Production APIs)");
        System.out.println("═".repeat(80));

        System.out.println("ALL 7 Serventis Instrument Types:");
        System.out.println("  • Queues    → Buffer overflow/underflow detection");
        System.out.println("  • Probes    → Connection success/failure rates");
        System.out.println("  • Services  → Request lifecycle tracking");
        System.out.println("  • Gauges    → JVM heap, CPU, disk usage");
        System.out.println("  • Counters  → Message counts, error totals");
        System.out.println("  • Resources → Thread pool capacity");
        System.out.println("  • Caches    → Metadata cache hit/miss");

        System.out.println("\n→ Complete Semiotic Hierarchy:");
        System.out.println("  LAYER 1 (OBSERVE):  Queues/Probes/Services/Gauges/Counters/Resources/Caches");
        System.out.println("         ↓ Aggregate signals");
        System.out.println("  LAYER 2 (ORIENT):   Monitors assess conditions (STABLE/DEGRADED/DEFECTIVE)");
        System.out.println("         ↓ Assess urgency");
        System.out.println("  LAYER 3 (DECIDE):   Reporters determine criticality (WARNING/CRITICAL)");
        System.out.println("         ↓ Trigger response");
        System.out.println("  LAYER 4 (ACT):      Actors execute commands (THROTTLE/ALERT)");

        System.out.println("\nCurrent State:");
        System.out.println("  • broker-1.health: " + cluster.getBroker("broker-1").getHealth());
        System.out.println("  • broker-1.orders.p0: " +
            (cluster.getPartition("broker-1.orders.p0").isOverflowing() ? "OVERFLOW" : "NORMAL"));

        System.out.println("═".repeat(80) + "\n");
    }

    public void stop() {
        running.set(false);
        if (metricsTask != null) {
            metricsTask.cancel(false);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
