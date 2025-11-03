package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.HierarchicalName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.humainary.substrates.api.Substrates.Composer.pipe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SingleThreadCircuit (RC1 API compliant).
 * 
 * Note: Clock and tap() APIs were removed in RC1, so those tests are deleted.
 */
class SingleThreadCircuitTest {
  private SingleThreadCircuit circuit;

  @AfterEach
  void cleanup() {
    if (circuit != null) {
      circuit.close();
    }
  }

  @Test
  void shouldCreateCircuitWithName() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test-circuit"));

    assertThat((Object) circuit).isNotNull();
    assertThat((Object) circuit.subject()).isNotNull();
    assertThat(circuit.subject().type()).isEqualTo(Circuit.class);
  }

  // RC1: Circuit no longer extends Source< State > - test removed

  // RC1: Clock API removed - tests deleted

  @Test
  void shouldRequireNonNullName() {
    assertThatThrownBy(() -> new SingleThreadCircuit(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("Circuit name cannot be null");
  }

  @Test
  void shouldRequireNonNullConduitName() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    assertThatThrownBy(() -> circuit.conduit(null, pipe()))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("Conduit name cannot be null");
  }

  @Test
  void shouldRequireNonNullComposer() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    assertThatThrownBy(() -> circuit.conduit(HierarchicalName.of("test"), null))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("Composer cannot be null");
  }

  // RC1: tap() API removed - tests deleted

  @Test
  void shouldAllowMultipleCloses() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    circuit.close();
    circuit.close(); // Should not throw

    assertThat((Object) circuit).isNotNull();
  }

  @Test
  void shouldCreateDifferentConduitsForDifferentComposers() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    // Same name, different composers should create DIFFERENT Conduits
    Composer< String, Pipe< String >> composer1 = pipe();
    Composer< String, Pipe< String >> composer2 = channel -> channel.pipe();

    Conduit< Pipe< String >, String > conduit1 = circuit.conduit(HierarchicalName.of("shared"), composer1);
    Conduit< Pipe< String >, String > conduit2 = circuit.conduit(HierarchicalName.of("shared"), composer2);

    assertThat((Object) conduit1).isNotSameAs(conduit2);
  }

  @Test
  void shouldCacheConduitsWithSameNameAndComposer() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    Composer< String, Pipe< String >> composer = pipe();

    Conduit< Pipe< String >, String > conduit1 = circuit.conduit(HierarchicalName.of("cached"), composer);
    Conduit< Pipe< String >, String > conduit2 = circuit.conduit(HierarchicalName.of("cached"), composer);

    assertThat((Object) conduit1).isSameAs(conduit2);
  }

  @Test
  void shouldProvideAccessToSubject() {
    circuit = new SingleThreadCircuit(HierarchicalName.of("test"));

    assertThat((Object) circuit.subject()).isNotNull();
    assertThat((Object) circuit).isNotNull();
  }
}
