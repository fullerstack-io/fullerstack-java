package io.fullerstack.serventis.signals;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe manager for VectorClock state.
 * <p>
 * Provides mutable state management for VectorClock with thread-safe
 * increment, snapshot, and merge operations for causal ordering in
 * distributed systems.
 * <p>
 * Usage:
 * <pre>
 * VectorClockManager manager = new VectorClockManager();
 *
 * // Before emitting signal
 * manager.increment("entity-1");
 * VectorClock snapshot = manager.snapshot();
 *
 * // Include in Signal
 * MonitorSignal signal = MonitorSignal.stable(subject, snapshot, payload);
 * </pre>
 *
 * @see VectorClock
 * @since 1.0.0
 */
public class VectorClockManager {

    private final Map<String, AtomicLong> clocks;

    /**
     * Create a new VectorClockManager with empty clock state.
     */
    public VectorClockManager() {
        this.clocks = new ConcurrentHashMap<>();
    }

    /**
     * Increment the clock for the given entity.
     * <p>
     * Thread-safe operation using AtomicLong. If the entity doesn't
     * exist in the clock, it will be created with an initial value of 1.
     *
     * @param entityId Entity identifier (e.g., "broker-1", "service-2")
     */
    public void increment(String entityId) {
        clocks.computeIfAbsent(entityId, k -> new AtomicLong(0))
              .incrementAndGet();
    }

    /**
     * Capture current clock state as an immutable snapshot.
     * <p>
     * Returns a VectorClock with current values from all entities.
     * The snapshot is immutable and safe to include in signals.
     *
     * @return VectorClock snapshot (never null)
     */
    public VectorClock snapshot() {
        Map<String, Long> snapshot = new ConcurrentHashMap<>();
        clocks.forEach((entityId, atomicValue) ->
                snapshot.put(entityId, atomicValue.get())
        );
        return new VectorClock(snapshot);
    }

    /**
     * Merge incoming VectorClock into our state (taking max of each clock).
     * <p>
     * Used when receiving events from remote entities to update causal knowledge.
     * Implements the standard vector clock merge rule: for each entity, take the
     * maximum value between local and incoming clocks.
     *
     * @param incoming VectorClock from remote event (null-safe)
     */
    public void merge(VectorClock incoming) {
        if (incoming == null) {
            return;
        }

        Map<String, Long> incomingClocks = incoming.clocks();
        incomingClocks.forEach((entityId, incomingValue) ->
                clocks.compute(entityId, (k, existingAtomic) -> {
                    if (existingAtomic == null) {
                        return new AtomicLong(incomingValue);
                    }
                    // Take max (vector clock merge rule)
                    existingAtomic.updateAndGet(current -> Math.max(current, incomingValue));
                    return existingAtomic;
                })
        );
    }

    /**
     * Get current clock value for an entity.
     *
     * @param entityId Entity identifier
     * @return Current clock value (0 if entity not in clock)
     */
    public long get(String entityId) {
        AtomicLong value = clocks.get(entityId);
        return value != null ? value.get() : 0L;
    }

    /**
     * Reset all clocks to zero.
     * <p>
     * Primarily for testing purposes. Clears all entity clocks.
     */
    public void reset() {
        clocks.clear();
    }
}
