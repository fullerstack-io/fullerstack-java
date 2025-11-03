package io.fullerstack.substrates.functional;

import lombok.experimental.UtilityClass;

import java.util.function.Supplier;

/**
 * Functional utilities for working with Suppliers.
 * <p>
 * < p >Provides lazy evaluation and memoization patterns for deferred computation.
 * <p>
 * < h3 >Example Usage:</h3 >
 * < pre >{@code
 * // Lazy evaluation - computed only when get() is called
 * Supplier< Pipe< MonitorSignal >> lazy = Suppliers.lazy(() ->
 * createExpensivePipe(subject, queue)
 * );
 * <p>
 * // Memoization - computed once, cached for subsequent calls
 * Supplier< Cortex > memoized = Suppliers.memoized(() ->
 * Cortex.create()
 * );
 * }</pre >
 *
 * @see Supplier
 */
@UtilityClass
public class Suppliers {

  /**
   * Returns a memoized supplier that computes its value once and caches it.
   * <p>
   * < p >Thread-safe double-checked locking ensures the supplier is invoked at most once,
   * even when called concurrently from multiple threads.
   * <p>
   * < p >< b >Use case:</b > Expensive initialization that should happen lazily but only once.
   *
   * @param supplier the supplier to memoize
   * @param <        T > the type of value supplied
   * @return a memoized supplier
   */
  public static < T > Supplier < T > memoized ( Supplier < T > supplier ) {
    return new Supplier <> () {
      private volatile T cached;

      @Override
      public T get () {
        // Double-checked locking for thread-safe lazy initialization
        if ( cached == null ) {
          synchronized ( this ) {
            if ( cached == null ) {
              cached = supplier.get ();
            }
          }
        }
        return cached;
      }
    };
  }

  /**
   * Returns a lazy supplier that wraps the given supplier.
   * <p>
   * < p >This is semantically identical to the input supplier but provides
   * explicit documentation that the value is computed lazily.
   * <p>
   * < p >< b >Use case:</b > Document intent when deferring computation.
   *
   * @param supplier the supplier to wrap
   * @param <        T > the type of value supplied
   * @return the same supplier (identity function)
   */
  public static < T > Supplier < T > lazy ( Supplier < T > supplier ) {
    return supplier;
  }

  /**
   * Returns a supplier that always returns the given constant value.
   * <p>
   * < p >< b >Use case:</b > Convert a value into a Supplier for API compatibility.
   *
   * @param value the constant value to supply
   * @param <     T > the type of value supplied
   * @return a supplier that always returns the given value
   */
  public static < T > Supplier < T > of ( T value ) {
    return () -> value;
  }

  /**
   * Composes two suppliers sequentially.
   * <p>
   * < p >The second supplier receives the result of the first supplier as input.
   *
   * @param first  the first supplier
   * @param second a function that takes the result of the first supplier
   * @param <      T > the type of the first supplier's result
   * @param <      R > the type of the second supplier's result
   * @return a composed supplier
   */
  public static < T, R > Supplier < R > compose ( Supplier < T > first, java.util.function.Function < T, R > second ) {
    return () -> second.apply ( first.get () );
  }
}
