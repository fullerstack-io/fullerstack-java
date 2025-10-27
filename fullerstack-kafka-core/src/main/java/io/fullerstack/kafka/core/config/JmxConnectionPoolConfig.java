package io.fullerstack.kafka.core.config;

import java.time.Duration;

/**
 * Configuration for JMX connection pooling behavior.
 * <p>
 * <b>Purpose:</b> Control connection pooling for high-frequency JMX metrics collection.
 * Connection pooling reduces overhead when collection interval is below 10 seconds.
 * <p>
 * <b>When to Enable:</b>
 * <ul>
 *   <li>Collection interval &lt; 10 seconds: RECOMMENDED (90-95% overhead reduction)</li>
 *   <li>Collection interval 10-30 seconds: OPTIONAL</li>
 *   <li>Collection interval &gt; 30 seconds: NOT NEEDED (default disabled)</li>
 * </ul>
 * <p>
 * <b>Performance Impact:</b>
 * <ul>
 *   <li>Without pooling: 50-200ms connection setup per cycle</li>
 *   <li>With pooling: &lt;5ms connection reuse per cycle</li>
 * </ul>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * // Disable pooling (default - backward compatible)
 * JmxConnectionPoolConfig disabled = JmxConnectionPoolConfig.disabled();
 *
 * // Enable pooling with defaults (recommended for high-frequency)
 * JmxConnectionPoolConfig enabled = JmxConnectionPoolConfig.withPoolingEnabled();
 *
 * // Custom configuration
 * JmxConnectionPoolConfig custom = new JmxConnectionPoolConfig(
 *     true,                           // enabled
 *     Duration.ofMinutes(5),          // maxIdleTime
 *     Duration.ofSeconds(10)          // connectionTimeout
 * );
 * }</pre>
 *
 * @param enabled Enable connection pooling (default: false for backward compatibility)
 * @param maxIdleTime Maximum time a connection can be idle before refresh (default: 5 minutes)
 * @param connectionTimeout Timeout for establishing new connections (default: 10 seconds)
 */
public record JmxConnectionPoolConfig(
    boolean enabled,
    Duration maxIdleTime,
    Duration connectionTimeout
) {
    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if durations are negative or zero
     */
    public JmxConnectionPoolConfig {
        if (maxIdleTime.isNegative() || maxIdleTime.isZero()) {
            throw new IllegalArgumentException(
                "maxIdleTime must be positive, got: " + maxIdleTime
            );
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException(
                "connectionTimeout must be positive, got: " + connectionTimeout
            );
        }
    }

    /**
     * Creates disabled pooling configuration (default behavior).
     * <p>
     * Use for collection intervals &gt; 30 seconds or when backward compatibility
     * with existing behavior is required.
     *
     * @return disabled config with default timeouts
     */
    public static JmxConnectionPoolConfig disabled() {
        return new JmxConnectionPoolConfig(
            false,                      // disabled
            Duration.ofMinutes(5),      // maxIdleTime (unused when disabled)
            Duration.ofSeconds(10)      // connectionTimeout
        );
    }

    /**
     * Creates enabled pooling configuration with defaults.
     * <p>
     * Recommended for high-frequency monitoring (collection interval &lt; 10 seconds).
     * Provides 90-95% reduction in connection overhead.
     *
     * @return enabled config with production-ready defaults
     */
    public static JmxConnectionPoolConfig withPoolingEnabled() {
        return new JmxConnectionPoolConfig(
            true,                       // enabled
            Duration.ofMinutes(5),      // maxIdleTime: refresh after 5 minutes idle
            Duration.ofSeconds(10)      // connectionTimeout: 10 second timeout
        );
    }
}
