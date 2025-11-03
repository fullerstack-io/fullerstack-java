package io.fullerstack.substrates.functional;

import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Composers}.
 * <p>
 * < p >These tests demonstrate the CORRECT way to use Substrates via the Cortex API.
 * <p>
 * < p >Note: Hierarchical routing tests removed as that functionality is now built
 * into the Cell implementation via auto-subscription pattern.
 */
class ComposersTest {

  // ========== Filter/Transform Tests ==========

  @Test
  void filter_appliesPredicateToEmissions () {
    // Given: Circuit with filtered conduit using direct Pipe API
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    Conduit < Pipe < Integer >, Integer > conduit = circuit.conduit (
      cortex.name ( "positive" ),
      Channel::pipe,
      path -> path.guard ( n -> n > 0 )
    );

    // When: Emitting positive and negative values
    List < Integer > received = new CopyOnWriteArrayList <> ();

    conduit.subscribe ( cortex.subscriber (
      cortex.name ( "test-subscriber" ),
      ( subject, registrar ) -> registrar.register ( emission -> received.add ( emission ) )
    ) );

    Pipe < Integer > pipe = conduit.get ( cortex.name ( "test" ) );
    pipe.emit ( -5 );
    pipe.emit ( 10 );
    pipe.emit ( -3 );
    pipe.emit ( 20 );

    // Give emissions time to propagate
    try { Thread.sleep ( 100 ); } catch ( InterruptedException e ) { }

    // Then: Only positive values received
    assertThat ( received ).containsExactly ( 10, 20 );
  }

  @Test
  void limit_restrictsEmissionCount () {
    // Given: Circuit with limited conduit using direct Pipe API
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    Conduit < Pipe < String >, String > conduit = circuit.conduit (
      cortex.name ( "limited" ),
      Channel::pipe,
      path -> path.limit ( 3 )
    );

    // When: Emitting more than the limit
    List < String > received = new CopyOnWriteArrayList <> ();

    conduit.subscribe ( cortex.subscriber (
      cortex.name ( "test-subscriber" ),
      ( subject, registrar ) -> registrar.register ( emission -> received.add ( emission ) )
    ) );

    Pipe < String > pipe = conduit.get ( cortex.name ( "test" ) );
    pipe.emit ( "1" );
    pipe.emit ( "2" );
    pipe.emit ( "3" );
    pipe.emit ( "4" );  // Should be dropped
    pipe.emit ( "5" );  // Should be dropped

    // Give emissions time to propagate
    try { Thread.sleep ( 100 ); } catch ( InterruptedException e ) { }

    // Then: Only first 3 emissions received
    assertThat ( received ).hasSize ( 3 );
    assertThat ( received ).containsExactly ( "1", "2", "3" );
  }

  @Test
  void diff_filtersConsecutiveDuplicates () {
    // Given: Circuit with diff sequencer using direct Pipe API
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    Conduit < Pipe < String >, String > conduit = circuit.conduit (
      cortex.name ( "diff" ),
      Composer.pipe (),
      flow -> flow.diff ()
    );

    // When: Emitting duplicate values
    List < String > received = new CopyOnWriteArrayList <> ();

    conduit.subscribe ( cortex.subscriber (
      cortex.name ( "test-subscriber" ),
      ( subject, registrar ) -> registrar.register ( emission -> received.add ( emission ) )
    ) );

    Pipe < String > pipe = conduit.get ( cortex.name ( "test" ) );
    pipe.emit ( "A" );
    pipe.emit ( "A" );  // Duplicate - filtered
    pipe.emit ( "B" );
    pipe.emit ( "B" );  // Duplicate - filtered
    pipe.emit ( "A" );  // Different from previous - passed

    // Give emissions time to propagate
    try { Thread.sleep ( 100 ); } catch ( InterruptedException e ) { }

    // Then: Only changed values received
    assertThat ( received ).containsExactly ( "A", "B", "A" );
  }

