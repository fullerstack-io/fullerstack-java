package io.fullerstack.kafka.runtime;

/**
 * Health summary for a single MSK cluster.
 * <p>
 * Aggregates monitoring status from Epic 1 agents:
 * - Active brokers (from DynamicBrokerDiscovery)
 * - Active producers (from DynamicClientDiscovery)
 * - Active consumers (from DynamicClientDiscovery)
 * <p>
 * A cluster is considered healthy if at least one broker is being monitored.
 *
 * @param clusterArn      AWS ARN for the MSK cluster
 * @param activeBrokers   Number of brokers currently monitored
 * @param activeProducers Number of producers currently monitored
 * @param activeConsumers Number of consumer groups currently monitored
 * @author Fullerstack
 */
public record ClusterHealth(
    String clusterArn,
    int activeBrokers,
    int activeProducers,
    int activeConsumers
) {

    /**
     * Checks if this cluster is healthy (at least one broker monitored).
     *
     * @return true if healthy
     */
    public boolean isHealthy() {
        return activeBrokers > 0;
    }

    /**
     * Builder for ClusterHealth.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String clusterArn;
        private int activeBrokers;
        private int activeProducers;
        private int activeConsumers;

        public Builder clusterArn(String clusterArn) {
            this.clusterArn = clusterArn;
            return this;
        }

        public Builder activeBrokers(int activeBrokers) {
            this.activeBrokers = activeBrokers;
            return this;
        }

        public Builder activeProducers(int activeProducers) {
            this.activeProducers = activeProducers;
            return this;
        }

        public Builder activeConsumers(int activeConsumers) {
            this.activeConsumers = activeConsumers;
            return this;
        }

        public ClusterHealth build() {
            return new ClusterHealth(
                clusterArn,
                activeBrokers,
                activeProducers,
                activeConsumers
            );
        }
    }
}
