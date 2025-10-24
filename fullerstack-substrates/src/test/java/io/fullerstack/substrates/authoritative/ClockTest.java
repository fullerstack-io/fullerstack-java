/*
 * Copyright (c) 2025 William David Louth
 */

package io.fullerstack.substrates.authoritative;
import io.fullerstack.substrates.CortexRuntime;

import io.humainary.substrates.api.Substrates.Cortex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.humainary.substrates.api.Substrates.Clock.Cycle.*;
import static java.lang.Thread.currentThread;
import static org.junit.jupiter.api.Assertions.*;

final class ClockTest {

  private Cortex cortex;

  @BeforeEach
  void setup () {

    cortex = new CortexRuntime();

  }

  @Test
  void testClockConsumeFiltersByCycle () {

    final var circuit = cortex.circuit ();

    try {

      final var clock = circuit.clock ( cortex.name ( "clock.consume.filter" ) );

      final var millisecondCount = new AtomicInteger ();
      final var minuteCount = new AtomicInteger ();

      final var millisecondSubscription = clock.consume (
        cortex.name ( "clock.filter.millisecond" ),
        MILLISECOND,
        _ -> millisecondCount.incrementAndGet ()
      );

      final var minuteSubscription = clock.consume (
        cortex.name ( "clock.filter.minute" ),
        MINUTE,
        _ -> minuteCount.incrementAndGet ()
      );

      try {
        Thread.sleep ( 25L );
      } catch ( final InterruptedException e ) {
        currentThread ().interrupt ();
        fail ( e );
      }

      circuit.await ();

      assertTrue ( millisecondCount.get () > 0, "Millisecond cycle should produce ticks" );
      assertEquals ( 0, minuteCount.get (), "Minute cycle should not tick within a short window" );

      millisecondSubscription.close ();
      minuteSubscription.close ();
      clock.close ();

    } finally {

      circuit.close ();

    }

  }

  @Test
  void testClockSubscriptionCloseStopsTicks () {

    final var circuit = cortex.circuit ();

    try {

      final var clock = circuit.clock ();

      final var tickCount = new AtomicInteger ();

      final var subscription = clock.consume (
        cortex.name ( "clock.subscription.close" ),
        SECOND,
        _ -> tickCount.incrementAndGet ()
      );

      try {
        Thread.sleep ( 25L );
      } catch ( final InterruptedException e ) {
        currentThread ().interrupt ();
        fail ( e );
      }

      circuit.await ();

      final int beforeClose = tickCount.get ();

      subscription.close ();

      try {
        Thread.sleep ( 20L );
      } catch ( final InterruptedException e ) {
        currentThread ().interrupt ();
        fail ( e );
      }

      circuit.await ();

      assertEquals ( beforeClose, tickCount.get (), "Ticks should stop once the subscription is closed" );

      clock.close ();

    } finally {

      circuit.close ();

    }

  }


}
