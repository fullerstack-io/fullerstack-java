package io.fullerstack.serventis.signals;

import java.util.HashMap;
import java.util.Map;

/**
 * Vector clock for causal ordering of signals across distributed sensor agents.
 *
 * <p>Immutable record that tracks logical timestamps for each agent to determine
 * causal relationships between signals (happens-before relation).
 *
 * <p>Supports causal ordering operations:
 * <ul>
 *   <li>{@link #happenedBefore(VectorClock)} - Test if this clock causally precedes another</li>
 *   <li>{@link #concurrent(VectorClock)} - Test if two clocks represent concurrent events</li>
 *   <li>{@link #merge(VectorClock)} - Combine clocks (takes max of each actor's timestamp)</li>
 *   <li>{@link #increment(String)} - Advance clock for a specific actor</li>
 * </ul>
 *
 * @param clocks map of agent ID to logical timestamp
 */
public record VectorClock(Map<String, Long> clocks) {

    /**
     * Compact constructor with defensive copy to ensure immutability.
     */
    public VectorClock {
        clocks = Map.copyOf(clocks);
    }

    /**
     * Creates an empty vector clock.
     *
     * @return empty vector clock
     */
    public static VectorClock empty() {
        return new VectorClock(Map.of());
    }

    /**
     * Increments the clock for a specific actor.
     *
     * @param actor the actor whose clock to increment
     * @return new VectorClock with incremented timestamp for the actor
     */
    public VectorClock increment(String actor) {
        Map<String, Long> updated = new HashMap<>(clocks);
        updated.put(actor, updated.getOrDefault(actor, 0L) + 1);
        return new VectorClock(updated);
    }

    /**
     * Tests if this vector clock happened before another clock in causal order.
     *
     * <p>Clock A happened before clock B if:
     * <ul>
     *   <li>For all actors: A[actor] ≤ B[actor]</li>
     *   <li>For at least one actor: A[actor] &lt; B[actor]</li>
     * </ul>
     *
     * @param other the other vector clock
     * @return true if this clock causally precedes the other
     */
    public boolean happenedBefore(VectorClock other) {
        boolean atLeastOneSmaller = false;

        // Check all actors in this clock
        for (String actor : clocks.keySet()) {
            long ourClock = clocks.get(actor);
            long theirClock = other.clocks.getOrDefault(actor, 0L);

            if (ourClock > theirClock) {
                return false;  // We're ahead on this actor, so we didn't happen before
            }
            if (ourClock < theirClock) {
                atLeastOneSmaller = true;
            }
        }

        // Check actors only in their clock
        for (String actor : other.clocks.keySet()) {
            if (!clocks.containsKey(actor) && other.clocks.get(actor) > 0) {
                atLeastOneSmaller = true;
            }
        }

        return atLeastOneSmaller;
    }

    /**
     * Tests if two vector clocks represent concurrent (causally independent) events.
     *
     * <p>Two events are concurrent if neither happened before the other.
     *
     * @param other the other vector clock
     * @return true if the events are concurrent
     */
    public boolean concurrent(VectorClock other) {
        return !this.happenedBefore(other) && !other.happenedBefore(this);
    }

    /**
     * Merges this vector clock with another by taking the maximum timestamp for each actor.
     *
     * <p>Used when combining causal histories from different signal sources.
     *
     * @param other the other vector clock
     * @return new VectorClock with merged timestamps
     */
    public VectorClock merge(VectorClock other) {
        Map<String, Long> merged = new HashMap<>(clocks);
        other.clocks.forEach((actor, clock) ->
            merged.merge(actor, clock, Math::max)
        );
        return new VectorClock(merged);
    }

    /**
     * Converts to a single long value (max of all clocks).
     *
     * <p>Note: This loses causal information and should only be used for
     * rough ordering when full causal precision is not needed.
     *
     * @return maximum timestamp across all actors
     */
    public long toLong() {
        return clocks.values().stream()
            .max(Long::compare)
            .orElse(0L);
    }
}