  @Test
  void sample_emitsEveryNth () {
    // Given: Circuit with sampling using direct Pipe API
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    Conduit < Pipe < Integer >, Integer > conduit = circuit.conduit (
      cortex.name ( "sampled" ),
      Channel::pipe,
      path -> path.sample ( 3 )  // Every 3rd emission
    );

    // When: Emitting many values
    List < Integer > received = new CopyOnWriteArrayList <> ();

    conduit.subscribe ( cortex.subscriber (
      cortex.name ( "test-subscriber" ),
      ( subject, registrar ) -> registrar.register ( emission -> received.add ( emission ) )
    ) );

    Pipe < Integer > pipe = conduit.get ( cortex.name ( "test" ) );
    for ( int i = 1; i <= 10; i++ ) {
      pipe.emit ( i );
    }

    // Give emissions time to propagate
    try { Thread.sleep ( 100 ); } catch ( InterruptedException e ) { }

    // Then: Only every 3rd value received
    assertThat ( received ).containsExactly ( 3, 6, 9 );
  }

  @Test
  void compose_appliesMultipleTransformations () {
    // Given: Circuit with composed transformations using direct Pipe API
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    Conduit < Pipe < Integer >, Integer > conduit = circuit.conduit (
      cortex.name ( "pipeline" ),
      Channel::pipe,
      path -> path
        .guard ( n -> n > 0 )    // Only positive
        .sample ( 2 )            // Every 2nd
        .limit ( 3 )             // Max 3
    );

    // When: Emitting values
    List < Integer > received = new CopyOnWriteArrayList <> ();

    conduit.subscribe ( cortex.subscriber (
      cortex.name ( "test-subscriber" ),
      ( subject, registrar ) -> registrar.register ( emission -> received.add ( emission ) )
    ) );

    Pipe < Integer > pipe = conduit.get ( cortex.name ( "test" ) );
    pipe.emit ( -1 );  // Filtered (negative)
    pipe.emit ( 1 );   // Filtered (not 2nd)
    pipe.emit ( 2 );   // Passed (positive, 2nd, under limit)
    pipe.emit ( 3 );   // Filtered (not 2nd)
    pipe.emit ( 4 );   // Passed (positive, 2nd, under limit)
    pipe.emit ( 5 );   // Filtered (not 2nd)
    pipe.emit ( 6 );   // Passed (positive, 2nd, under limit)
    pipe.emit ( 7 );   // Dropped (limit reached)

    // Give emissions time to propagate
    try { Thread.sleep ( 100 ); } catch ( InterruptedException e ) { }

    // Then: Pipeline applied correctly
    assertThat ( received ).hasSize ( 3 );
    assertThat ( received ).containsExactly ( 2, 4, 6 );
  }

  // ========== Circuit Configuration Tests ==========

  @Test
  void configure_appliesConfigurationAndReturnsCircuit () {
    // Given: Cortex
    Cortex cortex = CortexRuntime.cortex ();

    // When: Configuring circuit
    Circuit circuit = Composers.configure (
      cortex.circuit ( cortex.name ( "configured" ) ),
      c -> {
        // Configuration applied
        assertThat ( c.subject ().name ().path ().toString () ).isEqualTo ( "configured" );
      }
    );

    // Then: Circuit returned
    assertThat ( circuit ).isNotNull ();
    assertThat ( circuit.subject ().name ().path ().toString () ).isEqualTo ( "configured" );
  }

  @Test
  void tap_allowsSideEffectsWithoutBreakingChain () {
    // Given: Cortex and circuit
    Cortex cortex = CortexRuntime.cortex ();
    Circuit circuit = cortex.circuit ( cortex.name ( "test" ) );

    // When: Tapping for side effects
    Circuit result = Composers.tap ( circuit, c -> {
      // Side effect: verify name
      assertThat ( c.subject ().name ().path ().toString () ).isEqualTo ( "test" );
    } );

    // Then: Same circuit returned
    assertThat ( result ).isSameAs ( circuit );
  }
}
