package io.fullerstack.kafka.msk;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for dynamic broker discovery.
 * <p>
 * Controls how often to poll MSK API for cluster topology changes.
 *
 * @param pollInterval     How often to poll MSK API for broker changes (default: 60 seconds)
 * @param initialDelay     Initial delay before first sync (default: 0 = immediate)
 */
public record DynamicBrokerDiscoveryConfig(
    Duration pollInterval,
    Duration initialDelay
) {
    /**
     * Compact constructor with validation.
     */
    public DynamicBrokerDiscoveryConfig {
        Objects.requireNonNull(pollInterval, "pollInterval cannot be null");
        Objects.requireNonNull(initialDelay, "initialDelay cannot be null");

        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay cannot be negative");
        }
    }

    /**
     * Create config with default settings.
     * <p>
     * Default values:
     * <ul>
     *   <li>Poll interval: 60 seconds (balance responsiveness vs API throttling)</li>
     *   <li>Initial delay: 0 seconds (immediate first sync)</li>
     * </ul>
     */
    public static DynamicBrokerDiscoveryConfig defaults() {
        return new DynamicBrokerDiscoveryConfig(
            Duration.ofSeconds(60),
            Duration.ZERO
        );
    }

    /**
     * Create config with custom poll interval and default initial delay.
     *
     * @param pollInterval poll interval duration
     * @return config with custom poll interval
     */
    public static DynamicBrokerDiscoveryConfig withPollInterval(Duration pollInterval) {
        return new DynamicBrokerDiscoveryConfig(pollInterval, Duration.ZERO);
    }

    /**
     * Builder for flexible configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration pollInterval = Duration.ofSeconds(60);
        private Duration initialDelay = Duration.ZERO;

        /**
         * Set poll interval (how often to check MSK for changes).
         *
         * @param pollInterval poll interval duration
         * @return this builder
         */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /**
         * Set poll interval in seconds.
         *
         * @param seconds poll interval in seconds
         * @return this builder
         */
        public Builder pollIntervalSeconds(long seconds) {
            this.pollInterval = Duration.ofSeconds(seconds);
            return this;
        }

        /**
         * Set initial delay before first sync.
         *
         * @param initialDelay initial delay duration
         * @return this builder
         */
        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        /**
         * Set initial delay in seconds.
         *
         * @param seconds initial delay in seconds
         * @return this builder
         */
        public Builder initialDelaySeconds(long seconds) {
            this.initialDelay = Duration.ofSeconds(seconds);
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return validated configuration
         */
        public DynamicBrokerDiscoveryConfig build() {
            return new DynamicBrokerDiscoveryConfig(pollInterval, initialDelay);
        }
    }
}
