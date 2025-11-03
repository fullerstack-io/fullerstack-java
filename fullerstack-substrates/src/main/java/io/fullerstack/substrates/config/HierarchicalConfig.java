package io.fullerstack.substrates.config;

import java.util.*;

/**
 * Hierarchical configuration using ResourceBundle (zero dependencies).
 *
 * <p>Supports fallback chain:
 * <ol>
 *   <li>config_{circuit}_{container}.properties (container-specific)</li>
 *   <li>config_{circuit}.properties (circuit-specific)</li>
 *   <li>config.properties (global defaults)</li>
 * </ol>
 *
 * <p><strong>How ResourceBundle Fallback Works:</strong>
 * <p>ResourceBundle uses {@link Locale} for fallback. We cleverly use locale
 * language tags to represent circuit/container hierarchy:
 * <ul>
 *   <li>Locale "broker-health" → config_broker-health.properties → config.properties</li>
 *   <li>Locale "broker-health-brokers" → config_broker-health-brokers.properties → config_broker-health.properties → config.properties</li>
 * </ul>
 *
 * <p><strong>Example Property Files:</strong>
 * <pre>
 * # config.properties (global defaults)
 * valve.queue-size=1000
 * valve.shutdown-timeout-ms=5000
 *
 * # config_broker-health.properties (circuit override)
 * valve.queue-size=5000  # Heavy workload needs larger queue
 *
 * # config_broker-health-brokers.properties (container override)
 * valve.queue-size=10000  # Broker container needs even larger queue
 * </pre>
 *
 * <p><strong>Usage:</strong>
 * <pre>
 * // Global config
 * HierarchicalConfig global = HierarchicalConfig.global();
 * int queueSize = global.getInt("valve.queue-size");
 * // → 1000 (from config.properties)
 *
 * // Circuit-specific config
 * HierarchicalConfig brokerHealth = HierarchicalConfig.forCircuit("broker-health");
 * int queueSize = brokerHealth.getInt("valve.queue-size");
 * // → 5000 (from config_broker-health.properties)
 *
 * // Container-specific config
 * HierarchicalConfig brokers = HierarchicalConfig.forContainer("broker-health", "brokers");
 * int queueSize = brokers.getInt("valve.queue-size");
 * // → 10000 (from config_broker-health-brokers.properties)
 * </pre>
 *
 * <p><strong>System Property Overrides:</strong>
 * <p>System properties take precedence over all property files:
 * <pre>
 * java -Dvalve.queue-size=20000 -jar app.jar
 * </pre>
 */
public class HierarchicalConfig {

  private final ResourceBundle bundle;
  private final String context;  // For debugging/logging

  private HierarchicalConfig(ResourceBundle bundle, String context) {
    this.bundle = bundle;
    this.context = context;
  }

  /**
   * Get global configuration (config.properties).
   *
   * @return Global configuration
   */
  public static HierarchicalConfig global() {
    ResourceBundle bundle = ResourceBundle.getBundle("config", Locale.ROOT);
    return new HierarchicalConfig(bundle, "global");
  }

  /**
   * Get circuit-specific configuration.
   *
   * <p>Fallback chain:
   * <ol>
   *   <li>config_{circuitName}.properties</li>
   *   <li>config.properties (global)</li>
   * </ol>
   *
   * @param circuitName Circuit name (e.g., "broker-health", "partition-flow")
   * @return Circuit-specific configuration
   */
  public static HierarchicalConfig forCircuit(String circuitName) {
    Objects.requireNonNull(circuitName, "circuitName cannot be null");
    if (circuitName.isBlank()) {
      throw new IllegalArgumentException("circuitName cannot be blank");
    }

    // Use language tag as circuit identifier
    Locale circuitLocale = Locale.forLanguageTag(circuitName);
    ResourceBundle bundle = ResourceBundle.getBundle("config", circuitLocale);
    return new HierarchicalConfig(bundle, "circuit:" + circuitName);
  }

  /**
   * Get container-specific configuration.
   *
   * <p>Fallback chain:
   * <ol>
   *   <li>config_{circuitName}-{containerName}.properties</li>
   *   <li>config_{circuitName}.properties (circuit default)</li>
   *   <li>config.properties (global)</li>
   * </ol>
   *
   * @param circuitName Circuit name (e.g., "broker-health")
   * @param containerName Container name (e.g., "brokers", "partitions")
   * @return Container-specific configuration
   */
  public static HierarchicalConfig forContainer(String circuitName, String containerName) {
    Objects.requireNonNull(circuitName, "circuitName cannot be null");
    Objects.requireNonNull(containerName, "containerName cannot be null");
    if (circuitName.isBlank()) {
      throw new IllegalArgumentException("circuitName cannot be blank");
    }
    if (containerName.isBlank()) {
      throw new IllegalArgumentException("containerName cannot be blank");
    }

    // Use language tag as circuit-container identifier
    // Locale "broker-health-brokers" → config_broker-health-brokers.properties
    Locale containerLocale = Locale.forLanguageTag(circuitName + "-" + containerName);
    ResourceBundle bundle = ResourceBundle.getBundle("config", containerLocale);
    return new HierarchicalConfig(bundle, "container:" + circuitName + "/" + containerName);
  }

