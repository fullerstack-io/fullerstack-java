package io.fullerstack.substrates.sink;

import io.humainary.substrates.api.Substrates.Capture;
import io.humainary.substrates.api.Substrates.Subject;

import java.util.Objects;

/**
 * Implementation of Substrates.Capture for buffering emissions.
 *
 * <p>Pairs a Subject (WHO emitted) with an emission value (WHAT was emitted).
 * Used by Sink to store captured events for later analysis.
 *
 * @param subject the Subject that emitted the value
 * @param emission the emitted value
 * @param <E> the emission type
 */
public record CaptureImpl<E>(Subject subject, E emission) implements Capture<E> {

    public CaptureImpl {
        Objects.requireNonNull(subject, "Capture subject cannot be null");
    }
}
