package io.fullerstack.serventis.signals;

import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Base signal interface for all observability signals.
 *
 * <p>Signals are interpretable signs - they carry meaning, not just data.
 * Each signal represents a semiotic event that can be understood by Observers
 * in the context of the system's state.
 *
 * <p>Signals are immutable event records emitted by sensors and routed through
 * Substrates circuits/conduits. All signal implementations should be Java records
 * for immutability and thread-safety.
 *
 * <p><b>Semiotic Principles:</b>
 * <ul>
 *   <li><b>Firstness</b> - Raw sensation (the signal itself)</li>
 *   <li><b>Secondness</b> - Relational context (subject, timestamp, causality)</li>
 *   <li><b>Thirdness</b> - Interpretable meaning (semantic helpers, condition/sign enums)</li>
 * </ul>
 *
 * @see MonitorSignal
 * @see ServiceSignal
 * @see QueueSignal
 * @see ResourceSignal
 * @see ProbeSignal
 * @see ReporterSignal
 */
public interface Signal {

    /**
     * Unique identifier for this signal.
     *
     * @return signal ID
     */
    UUID id();

    /**
     * Subject identity for this signal using Substrates Subject type.
     *
     * <p>The Subject encapsulates both the circuit (scope) and the specific
     * entity being observed. This aligns with Substrates' semantic identity model.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>Circuit: "kafka.broker.health", Name: "broker-1"</li>
     *   <li>Circuit: "kafka.consumer.lag", Name: "consumer-group-x.topic-y.partition-0"</li>
     * </ul>
     *
     * @return Substrates Subject combining circuit and entity identity
     */
    Subject subject();

    /**
     * Timestamp when signal was emitted.
     *
     * @return signal timestamp
     */
    Instant timestamp();

    /**
     * Vector clock for causal ordering across distributed sensors.
     *
     * <p>Enables understanding of causal relationships between signals:
     * "Did signal A happen before signal B?" This is critical for
     * understanding cascading failures and event correlation.
     *
     * @return vector clock
     */
    VectorClock vectorClock();

    /**
     * Additional metadata payload.
     *
     * <p>Should contain interpretable context, not just raw metrics.
     * Examples: "heap_usage_percent", "gc_pause_ms", "broker_id"
     *
     * @return metadata map (immutable)
     */
    Map<String, String> payload();

    /**
     * Returns the severity level of this signal for triage and alerting.
     *
     * <p>Severity helps Observers prioritize attention:
     * <ul>
     *   <li><b>INFO</b> - Normal operation, no action needed</li>
     *   <li><b>WARNING</b> - Potential issue, monitor closely</li>
     *   <li><b>ERROR</b> - Problem requiring attention</li>
     *   <li><b>CRITICAL</b> - Urgent issue requiring immediate action</li>
     * </ul>
     *
     * @return severity level based on signal condition/sign
     */
    Severity severity();

    /**
     * Checks if this signal requires human or automated attention.
     *
     * <p>This is the key interpretive method - it encodes domain knowledge
     * about what conditions/signs warrant attention.
     *
     * @return true if signal indicates a situation requiring attention
     */
    boolean requiresAttention();

    /**
     * Returns a human-readable interpretation of this signal.
     *
     * <p>This moves beyond raw data to provide meaning:
     * "Broker 1 JVM heap is DEGRADED (87% used, GC pressure increasing)"
     * rather than just "condition=DEGRADED, confidence=SUSPECTED"
     *
     * @return interpretable description
     */
    String interpret();

    /**
     * Severity levels for signal triage and alerting.
     */
    enum Severity {
        /** Normal operation, informational */
        INFO,
        /** Potential issue, monitor closely */
        WARNING,
        /** Problem requiring attention */
        ERROR,
        /** Urgent issue requiring immediate action */
        CRITICAL
    }
}