  // =========================================================================
  // Type-safe getters with system property override support
  // =========================================================================

  /**
   * Get string value.
   *
   * <p>Checks system properties first, then ResourceBundle.
   *
   * @param key Property key
   * @return Property value
   * @throws ConfigurationException if key not found
   */
  public String getString(String key) {
    // System property override
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return sysProp;
    }

    try {
      return bundle.getString(key);
    } catch (MissingResourceException e) {
      throw new ConfigurationException(
        "Missing config key '" + key + "' in context: " + context, e
      );
    }
  }

  /**
   * Get string value with default.
   *
   * @param key Property key
   * @param defaultValue Default if not found
   * @return Property value or default
   */
  public String getString(String key, String defaultValue) {
    // System property override
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return sysProp;
    }

    try {
      return bundle.getString(key);
    } catch (MissingResourceException e) {
      return defaultValue;
    }
  }

  /**
   * Get double value.
   *
   * @param key Property key
   * @return Property value as double
   * @throws ConfigurationException if key not found or invalid format
   */
  public double getDouble(String key) {
    String value = getString(key);
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new ConfigurationException(
        "Invalid double value for key '" + key + "': " + value, e
      );
    }
  }

  /**
   * Get double value with default.
   *
   * @param key Property key
   * @param defaultValue Default if not found
   * @return Property value as double or default
   */
  public double getDouble(String key, double defaultValue) {
    try {
      String value = getString(key, null);
      if (value == null) {
        return defaultValue;
      }
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Get int value.
   *
   * @param key Property key
   * @return Property value as int
   * @throws ConfigurationException if key not found or invalid format
   */
  public int getInt(String key) {
    String value = getString(key);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ConfigurationException(
        "Invalid int value for key '" + key + "': " + value, e
      );
    }
  }

  /**
   * Get int value with default.
   *
   * @param key Property key
   * @param defaultValue Default if not found
   * @return Property value as int or default
   */
  public int getInt(String key, int defaultValue) {
    try {
      String value = getString(key, null);
      if (value == null) {
        return defaultValue;
      }
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Get long value.
   *
   * @param key Property key
   * @return Property value as long
   * @throws ConfigurationException if key not found or invalid format
   */
  public long getLong(String key) {
    String value = getString(key);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ConfigurationException(
        "Invalid long value for key '" + key + "': " + value, e
      );
    }
  }

  /**
   * Get long value with default.
   *
   * @param key Property key
   * @param defaultValue Default if not found
   * @return Property value as long or default
   */
  public long getLong(String key, long defaultValue) {
    try {
      String value = getString(key, null);
      if (value == null) {
        return defaultValue;
      }
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Get boolean value.
   *
   * @param key Property key
   * @return Property value as boolean
   */
  public boolean getBoolean(String key) {
    String value = getString(key);
    return Boolean.parseBoolean(value);
  }

  /**
   * Get boolean value with default.
   *
   * @param key Property key
   * @param defaultValue Default if not found
   * @return Property value as boolean or default
   */
  public boolean getBoolean(String key, boolean defaultValue) {
    try {
      String value = getString(key, null);
      if (value == null) {
        return defaultValue;
      }
      return Boolean.parseBoolean(value);
    } catch (MissingResourceException e) {
      return defaultValue;
    }
  }

  /**
   * Check if key exists in configuration.
   *
   * @param key Property key
   * @return true if key exists
   */
  public boolean contains(String key) {
    // Check system property first
    if (System.getProperty(key) != null) {
      return true;
    }
    return bundle.containsKey(key);
  }

  /**
   * Get all keys in this configuration level.
   *
   * @return Set of all keys
   */
  public Set<String> keys() {
    return bundle.keySet();
  }

  /**
   * Get configuration context (for debugging).
   *
   * @return Context description (e.g., "global", "circuit:broker-health")
   */
  public String context() {
    return context;
  }

  @Override
  public String toString() {
    return "HierarchicalConfig[context=" + context + "]";
  }
}
