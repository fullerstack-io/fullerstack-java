package io.fullerstack.kafka.core.demo;

import io.fullerstack.kafka.core.system.KafkaObservabilitySystem;
import io.humainary.substrates.ext.serventis.ext.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Integrates REAL Observer classes with simulated JMX endpoints.
 *
 * <p>Demonstrates the complete semiotic hierarchy using PRODUCTION receptor logic:
 * <pre>
 * JmxSimulator (fake MBeans)
 *     ↓ JMX queries
 * REAL Observers (LocalProducerBufferMonitor, etc.)
 *     ↓ Call REAL production emission logic
 * REAL Serventis Instruments (Queues, Probes, Services, Gauges, Counters)
 *     ↓ Emit signals
 * REAL OODA Loop (Monitors → Reporters → Actors)
 * </pre>
 *
 * <p>This uses the ACTUAL production signal emission logic from the receptor classes,
 * just with local MBeanServer access instead of remote JMX connections.
 */
public class KafkaMetricsSimulator implements AutoCloseable {

    private final KafkaObservabilitySystem ooda;
    private final KafkaClusterSimulator cluster;
    private final JmxSimulator jmxSimulator;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // REAL production receptor classes (using local MBeanServer)
    private final List<LocalProducerBufferMonitor> bufferMonitors = new ArrayList<>();
    private final List<LocalProducerSendObserver> sendObservers = new ArrayList<>();

    private ScheduledFuture<?> metricsTask;

    public KafkaMetricsSimulator(
        KafkaObservabilitySystem ooda,
        KafkaClusterSimulator cluster,
        ScheduledExecutorService scheduler
    ) {
        this.ooda = ooda;
        this.cluster = cluster;
        this.jmxSimulator = new JmxSimulator(cluster);
        this.scheduler = scheduler;
    }

    /**
     * Starts REAL production observers with simulated JMX endpoints.
     */
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            System.out.println("\n📊 Starting REAL production observers...");

            // Register simulated JMX MBeans
            jmxSimulator.registerAll();

            // Start REAL receptor classes
            setupRealObservers();

            // Also emit some manual signals for instruments not covered by observers
            startManualEmission();

