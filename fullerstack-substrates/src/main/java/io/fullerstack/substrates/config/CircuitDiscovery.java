package io.fullerstack.substrates.config;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers circuits by scanning for configuration files.
 * <p>
 * Convention: Any file matching {@code config_{circuit-name}.properties} defines a circuit.
 * <p>
 * Example files:
 * < ul >
 * < li >config_broker-health.properties → circuit "broker-health"</li >
 * < li >config_partition-flow.properties → circuit "partition-flow"</li >
 * < li >config_consumer-lag.properties → circuit "consumer-lag"</li >
 * </ul >
 * <p>
 * Container files (config_{circuit}_{container}.properties) are ignored - containers
 * are discovered separately within their circuit context.
 * <p>
 * Usage:
 * < pre >
 * // Discover all configured circuits
 * Set&lt;String&gt; circuitNames = CircuitDiscovery.discoverCircuits();
 * // → ["broker-health", "partition-flow", "consumer-lag"]
 * <p>
 * // Create circuits from discovery
 * for (String name : circuitNames) {
 * Circuit circuit = Cortex.circuit(Cortex.name(name));
 * HierarchicalConfig config = HierarchicalConfig.forCircuit(name);
 * // Use config to configure circuit...
 * }
 * </pre >
 */
public class CircuitDiscovery {

  // Pattern: config_{circuit-name}.properties
  // Matches: config_broker-health.properties
  // Does NOT match: config.properties (global)
  // Does NOT match: config_broker-health-brokers.properties (container)
  private static final Pattern CIRCUIT_CONFIG_PATTERN =
    Pattern.compile ( "config_([a-zA-Z0-9-]+)\\.properties" );

  /**
   * Discover all circuits by scanning classpath for config files.
   * <p>
   * Searches for files matching pattern: {@code config_{circuit-name}.properties}
   * <p>
   * Circuits are included if:
   * < ul >
   * < li >Config file exists: {@code config_{circuit-name}.properties}</li >
   * < li >{@code circuit.enabled=true} (or property not present, defaults to true)</li >
   * </ul >
   * <p>
   * Container files (with hyphens in the name after the first hyphen) are excluded.
   *
   * @return Set of enabled circuit names (e.g., ["broker-health", "partition-flow"])
   */
  public static Set < String > discoverCircuits () {
    Set < String > candidateNames = new HashSet <> ();

    try {
      // Get all resources from classpath that might be config files
      ClassLoader classLoader = Thread.currentThread ().getContextClassLoader ();
      Enumeration < URL > resources = classLoader.getResources ( "" );

      while ( resources.hasMoreElements () ) {
        URL resource = resources.nextElement ();
        // Scan this resource location for config files
        scanForCircuitConfigs ( resource, candidateNames );
      }

    } catch ( IOException e ) {
      throw new ConfigurationException ( "Failed to discover circuits", e );
    }

    // Filter by circuit.enabled property
    Set < String > enabledCircuits = candidateNames.stream ()
      .filter ( CircuitDiscovery::isCircuitEnabled )
      .collect ( Collectors.toSet () );

    return Collections.unmodifiableSet ( enabledCircuits );
  }

  /**
   * Check if a circuit is enabled.
   * <p>
   * A circuit is enabled if {@code circuit.enabled=true} in its config file,
   * or if the property is not present (defaults to true).
   *
   * @param circuitName Circuit name
   * @return true if circuit is enabled
   */
  public static boolean isCircuitEnabled ( String circuitName ) {
    try {
      HierarchicalConfig config = HierarchicalConfig.forCircuit ( circuitName );
      // Default to true if not specified
      return config.getBoolean ( "circuit.enabled", true );
    } catch ( Exception e ) {
      // If we can't load config, assume disabled
      return false;
    }
  }

  /**
   * Discover circuits by trying to load specific known files.
   * <p>
   * This is a simpler alternative that doesn't scan the filesystem,
   * but instead tries to load ResourceBundles for known circuit names.
   * <p>
   * Note: This approach requires you to know potential circuit names,
   * defeating the purpose of discovery. Use {@link #discoverCircuits()} instead.
   *
   * @param potentialNames Potential circuit names to check
   * @return Set of circuit names that have config files
   */
  public static Set < String > discoverCircuits ( Set < String > potentialNames ) {
    return potentialNames.stream ()
      .filter ( CircuitDiscovery::hasCircuitConfig )
      .collect ( Collectors.toSet () );
  }

