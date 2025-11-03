package io.fullerstack.substrates.testkit;

import io.humainary.substrates.api.Substrates;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.NotNull;

/// Common support for substrate test kit classes that require access to the singleton cortex.
/// The SPI provider is configured via the testkit Maven profile in the root pom.xml.
abstract class TestSupport {

  /// Returns the shared cortex instance configured via the SPI provider property.
  @NotNull
  protected static Cortex cortex () {

  return
    Substrates.CORTEX;

  }

}
