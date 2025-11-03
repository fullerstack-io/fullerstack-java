package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.msk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static io.humainary.substrates.api.Substrates.*;

/**
 * Runtime for monitoring a single MSK cluster.
 * <p>
 * Each cluster has its own Circuit and Epic 1 monitoring agents.
 * This is INFRASTRUCTURE - Epic 1's agents emit RC3 Serventis signals.
 * <p>
 * <b>Lifecycle</b>:
 * 1. Create ClusterRuntime with MSKClusterConfig
 * 2. Call start() to begin monitoring (starts discovery which starts Epic 1 agents)
 * 3. Call getHealth() for current monitoring status
 * 4. Call close() to gracefully stop monitoring and clean up Circuit
 * <p>
 * <b>Integration</b>:
 * - Story 5.2: DynamicBrokerDiscovery manages BrokerMonitoringAgent lifecycle
 * - Story 5.3: DynamicClientDiscovery manages Producer/Consumer monitor lifecycle
 * - Story 5.4: ThresholdDerivation updates BaselineService for Epic 1 Assessors
 *
 * @author Fullerstack
 */
public class ClusterRuntime implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRuntime.class);

    private final String clusterArn;
    private final Cortex cortex;
    private final Circuit circuit;  // ✅ Substrates RC3: One Circuit per cluster
    private final DynamicBrokerDiscovery brokerDiscovery;
    private final DynamicClientDiscovery clientDiscovery;
    private final ThresholdDerivation thresholdDerivation;

    private ClusterRuntime(
        String clusterArn,
        Cortex cortex,
        Circuit circuit,
        DynamicBrokerDiscovery brokerDiscovery,
        DynamicClientDiscovery clientDiscovery,
        ThresholdDerivation thresholdDerivation
    ) {
        this.clusterArn = clusterArn;
        this.cortex = cortex;
        this.circuit = circuit;
        this.brokerDiscovery = brokerDiscovery;
        this.clientDiscovery = clientDiscovery;
        this.thresholdDerivation = thresholdDerivation;
    }

    /**
     * Create ClusterRuntime for an MSK cluster.
     *
     * @param config MSK cluster configuration
     * @return ClusterRuntime instance
     */
    public static ClusterRuntime create(MSKClusterConfig config) {
        String clusterArn = config.clusterArn();
        String clusterName = extractClusterName(clusterArn);

        // ✅ Substrates RC3: Get Cortex and create isolated Circuit per cluster
        Cortex cortex = cortex();
        Circuit circuit = cortex.circuit(cortex.name("kafka-" + clusterName));

        // Story 5.1: MSK discovery
        MSKClusterDiscovery discovery = new MSKClusterDiscovery(config.mskClient());

        // Story 5.2: Dynamic broker discovery
        DynamicBrokerDiscoveryConfig brokerConfig = DynamicBrokerDiscoveryConfig.builder()
            .pollInterval(Duration.ofSeconds(60))
            .initialDelay(Duration.ZERO)
            .build();

        DynamicBrokerDiscovery brokerDiscovery = new DynamicBrokerDiscovery(
            discovery,
            clusterArn,
            brokerConfig,
            (brokerId, metrics) -> {} // metricsEmitter - not needed for Story 5.5
        );

        // Story 5.3: Dynamic producer/consumer discovery
        ProducerDiscovery producerDiscovery = new ProducerDiscovery(config.cloudWatchClient());
        ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(config.adminClient());
        DynamicClientDiscovery clientDiscovery = new DynamicClientDiscovery(
            producerDiscovery,
            consumerDiscovery,
            clusterName,
            Duration.ofSeconds(60),
            producerId -> {},  // onProducerAdded (not needed for this story)
            producerId -> {},  // onProducerRemoved
            groupId -> {},     // onConsumerAdded
            groupId -> {}      // onConsumerRemoved
        );

        // Story 5.4: Configuration-aware thresholds
        MSKConfigurationDiscovery configDiscovery = new MSKConfigurationDiscovery(config.mskClient());

        ThresholdDerivation thresholdDerivation = new ThresholdDerivation(
            configDiscovery,
            config.baselineService(),
            clusterArn,  // Use clusterArn (ThresholdDerivation will query for config ARN)
            Duration.ofMinutes(5)
        );

        return new ClusterRuntime(
            clusterArn,
            cortex,
            circuit,
            brokerDiscovery,
            clientDiscovery,
            thresholdDerivation
        );
    }

    /**
     * Start monitoring this cluster.
     * <p>
     * Starts Epic 5 discovery (Stories 5.2, 5.3, 5.4) which manages Epic 1 agents.
     */
    public void start() {
        logger.info("Starting monitoring for cluster: {}", clusterArn);

        // Start dynamic discovery
        brokerDiscovery.start();    // Story 5.2: Manages BrokerMonitoringAgent (Epic 1)
        clientDiscovery.start();    // Story 5.3: Manages Producer/Consumer monitors (Epic 1)
        thresholdDerivation.start(); // Story 5.4: Updates BaselineService
    }

    /**
     * Get cluster health summary.
     * <p>
     * Aggregates health from Epic 1's monitoring agents.
     */
    public ClusterHealth getHealth() {
        return new ClusterHealth(
            clusterArn,
            brokerDiscovery.getActiveSensorCount(),
            clientDiscovery.getActiveProducerCount(),
            clientDiscovery.getActiveConsumerCount()
        );
    }

    /**
     * Get this cluster's Circuit.
     * <p>
     * Epic 1's monitoring agents use this Circuit to emit Serventis signals.
     */
    public Circuit getCircuit() {
        return circuit;
    }

    @Override
    public void close() {
        logger.info("Shutting down monitoring for cluster: {}", clusterArn);

        // Stop discovery (which stops Epic 1 monitoring)
        brokerDiscovery.close();
        clientDiscovery.close();
        thresholdDerivation.close();

        // Close Circuit (Substrates resource cleanup)
        circuit.close();
    }

    private static String extractClusterName(String clusterArn) {
        // Extract cluster name from ARN: arn:aws:kafka:region:account:cluster/name/uuid
        String[] parts = clusterArn.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "unknown";
    }
}
