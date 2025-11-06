package io.fullerstack.kafka.runtime.gauge;

import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for GaugeFlowCircuit demonstrating RC5 Gauges API usage.
 * <p>
 * Tests realistic Kafka connection pool monitoring scenario with:
 * <ul>
 *   <li>Connection establishment (INCREMENT)</li>
 *   <li>Connection closure (DECREMENT)</li>
 *   <li>Capacity saturation (OVERFLOW)</li>
 *   <li>Resource depletion (UNDERFLOW)</li>
 *   <li>Pool recycling (RESET)</li>
 * </ul>
 */
class GaugeFlowCircuitTest {

  private GaugeFlowCircuit circuit;
  private Gauge connectionGauge;
  private List < Gauges.Sign > capturedSignals;
  private CountDownLatch signalLatch;

  @BeforeEach
  void setUp () {
    circuit = new GaugeFlowCircuit ();
    connectionGauge = circuit.gaugeFor ( "test.connections" );
    capturedSignals = new ArrayList <> ();
  }

  @AfterEach
  void tearDown () {
    if ( circuit != null ) {
      circuit.close ();
    }
  }

  @Test
  void testConnectionLifecycle () throws InterruptedException {
    // Arrange: Subscribe to capture gauge signals
    signalLatch = new CountDownLatch ( 4 );  // Expect 4 signals
    circuit.subscribe ( "test-observer", ( subject, registrar ) ->
      registrar.register ( sign -> {
        capturedSignals.add ( sign );
        signalLatch.countDown ();
      } )
    );

    // Act: Simulate connection lifecycle
    connectionGauge.increment ();    // Connection opened
    connectionGauge.increment ();    // Another connection opened
    connectionGauge.decrement ();    // Connection closed
    connectionGauge.decrement ();    // Another connection closed

    // Assert: All signals captured
    boolean completed = signalLatch.await ( 5, TimeUnit.SECONDS );
    assertThat ( completed ).isTrue ();

    assertThat ( capturedSignals )
      .hasSize ( 4 )
      .containsExactly (
        Gauges.Sign.INCREMENT,
        Gauges.Sign.INCREMENT,
        Gauges.Sign.DECREMENT,
        Gauges.Sign.DECREMENT
      );
  }

  @Test
  void testCapacitySaturation () throws InterruptedException {
    // Arrange: Subscribe to capture overflow signal
    signalLatch = new CountDownLatch ( 11 );  // 10 increments + 1 overflow
    circuit.subscribe ( "test-observer", ( subject, registrar ) ->
      registrar.register ( sign -> {
        capturedSignals.add ( sign );
        signalLatch.countDown ();
      } )
    );

    // Act: Simulate reaching connection capacity
    for ( int i = 0; i < 10; i++ ) {
      connectionGauge.increment ();    // Connections increasing
    }
    connectionGauge.overflow ();       // Hit maximum capacity

    // Assert: Overflow signal captured
    boolean completed = signalLatch.await ( 5, TimeUnit.SECONDS );
    assertThat ( completed ).isTrue ();

    assertThat ( capturedSignals )
      .hasSize ( 11 )
      .contains ( Gauges.Sign.OVERFLOW );

    // Last signal should be OVERFLOW
    assertThat ( capturedSignals.get ( 10 ) ).isEqualTo ( Gauges.Sign.OVERFLOW );
  }

  @Test
  void testResourceDepletion () throws InterruptedException {
    // Arrange: Build up connections then drain
    signalLatch = new CountDownLatch ( 11 );  // 5 increments + 5 decrements + 1 underflow
    circuit.subscribe ( "test-observer", ( subject, registrar ) ->
      registrar.register ( sign -> {
        capturedSignals.add ( sign );
        signalLatch.countDown ();
      } )
    );

    // Act: Build up then drain connections
    for ( int i = 0; i < 5; i++ ) {
      connectionGauge.increment ();    // Build up connections
    }
    for ( int i = 0; i < 5; i++ ) {
      connectionGauge.decrement ();    // Drain connections
    }
    connectionGauge.underflow ();      // Attempted to go below minimum

    // Assert: Underflow signal captured
    boolean completed = signalLatch.await ( 5, TimeUnit.SECONDS );
    assertThat ( completed ).isTrue ();

    assertThat ( capturedSignals )
      .hasSize ( 11 )
      .contains ( Gauges.Sign.UNDERFLOW );

    // Last signal should be UNDERFLOW
    assertThat ( capturedSignals.get ( 10 ) ).isEqualTo ( Gauges.Sign.UNDERFLOW );
  }

  @Test
  void testPoolRecycling () throws InterruptedException {
    // Arrange: Subscribe to capture reset signal
    signalLatch = new CountDownLatch ( 6 );  // 5 increments + 1 reset
    circuit.subscribe ( "test-observer", ( subject, registrar ) ->
      registrar.register ( sign -> {
        capturedSignals.add ( sign );
        signalLatch.countDown ();
      } )
    );

    // Act: Build up connections then reset pool
    for ( int i = 0; i < 5; i++ ) {
      connectionGauge.increment ();    // Active connections
    }
    connectionGauge.reset ();          // Pool recycled/reset

    // Assert: Reset signal captured
    boolean completed = signalLatch.await ( 5, TimeUnit.SECONDS );
    assertThat ( completed ).isTrue ();

    assertThat ( capturedSignals )
      .hasSize ( 6 )
      .contains ( Gauges.Sign.RESET );

    // Last signal should be RESET
    assertThat ( capturedSignals.get ( 5 ) ).isEqualTo ( Gauges.Sign.RESET );
  }

  @Test
  void testMultipleGauges () throws InterruptedException {
    // Arrange: Create multiple gauges for different connection pools
    Gauge brokerConnections = circuit.gaugeFor ( "broker.connections" );
    Gauge consumerConnections = circuit.gaugeFor ( "consumer.connections" );

    signalLatch = new CountDownLatch ( 4 );  // 2 signals per gauge
    List < String > capturedEntities = new ArrayList <> ();

    circuit.subscribe ( "test-observer", ( subject, registrar ) ->
      registrar.register ( sign -> {
        capturedEntities.add ( subject.name ().toString () );
        signalLatch.countDown ();
      } )
    );

    // Act: Emit from both gauges
    brokerConnections.increment ();
    brokerConnections.decrement ();
    consumerConnections.increment ();
    consumerConnections.decrement ();

    // Assert: Signals from both gauges captured
    boolean completed = signalLatch.await ( 5, TimeUnit.SECONDS );
    assertThat ( completed ).isTrue ();

    assertThat ( capturedEntities )
      .hasSize ( 4 )
      .containsExactly (
        "broker.connections",
        "broker.connections",
        "consumer.connections",
        "consumer.connections"
      );
  }
}