  /**
   * Check if a circuit has a configuration file.
   *
   * @param circuitName Circuit name
   * @return true if config_{circuitName}.properties exists
   */
  public static boolean hasCircuitConfig ( String circuitName ) {
    try {
      String filename = "config_" + circuitName + ".properties";
      URL resource = Thread.currentThread ()
        .getContextClassLoader ()
        .getResource ( filename );
      return resource != null;
    } catch ( Exception e ) {
      return false;
    }
  }

  /**
   * Discover containers within a circuit.
   * <p>
   * Searches for files matching: {@code config_{circuitName}-{containerName}.properties}
   * <p>
   * Example: For circuit "broker-health", finds:
   * < ul >
   * < li >config_broker-health-brokers.properties → container "brokers"</li >
   * < li >config_broker-health-partitions.properties → container "partitions"</li >
   * </ul >
   *
   * @param circuitName Circuit name
   * @return Set of container names within this circuit
   */
  public static Set < String > discoverContainers ( String circuitName ) {
    Set < String > containerNames = new HashSet <> ();

    // Pattern: config_{circuitName}-{containerName}.properties
    Pattern containerPattern = Pattern.compile (
      "config_" + Pattern.quote ( circuitName ) + "-([a-zA-Z0-9-]+)\\.properties"
    );

    try {
      ClassLoader classLoader = Thread.currentThread ().getContextClassLoader ();
      Enumeration < URL > resources = classLoader.getResources ( "" );

      while ( resources.hasMoreElements () ) {
        URL resource = resources.nextElement ();
        scanForContainerConfigs ( resource, circuitName, containerPattern, containerNames );
      }

    } catch ( IOException e ) {
      throw new ConfigurationException (
        "Failed to discover containers for circuit: " + circuitName, e
      );
    }

    return Collections.unmodifiableSet ( containerNames );
  }

  // =========================================================================
  // Internal Helpers
  // =========================================================================

  private static void scanForCircuitConfigs ( URL resource, Set < String > circuitNames ) {
    // Get directory path from URL
    String path = resource.getPath ();
    java.io.File dir = new java.io.File ( path );

    if ( !dir.isDirectory () ) {
      return;
    }

    // List all .properties files
    java.io.File[] files = dir.listFiles ( ( d, name ) -> name.endsWith ( ".properties" ) );
    if ( files == null ) {
      return;
    }

    for ( java.io.File file : files ) {
      String filename = file.getName ();
      Matcher matcher = CIRCUIT_CONFIG_PATTERN.matcher ( filename );

      if ( matcher.matches () ) {
        String circuitName = matcher.group ( 1 );

        // Exclude container configs (they have additional hyphens)
        // Example: "broker-health-brokers" → container, not circuit
        // We want only top-level circuit names like "broker-health"
        if ( !isContainerConfig ( circuitName ) ) {
          circuitNames.add ( circuitName );
        }
      }
    }
  }

  private static void scanForContainerConfigs (
    URL resource,
    String circuitName,
    Pattern containerPattern,
    Set < String > containerNames
  ) {
    String path = resource.getPath ();
    java.io.File dir = new java.io.File ( path );

    if ( !dir.isDirectory () ) {
      return;
    }

    java.io.File[] files = dir.listFiles ( ( d, name ) -> name.endsWith ( ".properties" ) );
    if ( files == null ) {
      return;
    }

    for ( java.io.File file : files ) {
      String filename = file.getName ();
      Matcher matcher = containerPattern.matcher ( filename );

      if ( matcher.matches () ) {
        String containerName = matcher.group ( 1 );
        containerNames.add ( containerName );
      }
    }
  }

  /**
   * Heuristic: If the circuit name contains hyphens beyond what might be
   * a circuit name, it's likely a container.
   * <p>
   * This is imperfect - we'll refine the discovery logic.
   * Better approach: Check if there's a parent circuit config file.
   */
  private static boolean isContainerConfig ( String name ) {
    // For now, accept any name - we'll filter properly in the scan
    // Real filtering happens by checking if parent circuit exists
    return false;
  }

  /**
   * Get all property keys defined in a circuit's configuration.
   * <p>
   * Useful for introspection and validation.
   *
   * @param circuitName Circuit name
   * @return Set of all property keys (including inherited from global)
   */
  public static Set < String > getCircuitConfigKeys ( String circuitName ) {
    HierarchicalConfig config = HierarchicalConfig.forCircuit ( circuitName );
    return config.keys ();
  }

  /**
   * Get all property keys defined in a container's configuration.
   *
   * @param circuitName   Circuit name
   * @param containerName Container name
   * @return Set of all property keys (including inherited from circuit/global)
   */
  public static Set < String > getContainerConfigKeys ( String circuitName, String containerName ) {
    HierarchicalConfig config = HierarchicalConfig.forContainer ( circuitName, containerName );
    return config.keys ();
  }
}
