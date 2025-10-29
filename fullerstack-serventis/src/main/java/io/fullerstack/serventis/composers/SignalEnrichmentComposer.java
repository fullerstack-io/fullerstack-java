package io.fullerstack.serventis.composers;

import io.fullerstack.serventis.signals.ServiceSignal;
import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.*;

/**
 * Composer that enriches Humainary {@link Services.Signal} enums with Substrates metadata.
 * <p>
 * Transforms bare {@link Services.Signal} values (CALL, SUCCEEDED, FAILED, etc.) into
 * full {@link ServiceSignal} records with:
 * <ul>
 *   <li>Subject (Circuit + Entity identity)</li>
 *   <li>VectorClock (causal ordering)</li>
 *   <li>UUID (unique signal ID)</li>
 *   <li>Timestamp (emission time)</li>
 *   <li>Payload (optional metadata)</li>
 * </ul>
 *
 * <h3>Architecture Pattern:</h3>
 * <pre>{@code
 * // Step 1: Services.Service → Services.Signal (via ServicesComposer)
 * Cell<Services.Service, Services.Signal> servicesCell = circuit.cell(
 *     new ServicesComposer(),
 *     Pipe.empty()
 * );
 *
 * // Step 2: Services.Signal → ServiceSignal (via SignalEnrichmentComposer)
 * Cell<Services.Signal, ServiceSignal> enrichmentCell = circuit.cell(
 *     new SignalEnrichmentComposer(),
 *     observerPipe
 * );
 *
 * // Connect: servicesCell output → enrichmentCell input
 * servicesCell.subscribe(enrichmentCell.pipe());
 * }</pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * Interceptor.call()
 *   → Services.Signal.CALL (enum)
 *   → SignalEnrichmentComposer
 *   → ServiceSignal(
 *         id = UUID,
 *         subject = kafka.producer.interactions/producer-1,
 *         signal = Services.Signal.CALL,
 *         vectorClock = VectorClock.empty(),
 *         payload = {}
 *     )
 *   → Observers
 * </pre>
 *
 * <h3>Subject Construction:</h3>
 * The Channel's Subject is used directly - it's provided by the Circuit when creating the Cell.
 * The Subject identifies both the circuit and the entity being monitored.
 *
 * @author Fullerstack
 * @see Services.Signal
 * @see ServiceSignal
 * @see ServicesComposer
 */
public class SignalEnrichmentComposer implements Composer<Pipe<Services.Signal>, ServiceSignal> {

    @Override
    public Pipe<Services.Signal> compose(Channel<ServiceSignal> channel) {
        // Get infrastructure-provided Subject and output Pipe
        Subject<Channel<ServiceSignal>> channelSubject = channel.subject();
        Pipe<ServiceSignal> outputPipe = channel.pipe();

        // Return input Pipe that enriches Services.Signal → ServiceSignal
        return new Pipe<>() {
            @Override
            public void emit(Services.Signal signal) {
                // Create enriched ServiceSignal
                ServiceSignal enrichedSignal = ServiceSignal.builder()
                    .subject((Subject) channelSubject)
                    .signal(signal)  // Use the Humainary Services.Signal directly
                    .build();

                // Emit to output
                outputPipe.emit(enrichedSignal);
            }
        };
    }
}
