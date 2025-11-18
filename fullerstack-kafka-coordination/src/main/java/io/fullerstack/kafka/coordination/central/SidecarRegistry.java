package io.fullerstack.kafka.coordination.central;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PRODUCTION: Registry of discovered producer/consumer sidecars.
 * <p>
 * **Auto-Discovery via Topic-Based Registration:**
 * - Sidecars self-register by sending speech acts to Kafka topics
 * - First message from sidecar → automatically registered
 * - Heartbeat messages → update last-seen timestamp
 * - No heartbeat for 30s → marked INACTIVE
 * <p>
 * **100% Production Pattern:**
 * - Same as Kafka Connect worker registry
 * - Same as Kafka Streams instance registry
 * - NO hardcoded sidecar lists
 * - NO fallback to defaults
 * <p>
 * **Thread-Safe:**
 * - Uses ConcurrentHashMap for concurrent access
 * - Safe for multiple consumers/threads
 *
 * @since 1.0.0
 */
public class SidecarRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SidecarRegistry.class);

    private static final long INACTIVE_THRESHOLD_MS = 30_000;  // 30 seconds

    private final Map<String, SidecarInfo> sidecars = new ConcurrentHashMap<>();

    /**
     * Register or update a sidecar.
     * <p>
     * Called when a message is received from the sidecar (auto-discovery).
     *
     * @param sidecarId   Sidecar identifier (e.g., "producer-1", "consumer-2")
     * @param type        Sidecar type ("producer" or "consumer")
     * @param jmxEndpoint JMX endpoint for metrics (e.g., "localhost:11001")
     */
    public void registerSidecar(String sidecarId, String type, String jmxEndpoint) {
        SidecarInfo existing = sidecars.get(sidecarId);

        if (existing == null) {
            // New sidecar discovered!
            SidecarInfo info = new SidecarInfo(sidecarId, type, jmxEndpoint);
            sidecars.put(sidecarId, info);

            logger.info("🆕 AUTO-DISCOVERED sidecar: {} (type={}, jmx={})",
                sidecarId, type, jmxEndpoint);
        } else {
            // Update last-seen timestamp (heartbeat)
            existing.updateLastSeen();

            logger.trace("💓 Heartbeat from sidecar: {}", sidecarId);
        }
    }

    /**
     * Get all registered sidecars.
     *
     * @return list of sidecar info objects
     */
    public List<SidecarInfo> getAllSidecars() {
        return List.copyOf(sidecars.values());
    }

    /**
     * Get active sidecars (received message in last 30 seconds).
     *
     * @return list of active sidecar info objects
     */
    public List<SidecarInfo> getActiveSidecars() {
        long now = System.currentTimeMillis();

        return sidecars.values().stream()
            .filter(info -> (now - info.getLastSeenMs()) < INACTIVE_THRESHOLD_MS)
            .collect(Collectors.toList());
    }

    /**
     * Get inactive sidecars (no message in last 30 seconds).
     *
     * @return list of inactive sidecar info objects
     */
    public List<SidecarInfo> getInactiveSidecars() {
        long now = System.currentTimeMillis();

        return sidecars.values().stream()
            .filter(info -> (now - info.getLastSeenMs()) >= INACTIVE_THRESHOLD_MS)
            .collect(Collectors.toList());
    }

    /**
     * Get sidecar info by ID.
     *
     * @param sidecarId sidecar identifier
     * @return sidecar info, or null if not found
     */
    public SidecarInfo getSidecar(String sidecarId) {
        return sidecars.get(sidecarId);
    }

    /**
     * Check if sidecar is registered.
     *
     * @param sidecarId sidecar identifier
     * @return true if registered (may be inactive)
     */
    public boolean isRegistered(String sidecarId) {
        return sidecars.containsKey(sidecarId);
    }

    /**
     * Check if sidecar is active (received message recently).
     *
     * @param sidecarId sidecar identifier
     * @return true if active
     */
    public boolean isActive(String sidecarId) {
        SidecarInfo info = sidecars.get(sidecarId);
        if (info == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        return (now - info.getLastSeenMs()) < INACTIVE_THRESHOLD_MS;
    }

    /**
     * Get count of registered sidecars.
     *
     * @return total count (active + inactive)
     */
    public int getCount() {
        return sidecars.size();
    }

    /**
     * Get count of active sidecars.
     *
     * @return active count
     */
    public int getActiveCount() {
        return getActiveSidecars().size();
    }

    /**
     * Information about a discovered sidecar.
     */
    public static class SidecarInfo {
        private final String sidecarId;
        private final String type;  // "producer" or "consumer"
        private final String jmxEndpoint;
        private final Instant discoveredAt;
        private volatile long lastSeenMs;

        public SidecarInfo(String sidecarId, String type, String jmxEndpoint) {
            this.sidecarId = sidecarId;
            this.type = type;
            this.jmxEndpoint = jmxEndpoint;
            this.discoveredAt = Instant.now();
            this.lastSeenMs = System.currentTimeMillis();
        }

        public void updateLastSeen() {
            this.lastSeenMs = System.currentTimeMillis();
        }

        public String getSidecarId() {
            return sidecarId;
        }

        public String getType() {
            return type;
        }

        public String getJmxEndpoint() {
            return jmxEndpoint;
        }

        public Instant getDiscoveredAt() {
            return discoveredAt;
        }

        public long getLastSeenMs() {
            return lastSeenMs;
        }

        public boolean isActive() {
            long now = System.currentTimeMillis();
            return (now - lastSeenMs) < INACTIVE_THRESHOLD_MS;
        }

        @Override
        public String toString() {
            return String.format("SidecarInfo{id=%s, type=%s, jmx=%s, active=%s}",
                sidecarId, type, jmxEndpoint, isActive());
        }
    }
}
