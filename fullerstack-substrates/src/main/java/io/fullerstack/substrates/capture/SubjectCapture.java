package io.fullerstack.substrates.capture;

import io.humainary.substrates.api.Substrates.Capture;
import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Substrate;

import java.util.Objects;

/**
 * Pairs a Subject with its emission for queue processing.
 *
 * <p>SubjectCapture represents a single emission event with full context:
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
 * <p><b>Data Flow with SubjectCapture:</b>
 * <pre>
 * Channel("sensor1") creates SubjectCapture(channelSubject, value)
 *   ↓
 * Queue&lt;Capture&lt;E, S&gt;&gt; stores both Subject and value
 *   ↓
 * QueueProcessor takes Capture
 *   ↓
 * Conduit invokes subscriber.accept(capture.subject(), registrar)
 *   ↓
 * Subscriber receives (subject, registrar) where subject.name() == "sensor1"
 * </pre>
 *
 * @param <E> the emission type
 * @param <S> the substrate type that emitted the value
 * @see Capture
 * @see Subject
 */
public record SubjectCapture<E, S extends Substrate<S>>(Subject<S> subject, E emission) implements Capture<E, S> {

    /**
     * Creates a SubjectCapture pairing Subject with emission.
     *
     * @param subject the Subject that emitted (Channel's subject)
     * @param emission the value emitted
     * @throws NullPointerException if subject is null
     */
    public SubjectCapture {
        Objects.requireNonNull(subject, "Capture subject cannot be null");
        // emission can be null (nullable emissions are allowed)
    }
}
