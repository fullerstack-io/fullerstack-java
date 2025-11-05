package io.fullerstack.kafka.core.monitors;

import io.humainary.substrates.ext.serventis.Gauges.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors Kafka connection pool utilization and emits gauge signals using RC5 Serventis API.
 * <p>
 * Collects JMX metrics for active connections and emits signals based on pool state:
 * <ul>
 *   <li><b>INCREMENT</b>: Connection established</li>
 *   <li><b>DECREMENT</b>: Connection closed</li>
 *   <li><b>OVERFLOW</b>: Hit maximum connection capacity</li>
 *   <li><b>UNDERFLOW</b>: Connections dropped below minimum</li>
 *   <li><b>RESET</b>: Connection pool recycled</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * GaugeFlowCircuit circuit = new GaugeFlowCircuit();
 * Gauge connectionGauge = circuit.gaugeFor("broker-1.connections");
 *
 * ConnectionPoolGaugeMonitor monitor = new ConnectionPoolGaugeMonitor(
 *     "broker-1",
 *     "localhost:9999",     // JMX endpoint
 *     connectionGauge,
 *     100                   // Max connections
 * );
 *
 * monitor.start();  // Begins monitoring every 5 seconds
 *
 * // Later...
 * monitor.stop();
 * }</pre>
 *
 * <h3>Observability Value:</h3>
 * <ul>
 *   <li>Track connection pool utilization over time</li>
 *   <li>Detect capacity saturation (OVERFLOW)</li>
 *   <li>Identify connection leaks (continuous INCREMENT without DECREMENT)</li>
 *   <li>Monitor pool recycling events (RESET)</li>
 * </ul>
 *
 * @author Fullerstack
 * @see Gauge
 */
