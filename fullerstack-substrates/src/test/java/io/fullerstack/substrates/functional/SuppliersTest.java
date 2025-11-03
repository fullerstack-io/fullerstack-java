package io.fullerstack.substrates.functional;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Suppliers}.
 */
class SuppliersTest {

  @Test
  void memoized_computesOnlyOnce() {
    // Given: A supplier that increments a counter
    AtomicInteger callCount = new AtomicInteger(0);
    Supplier<Integer> supplier = Suppliers.memoized(() -> callCount.incrementAndGet());

    // When: Called multiple times
    int first = supplier.get();
    int second = supplier.get();
    int third = supplier.get();

    // Then: Computed only once, same value returned
    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(1);
    assertThat(third).isEqualTo(1);
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void memoized_isThreadSafe() throws InterruptedException {
    // Given: A memoized supplier
    AtomicInteger callCount = new AtomicInteger(0);
    Supplier<Integer> supplier = Suppliers.memoized(() -> {
      try {
        Thread.sleep(10); // Simulate expensive computation
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return callCount.incrementAndGet();
    });

    // When: Called concurrently from multiple threads
    Thread t1 = new Thread(supplier::get);
    Thread t2 = new Thread(supplier::get);
    Thread t3 = new Thread(supplier::get);

    t1.start();
    t2.start();
    t3.start();

    t1.join();
    t2.join();
    t3.join();

    // Then: Computed only once despite concurrent access
    assertThat(callCount.get()).isEqualTo(1);
    assertThat(supplier.get()).isEqualTo(1);
  }

  @Test
  void lazy_returnsSupplierIdentity() {
    // Given: A supplier
    Supplier<String> original = () -> "test";

    // When: Wrapped with lazy()
    Supplier<String> lazy = Suppliers.lazy(original);

    // Then: Returns the same supplier
    assertThat(lazy).isSameAs(original);
  }

  @Test
  void of_returnsConstantSupplier() {
    // Given: A constant value
    String value = "constant";

    // When: Creating a supplier with of()
    Supplier<String> supplier = Suppliers.of(value);

    // Then: Always returns the same value
    assertThat(supplier.get()).isEqualTo(value);
    assertThat(supplier.get()).isEqualTo(value);
  }

  @Test
  void compose_appliesFunctionsSequentially() {
    // Given: Two functions
    Supplier<Integer> first = () -> 5;
    java.util.function.Function<Integer, String> second = n -> "Number: " + n;

    // When: Composing them
    Supplier<String> composed = Suppliers.compose(first, second);

    // Then: Functions applied sequentially
    assertThat(composed.get()).isEqualTo("Number: 5");
  }

  @Test
  void compose_withMemoization() {
    // Given: A memoized supplier and a transformation
    AtomicInteger callCount = new AtomicInteger(0);
    Supplier<Integer> memoized = Suppliers.memoized(() -> callCount.incrementAndGet());
    Supplier<String> composed = Suppliers.compose(memoized, n -> "Value: " + n);

    // When: Called multiple times
    String first = composed.get();
    String second = composed.get();

    // Then: Memoized supplier called only once
    assertThat(first).isEqualTo("Value: 1");
    assertThat(second).isEqualTo("Value: 1");
    assertThat(callCount.get()).isEqualTo(1);
  }
}
