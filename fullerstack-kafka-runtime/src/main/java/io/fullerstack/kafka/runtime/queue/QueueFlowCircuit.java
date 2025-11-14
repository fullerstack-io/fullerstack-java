package io.fullerstack.kafka.runtime.queue;

import io.humainary.substrates.ext.serventis.ext.Queues;
import io.humainary.substrates.ext.serventis.ext.Queues.Queue;
import io.humainary.substrates.api.Substrates.*;

import io.humainary.substrates.api.Substrates;
import static io.humainary.substrates.api.Substrates.*;

/**
 * Circuit for queue flow signal monitoring (RC1 Serventis API).
 * <p>
 * Provides infrastructure for queue instrumentation using {@link Queue} instruments
 * that emit overflow/underflow/put/take signals for producer buffer and consumer lag monitoring.
 * <p>
 * <b>RC1 Instrument Pattern</b>:
 * <ul>
 *   <li>Uses {@link Queue} instrument with method calls (overflow(), underflow(), put(), take())</li>
 *   <li>Signals are {@link Queues.Sign} records with Sign + units</li>
 *   <li>Subject context in Channel, not in signals</li>
 *   <li>NO manual Signal construction needed - instruments handle it</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * QueueFlowCircuit circuit = new QueueFlowCircuit();
 * Queue producerBuffer = circuit.queueFor("producer-1.buffer");
 *
 * // Emit signals based on buffer state
 * if (utilization > 0.95) {
 *     producerBuffer.overflow((long)(utilization * 100));  // OVERFLOW with % units
 * } else if (utilization > 0.80) {
 *     producerBuffer.put((long)(utilization * 100));       // PUT with pressure indication
 * } else {
 *     producerBuffer.enqueue();                                // Normal PUT
 * }
 *
 * circuit.close();
 * }</pre>
 *
 * @see Queues
 * @see Queue
 */
public class QueueFlowCircuit implements AutoCloseable {

    
    private final Circuit circuit;
    private final Conduit<Queue, Queues.Sign> conduit;

    /**
     * Creates a new queue flow circuit.
     * <p>
     * Initializes circuit "queue.flow" with a conduit using {@link Queues#composer}.
     */
    public QueueFlowCircuit() {
        // Create circuit using static Cortex methods
        this.circuit = Substrates.cortex().circuit(Substrates.cortex().name("queue.flow"));

        // Create conduit with Queues composer (returns Queue instruments)
        this.conduit = circuit.conduit(
            Substrates.cortex().name("queue-monitoring"),
            Queues::composer
        );
    }

    /**
     * Get Queue instrument for specific queue entity.
     *
     * @param entityName queue identifier (e.g., "producer-1.buffer", "consumer-group-1.lag")
     * @return Queue instrument for emitting queue signals via method calls
     */
    public Queue queueFor(String entityName) {
        return conduit.percept(Substrates.cortex().name(entityName));
    }

    /**
     * Closes the circuit and releases resources.
     */
    @Override
    public void close() {
        circuit.close();
    }
}