public class ConnectionPoolGaugeMonitor implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger ( ConnectionPoolGaugeMonitor.class );

  // Thresholds
  private static final double OVERFLOW_THRESHOLD = 0.95;   // 95%+ of max = overflow
  private static final double UNDERFLOW_THRESHOLD = 0.10;  // <10% = potential underutilization

  private final String entityId;
  private final String jmxEndpoint;
  private final Gauge connectionGauge;
  private final int maxConnections;

  private final ScheduledExecutorService scheduler;
  private JMXConnector jmxConnector;
  private MBeanServerConnection mbeanServer;
  private volatile boolean running = false;

  private int previousConnectionCount = 0;

  /**
   * Creates a new connection pool gauge monitor.
   *
   * @param entityId          Entity identifier (e.g., "broker-1", "producer-1")
   * @param jmxEndpoint       JMX endpoint (e.g., "localhost:9999")
   * @param connectionGauge   Gauge instrument for emitting connection signals
   * @param maxConnections    Maximum allowed connections (for overflow detection)
   */
  public ConnectionPoolGaugeMonitor (
    String entityId,
    String jmxEndpoint,
    Gauge connectionGauge,
    int maxConnections
  ) {
    this.entityId = entityId;
    this.jmxEndpoint = jmxEndpoint;
    this.connectionGauge = connectionGauge;
    this.maxConnections = maxConnections;
    this.scheduler = Executors.newSingleThreadScheduledExecutor ( r -> {
      Thread t = new Thread ( r, "connection-pool-monitor-" + entityId );
      t.setDaemon ( true );
      return t;
    } );
  }

  /**
   * Starts connection pool monitoring.
   * <p>
   * Connects to JMX and schedules pool checks every 5 seconds.
   */
  public void start () {
    if ( running ) {
      logger.warn ( "Connection pool monitor for {} is already running", entityId );
      return;
    }

    try {
      connectJmx ();
      running = true;

      // Schedule connection monitoring every 5 seconds
      scheduler.scheduleAtFixedRate (
        this::collectAndEmit,
        0,          // Initial delay
        5,          // Period
        TimeUnit.SECONDS
      );

      logger.info ( "Started connection pool monitor for {} (JMX: {})", entityId, jmxEndpoint );

    } catch ( Exception e ) {
      logger.error ( "Failed to start connection pool monitor for {}", entityId, e );
      running = false;
      throw new RuntimeException ( "Failed to start connection pool monitor", e );
    }
  }

  /**
   * Stops connection pool monitoring and releases resources.
   */
  public void stop () {
    if ( !running ) {
      return;
    }

    running = false;
    scheduler.shutdown ();

    try {
      if ( !scheduler.awaitTermination ( 5, TimeUnit.SECONDS ) ) {
        scheduler.shutdownNow ();
      }
    } catch ( InterruptedException e ) {
      scheduler.shutdownNow ();
      Thread.currentThread ().interrupt ();
    }

    closeJmx ();
    logger.info ( "Stopped connection pool monitor for {}", entityId );
  }

  @Override
  public void close () {
    stop ();
  }

  // ========================================
  // JMX Connection Management
  // ========================================

  private void connectJmx () throws IOException {
    String serviceUrl = "service:jmx:rmi:///jndi/rmi://" + jmxEndpoint + "/jmxrmi";
    JMXServiceURL url = new JMXServiceURL ( serviceUrl );
    jmxConnector = JMXConnectorFactory.connect ( url, null );
    mbeanServer = jmxConnector.getMBeanServerConnection ();
    logger.debug ( "Connected to JMX endpoint: {}", jmxEndpoint );
  }

  private void closeJmx () {
    if ( jmxConnector != null ) {
      try {
        jmxConnector.close ();
        logger.debug ( "Closed JMX connection to {}", jmxEndpoint );
      } catch ( IOException e ) {
        logger.warn ( "Error closing JMX connection to {}", jmxEndpoint, e );
      }
    }
  }

  // ========================================
  // Connection Metrics Collection & Emission
  // ========================================

  private void collectAndEmit () {
    try {
      // Collect JMX metrics
      int currentConnections = getConnectionCount ();

      // Calculate delta
      int delta = currentConnections - previousConnectionCount;

      // Emit gauge signals based on delta (RC5 Gauges pattern)
      emitGaugeSignals ( currentConnections, delta );

      // Update previous count
      previousConnectionCount = currentConnections;

    } catch ( Exception e ) {
      logger.error ( "Error collecting connection metrics for entity {}", entityId, e );
      // Emit overflow on error (conservative - assume capacity issue)
      connectionGauge.overflow ();
    }
  }

  private void emitGaugeSignals ( int currentConnections, int delta ) {
    // Calculate utilization
    double utilization = (double) currentConnections / maxConnections;

    // Emit INCREMENT/DECREMENT based on delta
    if ( delta > 0 ) {
      // Connections increased
      for ( int i = 0; i < delta; i++ ) {
        connectionGauge.increment ();
      }
      logger.debug ( "Connections for {} INCREASED by {}: total={}", entityId, delta, currentConnections );

      // Check for overflow (capacity saturation)
      if ( utilization >= OVERFLOW_THRESHOLD ) {
        connectionGauge.overflow ();
        logger.warn ( "Connection pool for {} OVERFLOW: connections={}/{} ({}%)",
          entityId, currentConnections, maxConnections, (int) ( utilization * 100 ) );
      }

    } else if ( delta < 0 ) {
      // Connections decreased
      int absoluteDelta = Math.abs ( delta );
      for ( int i = 0; i < absoluteDelta; i++ ) {
        connectionGauge.decrement ();
      }
      logger.debug ( "Connections for {} DECREASED by {}: total={}", entityId, absoluteDelta, currentConnections );

      // Check for underflow (resource depletion)
      if ( utilization <= UNDERFLOW_THRESHOLD && currentConnections > 0 ) {
        connectionGauge.underflow ();
        logger.warn ( "Connection pool for {} UNDERFLOW: connections={}/{} ({}%)",
          entityId, currentConnections, maxConnections, (int) ( utilization * 100 ) );
      }

    } else {
      // No change - steady state
      logger.trace ( "Connections for {} STABLE: total={}", entityId, currentConnections );
    }
  }

  private int getConnectionCount () throws Exception {
    // Build MBean object name for connection metrics
    // Example: kafka.server:type=socket-server-metrics,listener=PLAINTEXT
    ObjectName objectName = new ObjectName (
      String.format ( "kafka.server:type=socket-server-metrics,listener=PLAINTEXT" )
    );

    // Get connection count metric
    Object value = mbeanServer.getAttribute ( objectName, "connection-count" );

    if ( value instanceof Number ) {
      return ( (Number) value ).intValue ();
    } else {
      throw new IllegalArgumentException (
        "connection-count metric is not a number: " + value
      );
    }
  }

  /**
   * Manually trigger a reset signal (e.g., when connection pool is recycled).
   */
  public void triggerReset () {
    connectionGauge.reset ();
    previousConnectionCount = 0;
    logger.info ( "Connection pool for {} RESET (pool recycled)", entityId );
  }
}
