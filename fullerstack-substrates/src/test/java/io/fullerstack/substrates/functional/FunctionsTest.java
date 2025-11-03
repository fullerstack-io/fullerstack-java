package io.fullerstack.substrates.functional;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Functions}.
 */
class FunctionsTest {

  @Test
  void compose_twoFunctions () {
    // Given: Two functions
    Function < String, Integer > length = String::length;
    Function < Integer, Boolean > isEven = n -> n % 2 == 0;

    // When: Composing
    Function < String, Boolean > composed = Functions.compose ( length, isEven );

    // Then: Functions applied sequentially
    assertThat ( composed.apply ( "test" ) ).isTrue ();   // length 4, even
    assertThat ( composed.apply ( "hello" ) ).isFalse (); // length 5, odd
  }

  @Test
  void compose_multipleFunctions () {
    // Given: Multiple functions
    Function < Integer, Integer > addOne = n -> n + 1;
    Function < Integer, Integer > multiplyTwo = n -> n * 2;
    Function < Integer, Integer > subtractThree = n -> n - 3;

    // When: Composing
    Function < Integer, Integer > composed = Functions.compose ( addOne, multiplyTwo, subtractThree );

    // Then: Applied in order: (5 + 1) * 2 - 3 = 9
    assertThat ( composed.apply ( 5 ) ).isEqualTo ( 9 );
  }

  @Test
  void partial_fixesFirstArgument () {
    // Given: A binary function
    java.util.function.BiFunction < String, Integer, String > substring = String::substring;

    // When: Partially applying with "hello"
    Function < Integer, String > substringFrom = Functions.partial ( substring, "hello" );

    // Then: First argument fixed
    assertThat ( substringFrom.apply ( 0 ) ).isEqualTo ( "hello" );
    assertThat ( substringFrom.apply ( 2 ) ).isEqualTo ( "llo" );
  }

  @Test
  void identity_returnsInput () {
    // Given: Identity function
    Function < String, String > identity = Functions.identity ();

    // When: Applied
    String result = identity.apply ( "test" );

    // Then: Returns input unchanged
    assertThat ( result ).isEqualTo ( "test" );
  }

  @Test
  void constant_alwaysReturnsSameValue () {
    // Given: Constant function
    Function < Integer, String > constant = Functions.constant ( "fixed" );

    // When: Applied with different inputs
    String result1 = constant.apply ( 1 );
    String result2 = constant.apply ( 100 );

    // Then: Always returns same value
    assertThat ( result1 ).isEqualTo ( "fixed" );
    assertThat ( result2 ).isEqualTo ( "fixed" );
  }

  @Test
  void and_combinesPredicates () {
    // Given: Two predicates
    Predicate < Integer > isPositive = n -> n > 0;
    Predicate < Integer > isEven = n -> n % 2 == 0;

    // When: Combining with AND
    Predicate < Integer > combined = Functions.and ( isPositive, isEven );

    // Then: Both must be true
    assertThat ( combined.test ( 2 ) ).isTrue ();   // positive and even
    assertThat ( combined.test ( -2 ) ).isFalse (); // even but not positive
    assertThat ( combined.test ( 3 ) ).isFalse ();  // positive but not even
  }

  @Test
  void or_combinesPredicates () {
    // Given: Two predicates
    Predicate < Integer > isNegative = n -> n < 0;
    Predicate < Integer > isEven = n -> n % 2 == 0;

    // When: Combining with OR
    Predicate < Integer > combined = Functions.or ( isNegative, isEven );

    // Then: Either can be true
    assertThat ( combined.test ( -3 ) ).isTrue ();  // negative
    assertThat ( combined.test ( 2 ) ).isTrue ();   // even
    assertThat ( combined.test ( -2 ) ).isTrue ();  // both
    assertThat ( combined.test ( 3 ) ).isFalse ();  // neither
  }

  @Test
  void not_negatesPredicate () {
    // Given: A predicate
    Predicate < Integer > isPositive = n -> n > 0;

    // When: Negating
    Predicate < Integer > isNotPositive = Functions.not ( isPositive );

    // Then: Logic inverted
    assertThat ( isNotPositive.test ( 5 ) ).isFalse ();
    assertThat ( isNotPositive.test ( -5 ) ).isTrue ();
    assertThat ( isNotPositive.test ( 0 ) ).isTrue ();
  }

  @Test
  void alwaysTrue_returnsTrue () {
    // Given: alwaysTrue predicate
    Predicate < String > predicate = Functions.alwaysTrue ();

    // When: Testing any value
    boolean result1 = predicate.test ( "anything" );
    boolean result2 = predicate.test ( null );

    // Then: Always true
    assertThat ( result1 ).isTrue ();
    assertThat ( result2 ).isTrue ();
  }

  @Test
  void alwaysFalse_returnsFalse () {
    // Given: alwaysFalse predicate
    Predicate < String > predicate = Functions.alwaysFalse ();

    // When: Testing any value
    boolean result1 = predicate.test ( "anything" );
    boolean result2 = predicate.test ( null );

    // Then: Always false
    assertThat ( result1 ).isFalse ();
    assertThat ( result2 ).isFalse ();
  }

  @Test
  void memoized_cachesResults () {
    // Given: A function that counts calls
    AtomicInteger callCount = new AtomicInteger ( 0 );
    Function < Integer, String > expensive = n -> {
      callCount.incrementAndGet ();
      return "Result: " + n;
    };

    // When: Memoizing and calling multiple times
    Function < Integer, String > memoized = Functions.memoized ( expensive );
    String first = memoized.apply ( 5 );
    String second = memoized.apply ( 5 );  // Same input
    String third = memoized.apply ( 10 );  // Different input

    // Then: Called once per unique input
    assertThat ( first ).isEqualTo ( "Result: 5" );
    assertThat ( second ).isEqualTo ( "Result: 5" );
    assertThat ( third ).isEqualTo ( "Result: 10" );
    assertThat ( callCount.get () ).isEqualTo ( 2 ); // Only 2 computations
  }

  @Test
  void memoized_handlesNullInputs () {
    // Given: A memoized function
    AtomicInteger callCount = new AtomicInteger ( 0 );
    Function < String, Integer > memoized = Functions.memoized ( s -> {
      callCount.incrementAndGet ();
      return s == null ? 0 : s.length ();
    } );

    // When: Called with null multiple times
    int first = memoized.apply ( null );
    int second = memoized.apply ( null );

    // Then: Cached correctly
    assertThat ( first ).isEqualTo ( 0 );
    assertThat ( second ).isEqualTo ( 0 );
    assertThat ( callCount.get () ).isEqualTo ( 1 );
  }
}
