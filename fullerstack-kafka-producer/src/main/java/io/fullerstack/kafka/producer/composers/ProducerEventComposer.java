package io.fullerstack.kafka.producer.composers;

import io.fullerstack.kafka.producer.models.ProducerEventMetrics;
import io.fullerstack.serventis.signals.ServiceSignal;
import io.fullerstack.serventis.signals.VectorClock;
import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Composer that transforms ProducerEventMetrics into enriched ServiceSignal records.
 * <p>
 * This follows the standard Substrates Composer pattern:
 * - Input: ProducerEventMetrics (rich domain data with topic, partition, offset, etc.)
 * - Output: ServiceSignal (semiotic signal with Subject, VectorClock, UUID)
 * <p>
 * Unlike SignalEnrichmentComposer (which enriches bare Services.Signal enums),
 * this composer transforms rich metrics into signals, preserving all contextual data
 * in the signal payload.
 * <p>
 * Signal Flow:
 * <pre>
 * ProducerEventInterceptor
 *   → emit(ProducerEventMetrics)
 *   → ProducerEventComposer
 *   → emit(ServiceSignal)
 *   → Observers
 * </pre>
 *
 * @see ProducerEventMetrics
 * @see ServiceSignal
 */
public class ProducerEventComposer implements Composer<Pipe<ProducerEventMetrics>, ServiceSignal> {

    /**
     * Create input Pipe that transforms ProducerEventMetrics to ServiceSignal.
     *
     * @param channel The output channel for ServiceSignals
     * @return Input Pipe that accepts ProducerEventMetrics
     */
    @Override
    public Pipe<ProducerEventMetrics> compose(Channel<ServiceSignal> channel) {
        return new Pipe<>() {
            @Override
            public void emit(ProducerEventMetrics metrics) {
                ServiceSignal signal = transform(metrics, channel.subject());
                channel.pipe().emit(signal);
            }
        };
    }

    /**
     * Transform ProducerEventMetrics into enriched ServiceSignal.
     *
     * @param metrics The producer event metrics
     * @param subject The Subject from the Channel (producer identity)
     * @return Enriched ServiceSignal with all context preserved
     */
    private ServiceSignal transform(ProducerEventMetrics metrics, Subject<? extends Channel<ServiceSignal>> subject) {
        // Build payload with all contextual data (ServiceSignal requires Map<String, String>)
        Map<String, String> payload = new HashMap<>();
        payload.put("producerId", metrics.producerId());
        payload.put("topic", metrics.topic());
        payload.put("partition", String.valueOf(metrics.partition()));
        payload.put("offset", String.valueOf(metrics.offset()));
        payload.put("latencyMs", String.valueOf(metrics.latencyMs()));
        payload.put("timestamp", metrics.timestamp().toString());

        // Add exception details if present
        if (metrics.exception() != null) {
            payload.put("exceptionType", metrics.exception().getClass().getName());
            payload.put("exceptionMessage", metrics.exception().getMessage() != null
                ? metrics.exception().getMessage()
                : "");
            if (metrics.exception().getCause() != null) {
                payload.put("exceptionCause", metrics.exception().getCause().toString());
            }
        }

        // Merge any additional metadata (convert Object values to String)
        for (Map.Entry<String, Object> entry : metrics.metadata().entrySet()) {
            payload.put(entry.getKey(), String.valueOf(entry.getValue()));
        }

        // Create ServiceSignal with proper Subject casting
        @SuppressWarnings("unchecked")
        Subject<Channel<ServiceSignal>> channelSubject = (Subject<Channel<ServiceSignal>>) (Subject<?>) subject;

        return new ServiceSignal(
            UUID.randomUUID(),
            channelSubject,
            metrics.timestamp(),
            VectorClock.empty(),
            metrics.signal(),
            payload
        );
    }
}
