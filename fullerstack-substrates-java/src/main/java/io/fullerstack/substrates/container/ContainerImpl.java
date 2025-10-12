package io.fullerstack.substrates.container;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.sink.CaptureImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of Substrates.Container managing a collection of Conduits.
 *
 * <p>Container is a "Pool of Pools" (Container extends Pool<Pool<P>>), meaning:
 * <ul>
 *   <li>get(Name) returns Pool<P> representing a Conduit for that name</li>
 *   <li>Each name gets its own Conduit, created on first access</li>
 *   <li>source() returns Source<Source<E>> emitting Conduit Sources when created</li>
 * </ul>
 *
 * <p><b>Hierarchical Subscription Pattern:</b>
 * Container's Source emits Conduit Sources (not the actual emissions). This enables
 * hierarchical subscription:
 * <pre>
 * // Subscribe to Container - receive Conduit Sources
 * container.source().subscribe(
 *   stock -> orders ->           // stock=Conduit Subject, orders=Conduit Source
 *     orders.subscribe(          // Subscribe to individual Conduit
 *       type -> order ->         // type=Channel Subject, order=actual emission
 *         process(order)
 *     )
 * );
 *
 * // When you call container.get("APPL") for the first time:
 * // 1. Container creates new Conduit for "APPL"
 * // 2. Container emits that Conduit's Source to subscribers
 * // 3. Subscribers receive the Source and can subscribe to it
 * container.get(cortex.name("APPL")).get(cortex.name("BUY")).emit(order);
 * </pre>
 *
 * <p><b>Article Reference:</b>
 * "A Container is a collection of Conduits of the same emittance data type"
 * - https://humainary.io/blog/observability-x-containers/
 *
 * @param <P> percept type (what Conduit's Pool contains, e.g., Pipe<E>)
 * @param <E> emission type (what Conduit emits, e.g., Order)
 * @see Container
 */
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Name containerName;
    private final Circuit circuit;
    private final Composer<? extends P, E> composer;
    private final Sequencer<Segment<E>> sequencer; // Optional transformation pipeline (nullable)
    private final Map<Name, Conduit<P, E>> conduits = new ConcurrentHashMap<>();
    private final SourceImpl<Source<E>> containerSource;
    private final Subject containerSubject;

    /**
     * Creates a container without transformations.
     *
     * @param containerName the container's name
     * @param circuit the circuit used to create Conduits
     * @param composer the composer used for all Conduits in this container
     */
    public ContainerImpl(Name containerName, Circuit circuit, Composer<? extends P, E> composer) {
        this(containerName, circuit, composer, null);
    }

    /**
     * Creates a container with optional transformation pipeline.
     *
     * <p>Each call to get(Name) creates or retrieves a Conduit for that name.
     * If a Sequencer is provided, all Conduits created by this Container will
     * apply the same transformation pipeline.
     *
     * @param containerName the container's name
     * @param circuit the circuit used to create Conduits
     * @param composer the composer used for all Conduits in this container
     * @param sequencer optional transformation pipeline (null if no transformations)
     */
    public ContainerImpl(Name containerName, Circuit circuit, Composer<? extends P, E> composer, Sequencer<Segment<E>> sequencer) {
        this.containerName = Objects.requireNonNull(containerName, "Container name cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "Circuit cannot be null");
        this.composer = Objects.requireNonNull(composer, "Composer cannot be null");
        this.sequencer = sequencer; // Can be null
        this.containerSource = new SourceImpl<>(containerName);
        this.containerSubject = new SubjectImpl(
            IdImpl.generate(),
            containerName,
            StateImpl.empty(),
            Subject.Type.CONTAINER
        );
    }

    @Override
    public Subject subject() {
        return containerSubject;
    }

    @Override
    public Pool<P> get(Name name) {
        Objects.requireNonNull(name, "Conduit name cannot be null");

        // Track if this is a new Conduit creation
        final boolean[] isNewConduit = {false};

        // Get or create Conduit for this name
        Conduit<P, E> conduit = conduits.computeIfAbsent(name, n -> {
            // Create hierarchical name: containerName + conduitName
            Name conduitName = containerName.name(n);

            // Create new Conduit via Circuit, passing Sequencer if present
            // All Conduits created by this Container share the same transformation pipeline
            Conduit<P, E> newConduit = sequencer != null
                ? circuit.conduit(conduitName, composer, sequencer)
                : circuit.conduit(conduitName, composer);

            isNewConduit[0] = true;
            return newConduit;
        });

        // Emit the Conduit's Source to Container subscribers (only for new Conduits)
        if (isNewConduit[0]) {
            // Create Capture pairing the Conduit's Subject with its Source
            Capture<Source<E>> capture = new CaptureImpl<>(conduit.subject(), conduit.source());

            // Emit synchronously to notify all Container subscribers
            // Subscribers can then subscribe to this Conduit's Source
            containerSource.emissionHandler().accept(capture);
        }

        // Conduit implements Pool<P>, so return it directly
        return conduit;
    }

    @Override
    public Source<Source<E>> source() {
        return containerSource;
    }

    /**
     * Gets all managed Conduits.
     * Package-private for testing and inspection.
     *
     * @return map of conduit names to conduits
     */
    Map<Name, Conduit<P, E>> getConduits() {
        return conduits;
    }

    @Override
    public void close() {
        // Close all managed Conduits
        conduits.values().forEach(conduit -> {
            try {
                if (conduit instanceof AutoCloseable) {
                    ((AutoCloseable) conduit).close();
                }
            } catch (Exception e) {
                // Log but continue closing others
                System.err.println("Error closing conduit in container " + containerName + ": " + e.getMessage());
            }
        });
        conduits.clear();
    }
}
