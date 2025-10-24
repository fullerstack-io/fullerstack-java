/*
 * Copyright (c) 2025 William David Louth
 */

package io.fullerstack.substrates.authoritative;

import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.fullerstack.substrates.CortexRuntime;

import static io.humainary.substrates.api.Substrates.Composer.pipe;
import static org.junit.jupiter.api.Assertions.*;

/// Comprehensive tests for the Circuit interface.
///
/// This test class covers:
/// - Circuit creation and lifecycle
/// - Conduit creation with various overloads
/// - Clock creation and management
/// - Cell creation (experimental API)
/// - Threading model and await semantics
/// - Resource management and cleanup
/// - Integration with other components
///
/// @author wlouth
/// @since 1.0

final class CircuitTest {

  private Cortex cortex;

  @BeforeEach
  void setup () {

    cortex = new CortexRuntime();

  }

  // ===========================
  // Circuit Creation Tests
  // ===========================

  @Test
  void testAwaitAfterClosureUsesFastPath () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < String >, String > conduit =
        circuit.conduit ( pipe () );

      final Pipe < String > pipe =
        conduit.get ( cortex.name ( "valve.fastpath.channel" ) );

      pipe.emit ( "first" );
      pipe.emit ( "second" );

      circuit.await ();

      circuit.close ();
      circuit.await ();

      final Duration fastPathBudget = Duration.ofMillis ( 200L );

      assertTimeoutPreemptively (
        fastPathBudget,
        circuit::await,
        "await() should short-circuit once the circuit is closed"
      );

      // Subsequent invocations should remain fast even after the first post-close await.
      assertTimeoutPreemptively (
        fastPathBudget,
        circuit::await,
        "await() should consistently use the closed fast-path"
      );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testAwaitCompletesWhenQueueEmpty () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit ( pipe () );

      conduit.get ( cortex.name ( "test.channel" ) )
        .emit ( 42 );

