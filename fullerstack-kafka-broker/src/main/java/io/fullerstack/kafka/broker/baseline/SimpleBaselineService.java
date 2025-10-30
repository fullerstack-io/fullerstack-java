package io.fullerstack.kafka.broker.baseline;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple baseline service using static thresholds and basic trending.
 * <p>
 * This is a minimal implementation suitable for initial deployment.
 * Can be replaced with more sophisticated implementations that use:
 * <ul>
 *   <li>Historical data analysis</li>
 *   <li>Time-of-day patterns</li>
 *   <li>Machine learning models</li>
 *   <li>Anomaly detection algorithms</li>
 * </ul>
 *
 * <h3>Current Strategy:</h3>
 * - Heap baseline: 70% (typical healthy state)
 * - CPU baseline: 0.60 (60% utilization)
 * - Producer latency: 100ms (typical for well-tuned clusters)
 * - Trend detection: Simple comparison of recent vs older averages
 *
 * @author Fullerstack
 */
public class SimpleBaselineService implements BaselineService {

    // Static baselines (can be made configurable)
    private static final double DEFAULT_HEAP_BASELINE = 70.0;  // 70%
    private static final double DEFAULT_CPU_BASELINE = 0.60;   // 60%
    private static final long DEFAULT_REQUEST_RATE = 1000;     // 1000 req/s
    private static final long DEFAULT_PRODUCER_LATENCY = 100;  // 100ms

    // Observation history for trend detection
    private final Map<String, ObservationHistory> history = new ConcurrentHashMap<>();

    @Override
    public double getExpectedHeapPercent(String brokerId, Instant time) {
        // Simple implementation: return static baseline
        // Future: could vary by time of day, historical patterns
        return DEFAULT_HEAP_BASELINE;
    }

    @Override
    public double getExpectedCpuUsage(String brokerId, Instant time) {
        return DEFAULT_CPU_BASELINE;
    }

    @Override
    public long getExpectedRequestRate(String brokerId, Instant time) {
        return DEFAULT_REQUEST_RATE;
    }

    @Override
    public long getExpectedProducerLatency(String producerId, String topic) {
        // Could be topic-specific in future (large messages → higher latency)
        return DEFAULT_PRODUCER_LATENCY;
    }

    @Override
    public String getTrend(String entityId, String metric, Duration window) {
        String key = entityId + ":" + metric;
        ObservationHistory obs = history.get(key);

        if (obs == null || obs.isEmpty()) {
            return "unknown";  // No data
        }

        // Simple trend: compare recent average vs older average
        double recentAvg = obs.getAverageInWindow(window);
        double olderAvg = obs.getAverageBeforeWindow(window);

        if (Math.abs(recentAvg - olderAvg) < 0.05 * olderAvg) {
            return "stable";  // Within 5% of older average
        } else if (recentAvg > olderAvg * 1.1) {
            return "increasing";  // More than 10% increase
        } else if (recentAvg < olderAvg * 0.9) {
            return "decreasing";  // More than 10% decrease
        } else if (obs.getStdDev() > olderAvg * 0.3) {
            return "erratic";  // High variance
        }

        return "stable";
    }

    @Override
    public double getConfidence(String entityId, String metric) {
        String key = entityId + ":" + metric;
        ObservationHistory obs = history.get(key);

        if (obs == null || obs.isEmpty()) {
            return 0.0;  // No data, no confidence
        }

        // Confidence based on:
        // 1. Number of observations (more is better)
        // 2. Stability (lower variance is better)
        // 3. Recency (fresher is better)

        int count = obs.getCount();
        double countFactor = Math.min(1.0, count / 100.0);  // Full confidence at 100+ observations

        double variance = obs.getStdDev() / (obs.getAverage() + 1.0);  // Coefficient of variation
        double stabilityFactor = Math.max(0.0, 1.0 - variance);  // Lower variance → higher confidence

        Duration age = Duration.between(obs.getNewestTimestamp(), Instant.now());
        double recencyFactor = Math.max(0.0, 1.0 - (age.toMinutes() / 60.0));  // Decay over 1 hour

        return (countFactor + stabilityFactor + recencyFactor) / 3.0;
    }

    @Override
    public void recordObservation(String entityId, String metric, double value, Instant timestamp) {
        String key = entityId + ":" + metric;
        history.computeIfAbsent(key, k -> new ObservationHistory())
            .add(value, timestamp);
    }

    @Override
    public boolean hasBaseline(String entityId) {
        // Simple implementation always has baseline (static)
        // Future: could check if historical data exists
        return true;
    }

    /**
     * Tracks observation history for trend and confidence calculations.
     */
    private static class ObservationHistory {
        private static final int MAX_OBSERVATIONS = 1000;  // Keep last 1000 observations

        private final java.util.Deque<Observation> observations = new java.util.ArrayDeque<>();
        private double sum = 0.0;
        private double sumSquares = 0.0;
        private int count = 0;

        synchronized void add(double value, Instant timestamp) {
            // Remove oldest if at capacity
            if (observations.size() >= MAX_OBSERVATIONS) {
                Observation removed = observations.removeFirst();
                sum -= removed.value;
                sumSquares -= removed.value * removed.value;
                count--;
            }

            // Add new observation
            observations.addLast(new Observation(value, timestamp));
            sum += value;
            sumSquares += value * value;
            count++;
        }

        synchronized double getAverage() {
            return count > 0 ? sum / count : 0.0;
        }

        synchronized double getStdDev() {
            if (count < 2) return 0.0;
            double mean = sum / count;
            double variance = (sumSquares / count) - (mean * mean);
            return Math.sqrt(Math.max(0.0, variance));
        }

        synchronized double getAverageInWindow(Duration window) {
            Instant cutoff = Instant.now().minus(window);
            return observations.stream()
                .filter(obs -> obs.timestamp.isAfter(cutoff))
                .mapToDouble(obs -> obs.value)
                .average()
                .orElse(0.0);
        }

        synchronized double getAverageBeforeWindow(Duration window) {
            Instant cutoff = Instant.now().minus(window);
            return observations.stream()
                .filter(obs -> obs.timestamp.isBefore(cutoff))
                .mapToDouble(obs -> obs.value)
                .average()
                .orElse(0.0);
        }

        synchronized Instant getNewestTimestamp() {
            return observations.isEmpty()
                ? Instant.EPOCH
                : observations.getLast().timestamp;
        }

        synchronized int getCount() {
            return count;
        }

        synchronized boolean isEmpty() {
            return observations.isEmpty();
        }

        private record Observation(double value, Instant timestamp) {}
    }
}
