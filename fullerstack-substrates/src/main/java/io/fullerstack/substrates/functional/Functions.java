package io.fullerstack.substrates.functional;

import lombok.experimental.UtilityClass;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Functional utilities for composing and transforming functions.
 *
 * <p>Provides function composition, partial application, and predicate combinators.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Function composition
 * Function<String, Integer> length = String::length;
 * Function<Integer, Boolean> isEven = n -> n % 2 == 0;
 * Function<String, Boolean> isEvenLength = Functions.compose(length, isEven);
 *
 * // Partial application
 * BiFunction<String, Integer, String> substring = String::substring;
 * Function<Integer, String> substringFrom = Functions.partial(substring, "hello");
 *
 * // Predicate combinators
 * Predicate<Integer> isPositive = n -> n > 0;
 * Predicate<Integer> isEven = n -> n % 2 == 0;
 * Predicate<Integer> isPositiveAndEven = Functions.and(isPositive, isEven);
 * }</pre>
 */
@UtilityClass
public class Functions {

    /**
     * Composes two functions sequentially.
     *
     * <p>Returns a function that first applies {@code first}, then applies {@code second}
     * to the result.
     *
     * @param first the first function to apply
     * @param second the second function to apply
     * @param <T> the input type
     * @param <U> the intermediate type
     * @param <R> the result type
     * @return the composed function
     */
    public static <T, U, R> Function<T, R> compose(Function<T, U> first, Function<U, R> second) {
        return first.andThen(second);
    }

    /**
     * Composes multiple functions sequentially.
     *
     * @param functions the functions to compose (in order)
     * @param <T> the input and output type (must be consistent)
     * @return the composed function
     */
    @SafeVarargs
    public static <T> Function<T, T> compose(Function<T, T>... functions) {
        Function<T, T> result = Function.identity();
        for (Function<T, T> f : functions) {
            result = result.andThen(f);
        }
        return result;
    }

    /**
     * Partially applies a BiFunction, fixing the first argument.
     *
     * @param biFunction the binary function
     * @param arg1 the first argument to fix
     * @param <T> the type of the first argument
     * @param <U> the type of the second argument
     * @param <R> the result type
     * @return a unary function with the first argument fixed
     */
    public static <T, U, R> Function<U, R> partial(BiFunction<T, U, R> biFunction, T arg1) {
        return arg2 -> biFunction.apply(arg1, arg2);
    }

    /**
     * Returns the identity function.
     *
     * @param <T> the type
     * @return a function that returns its input unchanged
     */
    public static <T> Function<T, T> identity() {
        return Function.identity();
    }

    /**
     * Returns a constant function that always returns the given value.
     *
     * @param value the constant value
     * @param <T> the input type
     * @param <R> the result type
     * @return a function that ignores its input and returns the constant
     */
    public static <T, R> Function<T, R> constant(R value) {
        return ignored -> value;
    }

    /**
     * Combines two predicates with logical AND.
     *
     * @param p1 the first predicate
     * @param p2 the second predicate
     * @param <T> the type being tested
     * @return a predicate that is true when both inputs are true
     */
    public static <T> Predicate<T> and(Predicate<T> p1, Predicate<T> p2) {
        return p1.and(p2);
    }

    /**
     * Combines two predicates with logical OR.
     *
     * @param p1 the first predicate
     * @param p2 the second predicate
     * @param <T> the type being tested
     * @return a predicate that is true when either input is true
     */
    public static <T> Predicate<T> or(Predicate<T> p1, Predicate<T> p2) {
        return p1.or(p2);
    }

    /**
     * Negates a predicate.
     *
     * @param predicate the predicate to negate
     * @param <T> the type being tested
     * @return a predicate that is true when the input is false
     */
    public static <T> Predicate<T> not(Predicate<T> predicate) {
        return predicate.negate();
    }

    /**
     * Returns a predicate that always returns true.
     *
     * @param <T> the type being tested
     * @return a predicate that always returns true
     */
    public static <T> Predicate<T> alwaysTrue() {
        return t -> true;
    }

    /**
     * Returns a predicate that always returns false.
     *
     * @param <T> the type being tested
     * @return a predicate that always returns false
     */
    public static <T> Predicate<T> alwaysFalse() {
        return t -> false;
    }

    /**
     * Memoizes a function, caching its results.
     *
     * <p>This is useful for expensive computations that are called repeatedly
     * with the same inputs. Supports null inputs.
     *
     * <p><b>Warning:</b> This uses a ConcurrentHashMap for caching, which can
     * grow unbounded. Use only for functions with a small input domain.
     *
     * @param function the function to memoize
     * @param <T> the input type
     * @param <R> the result type
     * @return a memoized version of the function
     */
    public static <T, R> Function<T, R> memoized(Function<T, R> function) {
        java.util.Map<T, R> cache = new java.util.concurrent.ConcurrentHashMap<>();
        // Track whether we've computed the null case (ConcurrentHashMap doesn't support null keys)
        java.util.concurrent.atomic.AtomicReference<R> nullValue = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean nullComputed = new java.util.concurrent.atomic.AtomicBoolean(false);

        return input -> {
            if (input == null) {
                // Handle null input separately
                if (!nullComputed.get()) {
                    synchronized (nullComputed) {
                        if (!nullComputed.get()) {
                            nullValue.set(function.apply(null));
                            nullComputed.set(true);
                        }
                    }
                }
                return nullValue.get();
            }
            return cache.computeIfAbsent(input, function);
        };
    }
}
