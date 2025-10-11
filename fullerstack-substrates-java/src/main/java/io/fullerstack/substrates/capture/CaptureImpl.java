package io.fullerstack.substrates.capture;

import io.humainary.substrates.api.Substrates.Capture;
import io.humainary.substrates.api.Substrates.Subject;

import java.util.Objects;

/**
 * Implementation of Substrates.Capture for pairing Subject with emission.
 *
 * <p>Capture represents a single emission event with full context:
 * <ul>
 *   <li><b>Subject</b> - WHO emitted (the Channel's subject)</li>
 *   <li><b>Emission</b> - WHAT was emitted (the value)</li>
 * </ul>
 *
 * <p>This pairing is critical for the Substrates architecture because:
 * <ol>
 *   <li>Multiple Channels share a single queue in a Conduit</li>
 *   <li>Queue processor needs to know which Channel emitted each value</li>
 *   <li>Subscribers need the Channel's Subject for hierarchical routing</li>
 * </ol>
 *
 * <p><b>Data Flow with Capture:</b>
 * <pre>
 * Channel("sensor1") creates Capture(channelSubject, value)
 *   ↓
 * Queue&lt;Capture&lt;E&gt;&gt; stores both Subject and value
 *   ↓
 * QueueProcessor takes Capture
 *   ↓
 * Source.notify(capture) passes Channel's Subject to Subscribers
 *   ↓
 * Subscriber receives (subject, registrar) where subject.name() == "sensor1"
 * </pre>
 *
 * @param <E> the emission type
 * @see Capture
 * @see Subject
 */
public record CaptureImpl<E>(Subject subject, E emission) implements Capture<E> {

    /**
     * Creates a Capture pairing Subject with emission.
     *
     * @param subject the Subject that emitted (Channel's subject)
     * @param emission the value emitted
     * @throws NullPointerException if subject is null
     */
    public CaptureImpl {
        Objects.requireNonNull(subject, "Capture subject cannot be null");
        // emission can be null (nullable emissions are allowed)
    }
}
