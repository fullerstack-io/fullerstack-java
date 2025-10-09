package io.fullerstack.serventis;

import java.util.Map;

/**
 * Vector clock for causal ordering of signals across distributed sensor agents.
 *
 * <p>Immutable record that tracks logical timestamps for each agent to determine
 * causal relationships between signals (happens-before relation).
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
}