      // Should complete when queue is drained
      circuit.await ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testAwaitFromExternalThread () throws InterruptedException {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit ( pipe () );

      final var latch = new CountDownLatch ( 1 );

      final var thread = new Thread ( () -> {
        try {

          final Pipe < Integer > pipe =
            conduit.get ( cortex.name ( "async.channel" ) );

          pipe.emit ( 100 );

          circuit.await ();

          latch.countDown ();

        } catch ( final Exception ignored ) {
        }
      } );

      thread.start ();

      assertTrue (
        latch.await ( 5, TimeUnit.SECONDS ),
        "await should complete"
      );

      thread.join ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testAwaitOnCircuitThreadThrowsIllegalStateException () {

    final var circuit = cortex.circuit ();

    try {

      final AtomicReference < Throwable > captured = new AtomicReference <> ();
      final AtomicReference < Thread > workerThread = new AtomicReference <> ();

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit (
          cortex.name ( "valve.await.conduit" ),
          pipe ()
        );

      final Subscriber < Integer > subscriber =
        cortex.subscriber (
          cortex.name ( "valve.await.subscriber" ),
          ( _, registrar ) ->
            registrar.register (
              _ -> {
                workerThread.set ( Thread.currentThread () );
                try {
                  circuit.await ();
                } catch ( final IllegalStateException ex ) {
                  captured.set ( ex );
                }
              }
            )
        );

      final var subscription =
        conduit.subscribe ( subscriber );

      final Pipe < Integer > pipe =
        conduit.get ( cortex.name ( "valve.await.channel" ) );

      pipe.emit ( 1 );

      circuit.await ();

      subscription.close ();

      final var thrown = captured.get ();

      assertNotNull ( thrown, "await() on the circuit thread should throw" );
      assertEquals ( IllegalStateException.class, thrown.getClass () );
      assertEquals (
        "Cannot call Circuit::await from within a circuit's thread",
        thrown.getMessage ()
      );
      assertNotNull ( workerThread.get (), "Subscriber should execute on circuit worker thread" );

    } finally {

      circuit.close ();

    }

  }

  // ===========================
  // Conduit Creation Tests
  // ===========================

  @Test
  void testAwaitWithMultipleEmissions () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < String >, String > conduit =
        circuit.conduit ( pipe () );

      final Pipe < String > pipe =
        conduit.get ( cortex.name ( "multi.emit.channel" ) );

      pipe.emit ( "first" );
      pipe.emit ( "second" );
      pipe.emit ( "third" );

      circuit.await ();

    } finally {

      circuit.close ();

    }

  }

  @SuppressWarnings ( "DataFlowIssue" )
  @Test
  void testCellCreationNullGuards () {

    final var circuit = cortex.circuit ();

    try {

      assertThrows (
        NullPointerException.class,
        () -> circuit.cell ( (Composer < Pipe < Integer >, Integer >) null )
      );

      assertThrows (
        NullPointerException.class,
        () -> circuit.cell ( pipe (), null )
      );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCellCreationWithComposer () {

    final var circuit = cortex.circuit ();

    try {

      final Cell < Integer, Integer > cell =
        circuit.cell ( pipe () );

      assertNotNull ( cell );
      assertNotNull ( cell.subject () );
      assertEquals ( Cell.class, cell.subject ().type () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCellCreationWithComposerAndConfigurer () {

    final var circuit = cortex.circuit ();

    try {

      final Cell < String, String > cell =
        circuit.cell (
          pipe (),
          flow -> flow.limit ( 5 )
        );

      assertNotNull ( cell );
      assertNotNull ( cell.subject () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitCloseIsIdempotent () {

    final var circuit = cortex.circuit ();

    circuit.close ();
    circuit.close (); // Should not throw
    circuit.close (); // Should not throw

  }

  // ===========================
  // Clock Creation Tests
  // ===========================

  @Test
  void testCircuitCloseReleasesResources () {

    final var circuit = cortex.circuit ();

    final Conduit < Pipe < Integer >, Integer > conduit =
      circuit.conduit ( pipe () );

    assertNotNull ( conduit );

    circuit.close ();

    // Circuit is closed, but we can't really verify internal state
    // This test mainly ensures close doesn't throw

  }

  @SuppressWarnings ( "DataFlowIssue" )
  @Test
  void testCircuitCreationNullNameGuard () {

    assertThrows (
      NullPointerException.class,
      () -> cortex.circuit ( null )
    );

  }

  @Test
  void testCircuitCreationWithName () {

    final var circuitName = cortex.name ( "circuit.test.named" );
    final var circuit = cortex.circuit ( circuitName );

    assertNotNull ( circuit );
    assertEquals ( circuitName, circuit.subject ().name () );
    assertEquals ( Circuit.class, circuit.subject ().type () );

    circuit.close ();

  }

  @Test
  void testCircuitCreationWithoutName () {

    final var circuit = cortex.circuit ();

    assertNotNull ( circuit );
    assertNotNull ( circuit.subject () );
    assertNotNull ( circuit.subject ().name () );
    assertNotNull ( circuit.subject ().id () );
    assertEquals ( Circuit.class, circuit.subject ().type () );

    circuit.close ();

  }

  // ===========================
  // Cell Creation Tests (Experimental)
  // ===========================

  @Test
  void testCircuitEventOrdering () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit ( pipe () );

      final List < Integer > emissions = new ArrayList <> ();

      final Subscriber < Integer > subscriber =
        cortex.subscriber (
          cortex.name ( "ordering.subscriber" ),
          ( _, registrar ) ->
            registrar.register ( emissions::add )
        );

      conduit.subscribe ( subscriber );

      final Pipe < Integer > pipe =
        conduit.get ( cortex.name ( "ordering.channel" ) );

      pipe.emit ( 1 );
      pipe.emit ( 2 );
      pipe.emit ( 3 );
      pipe.emit ( 4 );
      pipe.emit ( 5 );

      circuit.await ();

      assertEquals ( List.of ( 1, 2, 3, 4, 5 ), emissions );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitProvidesSource () {

    final var circuit = cortex.circuit ();

    try {

      // Circuit implements Source directly through Context

      assertNotNull ( circuit );
      assertNotNull ( circuit.subject () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitSourceSubscription () {

    final var circuit = cortex.circuit ();

    try {

      final var subjects = new ArrayList < Subject < ? > > ();

      final Subscriber < State > subscriber =
        cortex.subscriber (
          cortex.name ( "circuit.source.subscriber" ),
          ( subject, registrar ) -> {
            subjects.add ( subject );
            registrar.register ( Pipe.empty () );
          }
        );

      final var subscription = circuit.subscribe ( subscriber );

      assertNotNull ( subscription );

      subscription.close ();

    } finally {

      circuit.close ();

    }

  }

  // ===========================
  // Circuit.await() Tests
  // ===========================

  @Test
  void testCircuitSubjectState () {

    final var circuit = cortex.circuit (
      cortex.name ( "circuit.state.test" )
    );

    try {

      final Subject < ? > subject = circuit.subject ();
      final var state = subject.state ();

      assertNotNull ( state );
      // State should be empty for a newly created circuit
      assertEquals ( 0, state.stream ().count () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitTapOperation () {

    final var circuit = cortex.circuit ();

    try {

      final var tapped = new AtomicBoolean ( false );

      circuit.tap ( c -> {
        assertNotNull ( c );
        assertSame ( circuit, c );
        tapped.set ( true );
      } );

      assertTrue ( tapped.get () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitWithCell () {

    final var circuit = cortex.circuit ();

    try {

      final List < String > emissions = new ArrayList <> ();

      final Cell < String, String > cell =
        circuit.cell (
          channel -> channel.pipe ( flow -> flow.forward ( emissions::add ) )
        );

      final Pipe < String > pipe = cell.get (
        cortex.name ( "cell.channel" )
      );

      pipe.emit ( "cell-test-1" );
      pipe.emit ( "cell-test-2" );

      circuit.await ();

      assertEquals ( 2, emissions.size () );
      assertTrue ( emissions.contains ( "cell-test-1" ) );
      assertTrue ( emissions.contains ( "cell-test-2" ) );

    } finally {

      circuit.close ();

    }

  }

  // ===========================
  // Circuit.close() Tests
  // ===========================

  @Test
  void testCircuitWithClock () {

    final var circuit = cortex.circuit ();

    try {

      final var clock = circuit.clock (
        cortex.name ( "circuit.test.clock" )
      );

      final var tickCount = new AtomicInteger ( 0 );

      final var subscription = clock.consume (
        cortex.name ( "millisecond.tick" ),
        Clock.Cycle.MILLISECOND,
        _ -> tickCount.incrementAndGet ()
      );

      assertNotNull ( subscription );

      // Let some time pass for ticks
      try {
        Thread.sleep ( 10 );
      } catch ( final InterruptedException ignored ) {
      }

      circuit.await ();

      subscription.close ();
      clock.close ();

      // Should have received at least one tick
      assertTrue ( tickCount.get () > 0 );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitWithConduitAndSink () {

    final var circuit = cortex.circuit (
      cortex.name ( "integration.circuit" )
    );

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit (
          cortex.name ( "integration.conduit" ),
          pipe ()
        );

      final Sink < Integer > sink = cortex.sink ( conduit );

      final Pipe < Integer > pipe =
        conduit.get ( cortex.name ( "integration.channel" ) );

      pipe.emit ( 10 );
      pipe.emit ( 20 );
      pipe.emit ( 30 );

      circuit.await ();

      final var captures =
        sink.drain ().toList ();

      assertEquals ( 3, captures.size () );
      assertEquals ( 10, captures.get ( 0 ).emission () );
      assertEquals ( 20, captures.get ( 1 ).emission () );
      assertEquals ( 30, captures.get ( 2 ).emission () );

      sink.close ();

    } finally {

      circuit.close ();

    }

  }

  // ===========================
  // Source Access Tests
  // ===========================

  @Test
  void testCircuitWithFlowConfiguration () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit (
          cortex.name ( "flow.conduit" ),
          pipe (),
          flow -> flow.limit ( 2 )
        );

      final Sink < Integer > sink = cortex.sink ( conduit );

      final Pipe < Integer > pipe =
        conduit.get ( cortex.name ( "flow.channel" ) );

      pipe.emit ( 1 );
      pipe.emit ( 2 );
      pipe.emit ( 3 ); // Should be limited

      circuit.await ();

      final var captures =
        sink.drain ().toList ();

      // Limit should restrict to first 2 emissions
      assertEquals ( 2, captures.size () );

      sink.close ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testCircuitWithMultipleConduitsAndChannels () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < String >, String > conduit1 =
        circuit.conduit ( cortex.name ( "conduit.one" ), pipe () );

      final Conduit < Pipe < Integer >, Integer > conduit2 =
        circuit.conduit ( cortex.name ( "conduit.two" ), pipe () );

      final Sink < String > sink1 = cortex.sink ( conduit1 );
      final Sink < Integer > sink2 = cortex.sink ( conduit2 );

      final Pipe < String > pipe1 =
        conduit1.get ( cortex.name ( "channel.alpha" ) );

      final Pipe < Integer > pipe2 =
        conduit2.get ( cortex.name ( "channel.beta" ) );

      pipe1.emit ( "hello" );
      pipe2.emit ( 42 );

      circuit.await ();

      assertEquals ( 1, sink1.drain ().count () );
      assertEquals ( 1, sink2.drain ().count () );

      sink1.close ();
      sink2.close ();

    } finally {

      circuit.close ();

    }

  }

  // ===========================
  // Integration Tests
  // ===========================

  @SuppressWarnings ( "DataFlowIssue" )
  @Test
  void testClockCreationNullNameGuard () {

    final var circuit = cortex.circuit ();

    try {

      assertThrows (
        NullPointerException.class,
        () -> circuit.clock ( null )
      );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testClockCreationWithName () {

    final var circuit = cortex.circuit ();

    try {

      final var clockName = cortex.name ( "circuit.test.clock" );
      final var clock = circuit.clock ( clockName );

      assertNotNull ( clock );
      assertEquals ( clockName, clock.subject ().name () );
      assertEquals ( Clock.class, clock.subject ().type () );

      clock.close ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testClockCreationWithoutName () {

    final var circuit = cortex.circuit ();

    try {

      final var clock = circuit.clock ();

      assertNotNull ( clock );
      assertNotNull ( clock.subject () );
      assertEquals ( Clock.class, clock.subject ().type () );

      clock.close ();

    } finally {

      circuit.close ();

    }

  }

  @SuppressWarnings ( "DataFlowIssue" )
  @Test
  void testConduitCreationNullGuards () {

    final var circuit = cortex.circuit ();

    try {

      final var name = cortex.name ( "test" );

      assertThrows (
        NullPointerException.class,
        () -> circuit.conduit ( (Composer < ?, ? >) null )
      );

      assertThrows (
        NullPointerException.class,
        () -> circuit.conduit ( null, pipe () )
      );

      assertThrows (
        NullPointerException.class,
        () -> circuit.conduit ( name, null )
      );

      assertThrows (
        NullPointerException.class,
        () -> circuit.conduit ( name, pipe (), null )
      );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testConduitCreationWithComposer () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit =
        circuit.conduit ( pipe () );

      assertNotNull ( conduit );
      assertNotNull ( conduit.subject () );
      assertEquals ( Conduit.class, conduit.subject ().type () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testConduitCreationWithNameAndComposer () {

    final var circuit = cortex.circuit ();

    try {

      final var conduitName = cortex.name ( "circuit.test.conduit" );
      final Conduit < Pipe < String >, String > conduit =
        circuit.conduit ( conduitName, pipe () );

      assertNotNull ( conduit );
      assertEquals ( conduitName, conduit.subject ().name () );
      assertEquals ( Conduit.class, conduit.subject ().type () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testConduitCreationWithNameComposerAndConfigurer () {

    final var circuit = cortex.circuit ();

    try {

      final var conduitName = cortex.name ( "circuit.test.conduit.configured" );
      final Conduit < Pipe < Double >, Double > conduit =
        circuit.conduit (
          conduitName,
          pipe (),
          flow -> flow.limit ( 10 )
        );

      assertNotNull ( conduit );
      assertEquals ( conduitName, conduit.subject ().name () );

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testMultipleCircuitsHaveUniqueSubjects () {

    final var circuit1 = cortex.circuit ();
    final var circuit2 = cortex.circuit ();

    try {

      assertNotSame ( circuit1.subject (), circuit2.subject () );
      assertNotEquals ( circuit1.subject ().id (), circuit2.subject ().id () );

    } finally {

      circuit1.close ();
      circuit2.close ();

    }

  }

  @Test
  void testMultipleClocksFromSameCircuit () {

    final var circuit = cortex.circuit ();

    try {

      final var clock1 = circuit.clock ();
      final var clock2 = circuit.clock ();

      assertNotSame ( clock1, clock2 );
      assertNotSame ( clock1.subject (), clock2.subject () );

      clock1.close ();
      clock2.close ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testMultipleConduitsFromSameCircuit () {

    final var circuit = cortex.circuit ();

    try {

      final Conduit < Pipe < Integer >, Integer > conduit1 =
        circuit.conduit ( pipe () );

      final Conduit < Pipe < Integer >, Integer > conduit2 =
        circuit.conduit ( pipe () );

      assertNotSame ( conduit1, conduit2 );
      assertNotSame ( conduit1.subject (), conduit2.subject () );

    } finally {

      circuit.close ();

    }

  }

}