            System.out.println("✅ Real observers started");
        }
    }

    /**
     * Sets up REAL production receptor classes using local MBeanServer.
     */
    private void setupRealObservers() {
        System.out.println("\n🔍 Setting up REAL production observers...");

        String[] producerIds = {"producer-1", "producer-2", "producer-3", "producer-4"};

        for (String producerId : producerIds) {
            // Create instruments for buffer monitoring
            Queues.Queue bufferQueue = ooda.getQueues().percept(
                cortex().name(producerId + ".buffer")
            );
            Gauges.Gauge totalBytesGauge = ooda.getGauges().percept(
                cortex().name(producerId + ".buffer.total-bytes")
            );
            Counters.Counter exhaustedCounter = ooda.getCounters().percept(
                cortex().name(producerId + ".buffer.exhausted")
            );
            Gauges.Gauge batchSizeGauge = ooda.getGauges().percept(
                cortex().name(producerId + ".batch-size")
            );
            Gauges.Gauge recordsPerRequestGauge = ooda.getGauges().percept(
                cortex().name(producerId + ".records-per-request")
            );

            // Create REAL buffer monitor with production logic
            LocalProducerBufferMonitor bufferMonitor = new LocalProducerBufferMonitor(
                producerId,
                bufferQueue,
                totalBytesGauge,
                exhaustedCounter,
                batchSizeGauge,
                recordsPerRequestGauge
            );

            bufferMonitor.start();
            bufferMonitors.add(bufferMonitor);

            // Create instruments for send monitoring
            Counters.Counter sendRateCounter = ooda.getCounters().percept(
                cortex().name(producerId + ".send-rate")
            );
            Counters.Counter sendTotalCounter = ooda.getCounters().percept(
                cortex().name(producerId + ".send-total")
            );
            Probes.Probe sendProbe = ooda.getProbes().percept(
                cortex().name(producerId + ".send")
            );
            Counters.Counter errorCounter = ooda.getCounters().percept(
                cortex().name(producerId + ".errors")
            );
            Services.Service retryService = ooda.getServices().percept(
                cortex().name(producerId + ".retry")
            );
            Counters.Counter retryCounter = ooda.getCounters().percept(
                cortex().name(producerId + ".retries")
            );
            Gauges.Gauge latencyGauge = ooda.getGauges().percept(
                cortex().name(producerId + ".latency")
            );

            // Create REAL send receptor with production logic
            LocalProducerSendObserver sendObserver = new LocalProducerSendObserver(
                producerId,
                sendRateCounter,
                sendTotalCounter,
                sendProbe,
                errorCounter,
                retryService,
                retryCounter,
                latencyGauge
            );

            sendObserver.start();
            sendObservers.add(sendObserver);
        }

        System.out.printf("   ✓ Started %d ProducerBufferMonitors (REAL production code)%n",
            bufferMonitors.size());
        System.out.printf("   ✓ Started %d ProducerSendObservers (REAL production code)%n",
            sendObservers.size());
    }

    /**
     * Starts manual emission for instruments not yet covered by real observers.
     */
    private void startManualEmission() {
        metricsTask = scheduler.scheduleAtFixedRate(
            this::emitMetrics,
            0,
            1,
            TimeUnit.SECONDS
        );
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
     * Emits Queue metrics (partition queues only - producer buffers handled by real observers).
     */
    private void emitQueueMetrics() {
        // Partition queue depths (not covered by observers yet)
        PartitionSimulator part = cluster.getPartition("broker-1.orders.p0");
        if (part != null && part.isOverflowing()) {
            Queues.Queue partQueue = ooda.getQueues().percept(cortex().name("broker-1.orders.p0.queue"));
            partQueue.overflow();
        }
    }

    /**
     * Emits Probe metrics (connection health) - only for entities not covered by observers.
     * NOTE: Producer send probes are handled by LocalProducerSendObserver.
     */
    private void emitProbeMetrics() {
        // Broker connection probes (not covered by observers yet)
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Probes.Probe brokerProbe = ooda.getProbes().percept(cortex().name("broker-1.connection"));

            if (broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
                // Connection failures during critical health
                brokerProbe.fail(Probes.Dimension.OUTBOUND);
            } else {
                // Successful connections
                brokerProbe.transfer(Probes.Dimension.OUTBOUND);
            }
        }
    }

    /**
     * Emits Service metrics (request/response lifecycle) - only for entities not covered by observers.
     * NOTE: Producer send/retry services are handled by LocalProducerSendObserver.
     */
    private void emitServiceMetrics() {
        // Consumer fetch request (not covered by observers yet)
        Services.Service consumerService = ooda.getServices().percept(cortex().name("consumer-1.fetch"));
        consumerService.call(Services.Dimension.CALLER);
        consumerService.success(Services.Dimension.CALLER);  // Usually succeeds
    }

    /**
     * Emits Gauge metrics (JVM heap, CPU, disk usage).
     */
    private void emitGaugeMetrics() {
        // Broker JVM heap
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null) {
            Gauges.Gauge heapGauge = ooda.getGauges().percept(cortex().name("broker-1.jvm.heap"));

            double heapUsage = broker.getMemoryUsage();
            if (heapUsage > 0.85) {
                heapGauge.overflow();  // Above threshold
            } else if (heapUsage > 0.75) {
                heapGauge.increment();  // Rising
            } else if (heapUsage < 0.50) {
                heapGauge.decrement();  // Falling
            }

            // CPU gauge
            Gauges.Gauge cpuGauge = ooda.getGauges().percept(cortex().name("broker-1.cpu"));
            double cpuUsage = broker.getCpuUsage();
            if (cpuUsage > 0.80) {
                cpuGauge.overflow();
            } else if (cpuUsage > 0.60) {
                cpuGauge.increment();
            }
        }

        // Disk usage
        Gauges.Gauge diskGauge = ooda.getGauges().percept(cortex().name("broker-1.disk"));
        diskGauge.increment();  // Disk slowly growing
    }

    /**
     * Emits Counter metrics (message counts, error counts).
     */
    private void emitCounterMetrics() {
        // Producer message counter
        ProducerSimulator producer = cluster.getProducer("producer-1");
        if (producer != null && producer.getCurrentRate() > 0) {
            Counters.Counter msgCounter = ooda.getCounters().percept(cortex().name("producer-1.messages"));
            msgCounter.increment();  // Increment by current rate
        }

        // Partition message counter
        PartitionSimulator part = cluster.getPartition("broker-1.orders.p0");
        if (part != null && part.getQueueDepth() > 0) {
            Counters.Counter partCounter = ooda.getCounters().percept(cortex().name("broker-1.orders.p0.messages"));
            partCounter.increment();
        }

        // Error counter
        BrokerSimulator broker = cluster.getBroker("broker-1");
        if (broker != null && broker.getHealth() == BrokerSimulator.BrokerHealth.CRITICAL) {
            Counters.Counter errorCounter = ooda.getCounters().percept(cortex().name("broker-1.errors"));
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
            Resources.Resource threadPool = ooda.getResources().percept(
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
        Caches.Cache metadataCache = ooda.getCaches().percept(cortex().name("broker-1.metadata-cache"));

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
            Monitors.Monitor brokerMonitor = ooda.getMonitors().percept(
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
            Monitors.Monitor partMonitor = ooda.getMonitors().percept(
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

        System.out.println("\n→ Signal Emission:");
        System.out.println("  REAL OBSERVERS:     ProducerBufferMonitor, ProducerSendObserver (production code!)");
        System.out.println("         ↓ Query JMX MBeans");
        System.out.println("  SERVENTIS APIS:     Emit using Queues, Probes, Services, Gauges, Counters methods");
        System.out.println("         ↓ Aggregate into Monitors");
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

        // Stop and close all real observers
        for (LocalProducerBufferMonitor monitor : bufferMonitors) {
            monitor.close();
        }
        for (LocalProducerSendObserver receptor : sendObservers) {
            receptor.close();
        }

        // Close JMX simulator
        try {
            jmxSimulator.close();
        } catch (Exception e) {
            System.err.println("Error closing JMX simulator: " + e.getMessage());
        }
    }
}
