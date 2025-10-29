package io.fullerstack.serventis.composers;

import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.*;

/**
 * Humainary Services Composer implementation.
 * <p>
 * Implements the {@link Services} Composer interface, which transforms a {@link Services.Service}
 * input Pipe (with convenience methods like call(), succeeded(), failed()) into a stream of
 * {@link Services.Signal} enums.
 *
 * <h3>Architecture Pattern:</h3>
 * <pre>{@code
 * // Create Cell with ServicesComposer
 * Cell<Services.Service, Services.Signal> cell = circuit.cell(
 *     new ServicesComposer(),
 *     subscriberPipe  // Receives Services.Signal
 * );
 *
 * // Get Services.Service input Pipe
 * Services.Service service = cell.pipe();
 *
 * // Call convenience methods (emits corresponding Services.Signal)
 * service.call();        // → Emits Services.Signal.CALL
 * service.succeeded();   // → Emits Services.Signal.SUCCEEDED
 * service.failed();      // → Emits Services.Signal.FAILED
 * }</pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * Application → service.succeeded() → Services.Signal.SUCCEEDED → Output Pipe → Subscribers
 * </pre>
 *
 * <h3>Why This Composer?</h3>
 * The {@link Services} interface defines the Composer contract:
 * <ul>
 *   <li>Input: {@link Services.Service} (Pipe with convenience methods)</li>
 *   <li>Output: {@link Services.Signal} (enum values like CALL, SUCCEEDED, FAILED)</li>
 * </ul>
 *
 * This Composer creates a {@link Services.Service} implementation whose default methods
 * (call(), succeeded(), etc.) emit the corresponding {@link Services.Signal} enum values
 * into the output Pipe.
 *
 * <h3>Usage in Producer Monitoring:</h3>
 * <pre>{@code
 * // Runtime creates producer services Cell
 * Cell<Services.Service, Services.Signal> cell = circuit.cell(
 *     new ServicesComposer(),
 *     signal -> handleProducerSignal(signal)
 * );
 *
 * Services.Service service = cell.pipe();
 *
 * // Pass service to interceptor
 * props.put("fullerstack.service", service);
 *
 * // Interceptor calls methods
 * public void onSend(ProducerRecord<K, V> record) {
 *     service.call();  // Emits Services.Signal.CALL
 * }
 *
 * public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
 *     if (exception == null) {
 *         service.succeeded();  // Emits Services.Signal.SUCCEEDED
 *     } else {
 *         service.failed();     // Emits Services.Signal.FAILED
 *     }
 * }
 * }</pre>
 *
 * @author Fullerstack
 * @see Services
 * @see Services.Service
 * @see Services.Signal
 */
public class ServicesComposer implements Services {

    @Override
    public Services.Service compose(Channel<Services.Signal> channel) {
        // Get output Pipe where Services.Signal will be emitted
        Pipe<Services.Signal> outputPipe = channel.pipe();

        // Return Services.Service implementation
        // The default methods (call(), succeeded(), etc.) are already implemented by the interface
        // They call emit(Services.Signal.CALL), emit(Services.Signal.SUCCEEDED), etc.
        return new Services.Service() {
            @Override
            public void emit(Services.Signal signal) {
                // Route signal to output Pipe
                outputPipe.emit(signal);
            }
        };
    }
}
