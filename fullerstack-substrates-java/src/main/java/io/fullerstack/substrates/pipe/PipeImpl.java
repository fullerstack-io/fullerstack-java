package io.fullerstack.substrates.pipe;

import io.fullerstack.substrates.capture.CaptureImpl;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.segment.SegmentImpl;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implementation of Substrates.Pipe interface.
 *
 * <p>A Pipe provides typed emission with optional transformation support via Segment.
 *
 * <p><b>Circuit Queue Architecture:</b>
 * Instead of putting Captures directly on a BlockingQueue, Pipes post Scripts to the
 * Circuit's Queue. Each Script creates a Capture and calls an emission handler callback.
 * This ensures all Conduits share the Circuit's single-threaded execution model
 * ("Virtual CPU Core" design principle).
 *
 * <p>When created without a Segment, emissions pass through directly.
 * When created with a Segment, emissions are transformed (filtered, mapped, reduced, etc.)
 * before being posted as Scripts.
 *
 * <p><b>Subject Propagation:</b> Each Pipe knows its Channel's Subject, which is paired
 * with the emission value in a Capture within the Script. This preserves the context
 * of WHO emitted for delivery to Subscribers.
 *
 * <p><b>Callback Pattern:</b> Uses Consumer&lt;Capture&lt;E&gt;&gt; to avoid circular
 * dependencies. The emission handler is typically a method reference to the parent
 * Conduit's processEmission() method, but this decouples Pipe from Conduit implementation.
 *
 * <p>Transformations are executed by the emitting thread before posting the Script,
 * minimizing work in the Circuit's single-threaded queue processor.
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final Queue circuitQueue; // Circuit's shared Queue for Scripts
    private final Subject channelSubject; // WHO this pipe belongs to
    private final Consumer<Capture<E>> emissionHandler; // Callback to process emissions
    private final SegmentImpl<E> segment; // SegmentImpl for apply() and hasReachedLimit()

    /**
     * Creates a Pipe without transformations.
     *
     * @param circuitQueue the Circuit's Queue to post Scripts to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param emissionHandler callback to handle emissions (typically Conduit::processEmission)
     */
    public PipeImpl(Queue circuitQueue, Subject channelSubject, Consumer<Capture<E>> emissionHandler) {
        this(circuitQueue, channelSubject, emissionHandler, null);
    }

    /**
     * Creates a Pipe with transformations defined by a Segment.
     *
     * @param circuitQueue the Circuit's Queue to post Scripts to
     * @param channelSubject the Subject of the Channel this Pipe belongs to
     * @param emissionHandler callback to handle emissions (typically Conduit::processEmission)
     * @param segment the transformation pipeline (null for no transformations)
     */
    public PipeImpl(Queue circuitQueue, Subject channelSubject, Consumer<Capture<E>> emissionHandler, SegmentImpl<E> segment) {
        this.circuitQueue = Objects.requireNonNull(circuitQueue, "Circuit queue cannot be null");
        this.channelSubject = Objects.requireNonNull(channelSubject, "Channel subject cannot be null");
        this.emissionHandler = Objects.requireNonNull(emissionHandler, "Emission handler cannot be null");
        this.segment = segment;
    }

    @Override
    public void emit(E value) {
        if (segment == null) {
            // No transformations - post Script directly
            postScript(value);
        } else {
            // Apply transformations before posting
            if (segment.hasReachedLimit()) {
                // Limit reached - drop emission
                return;
            }

            E transformed = segment.apply(value);
            if (transformed != null) {
                // Transformation passed - post Script with result
                postScript(transformed);
            }
            // If null, emission was filtered out
        }
    }

    /**
     * Posts a Script to the Circuit Queue that will process the emission.
     *
     * <p>The Script creates a Capture (pairing Subject with emission) and calls
     * the emission handler callback. This ensures single-threaded execution within
     * the Circuit domain while avoiding circular dependencies.
     *
     * @param value the emission value (after transformations, if any)
     */
    private void postScript(E value) {
        // Create Capture outside the Script (in emitting thread)
        Capture<E> capture = new CaptureImpl<>(channelSubject, value);

        // Post Script that calls the emission handler callback
        circuitQueue.post(current -> emissionHandler.accept(capture));
    }
}
