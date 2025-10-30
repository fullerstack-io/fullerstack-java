package io.fullerstack.kafka.broker.assessment;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Assesses broker health from raw JMX data and emits interpreted MonitorSignal.
 * <p>
 * <b>This is the CORE of signal-first architecture</b> - interpretation happens here,
 * WHERE context exists (baselines, trends, historical data).
 * <p>
 * <h3>Why This Component Exists:</h3>
 * Traditional monitoring emits raw metrics (heap=85%) without context.
 * Semiotic observability emits interpreted signals ("DEGRADED - heap 85% vs 60% baseline").
 * <p>
 * This assessor provides the contextual interpretation by:
 * <ul>
 *   <li>Comparing current values to baselines</li>
 *   <li>Detecting transitions ("heap went from 60% → 85% over 10min")</li>
 *   <li>Assessing severity using Monitors.Condition enum</li>
 *   <li>Determining confidence level</li>
 *   <li>Building rich payload with assessment, evidence, recommendations</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Raw JMX data
 * JmxData jmx = new JmxData(85.0, 0.75, ...);  // 85% heap, 75% CPU
 *
 * // Assess with baseline
 * MonitorSignal signal = assessor.assess("broker-1", subject, jmx);
 *
 * // Result: MonitorSignal.DEGRADED with payload:
 * // {
 * //   "assessment": "Broker experiencing operational degradation",
 * //   "heap_current": "85.0%",
 * //   "heap_baseline": "60.0%",
 * //   "heap_trend": "increasing",
 * //   "evidence": "Heap 85.0% vs baseline 60.0% (increasing trend)",
 * //   "recommendation": "Investigate heap usage: check for memory leaks..."
 * // }
 * }</pre>
 *
 * @author Fullerstack
 * @see BaselineService
 * @see MonitorSignal
 */
public class BrokerHealthAssessor {

    private final BaselineService baselineService;

    public BrokerHealthAssessor(BaselineService baselineService) {
        this.baselineService = baselineService;
    }

    /**
     * Assess broker health from raw JMX data.
     * <p>
     * This is where interpretation happens - comparing current values to baselines,
     * detecting trends, and building a signal with embedded meaning.
     *
     * @param brokerId Broker identifier
     * @param subject Substrates Subject for this broker
     * @param jmxData Raw JMX metrics (heap, CPU, etc.)
     * @return MonitorSignal with interpreted assessment
     */
    public MonitorSignal assess(String brokerId, Subject subject, JmxData jmxData) {
        // 1. Extract metrics
        double heapPercent = jmxData.heapPercent();
        double cpuUsage = jmxData.cpuUsage();
        long underReplicated = jmxData.underReplicatedPartitions();
        long offlinePartitions = jmxData.offlinePartitions();

        // 2. Get baselines
        double expectedHeap = baselineService.getExpectedHeapPercent(brokerId, Instant.now());
        double expectedCpu = baselineService.getExpectedCpuUsage(brokerId, Instant.now());

        // 3. Get trends
        String heapTrend = baselineService.getTrend(brokerId, "heap", Duration.ofMinutes(10));
        String cpuTrend = baselineService.getTrend(brokerId, "cpu", Duration.ofMinutes(10));

        // 4. Assess condition
        Monitors.Condition condition = determineCondition(
            heapPercent, expectedHeap,
            cpuUsage, expectedCpu,
            underReplicated,
            offlinePartitions,
            heapTrend, cpuTrend
        );

        // 5. Determine confidence
        Monitors.Confidence confidence = determineConfidence(
            baselineService.getConfidence(brokerId, "heap"),
            heapTrend
        );

        // 6. Build rich payload
        Map<String, String> payload = buildPayload(
            heapPercent, expectedHeap, heapTrend,
            cpuUsage, expectedCpu, cpuTrend,
            underReplicated,
            offlinePartitions,
            condition
        );

        // 7. Record observations for future baselines
        baselineService.recordObservation(brokerId, "heap", heapPercent, Instant.now());
        baselineService.recordObservation(brokerId, "cpu", cpuUsage, Instant.now());

        // 8. Create and return signal
        return MonitorSignal.create(subject, condition, confidence, payload);
    }

    /**
     * Determine broker condition based on metrics, baselines, and trends.
     * <p>
     * <b>Condition Logic:</b>
     * <ul>
     *   <li><b>DOWN</b>: Offline partitions exist</li>
     *   <li><b>DEFECTIVE</b>: Under-replicated partitions exist</li>
     *   <li><b>DEGRADED</b>: Heap > baseline * 1.3 with increasing trend, OR CPU > 90%</li>
     *   <li><b>DIVERGING</b>: Heap > baseline * 1.2 (moving away from baseline)</li>
     *   <li><b>ERRATIC</b>: Metrics showing high variance (erratic trend)</li>
     *   <li><b>CONVERGING</b>: Was problematic but improving (decreasing trend, still elevated)</li>
     *   <li><b>STABLE</b>: Everything within expected parameters</li>
     * </ul>
     */
    private Monitors.Condition determineCondition(
        double heapPercent, double expectedHeap,
        double cpuUsage, double expectedCpu,
        long underReplicated,
        long offlinePartitions,
        String heapTrend, String cpuTrend
    ) {
        // CRITICAL: Offline partitions → DOWN
        if (offlinePartitions > 0) {
            return Monitors.Condition.DOWN;
        }

        // DEFECTIVE: Under-replicated partitions
        if (underReplicated > 0) {
            return Monitors.Condition.DEFECTIVE;
        }

        // DEGRADED: Heap significantly above baseline with increasing trend
        if (heapPercent > expectedHeap * 1.3 && "increasing".equals(heapTrend)) {
            return Monitors.Condition.DEGRADED;
        }

        // DEGRADED: CPU very high (>90%)
        if (cpuUsage > 0.90) {
            return Monitors.Condition.DEGRADED;
        }

        // DIVERGING: Heap above baseline but not critical yet
        if (heapPercent > expectedHeap * 1.2) {
            return Monitors.Condition.DIVERGING;
        }

        // DIVERGING: CPU elevated (>80%)
        if (cpuUsage > 0.80) {
            return Monitors.Condition.DIVERGING;
        }

        // ERRATIC: High variance in metrics
        if ("erratic".equals(heapTrend) || "erratic".equals(cpuTrend)) {
            return Monitors.Condition.ERRATIC;
        }

        // CONVERGING: Was problematic but improving
        if ("decreasing".equals(heapTrend) && heapPercent > expectedHeap * 1.1) {
            return Monitors.Condition.CONVERGING;
        }

        // STABLE: Everything normal
        return Monitors.Condition.STABLE;
    }

    /**
     * Determine confidence level for the assessment.
     * <p>
     * <b>Confidence Logic:</b>
     * <ul>
     *   <li><b>CONFIRMED</b>: High baseline confidence (>0.8) AND stable trend</li>
     *   <li><b>MEASURED</b>: Moderate baseline confidence (>0.5)</li>
     *   <li><b>TENTATIVE</b>: Low baseline confidence (≤0.5) or limited data</li>
     * </ul>
     */
    private Monitors.Confidence determineConfidence(
        double baselineConfidence,
        String trend
    ) {
        // High confidence if we have solid baseline and stable trend
        if (baselineConfidence > 0.8 && "stable".equals(trend)) {
            return Monitors.Confidence.CONFIRMED;
        }

        // Medium confidence if baseline exists
        if (baselineConfidence > 0.5) {
            return Monitors.Confidence.MEASURED;
        }

        // Low confidence if limited data
        return Monitors.Confidence.TENTATIVE;
    }

    /**
     * Build rich payload with assessment, evidence, and recommendations.
     * <p>
     * The payload contains:
     * <ul>
     *   <li><b>Core metrics</b>: Current values, baselines, trends</li>
     *   <li><b>Assessment</b>: Human-readable interpretation of condition</li>
     *   <li><b>Evidence</b>: Specific data points supporting the assessment</li>
     *   <li><b>Recommendation</b>: Actionable guidance (if condition != STABLE)</li>
     * </ul>
     */
    private Map<String, String> buildPayload(
        double heapPercent, double expectedHeap, String heapTrend,
        double cpuUsage, double expectedCpu, String cpuTrend,
        long underReplicated,
        long offlinePartitions,
        Monitors.Condition condition
    ) {
        Map<String, String> payload = new HashMap<>();

        // Core metrics
        payload.put("metric", "broker_health");
        payload.put("heap_current", String.format("%.1f%%", heapPercent));
        payload.put("heap_baseline", String.format("%.1f%%", expectedHeap));
        payload.put("heap_trend", heapTrend);
        payload.put("cpu_current", String.format("%.2f", cpuUsage));
        payload.put("cpu_baseline", String.format("%.2f", expectedCpu));
        payload.put("cpu_trend", cpuTrend);

        if (underReplicated > 0) {
            payload.put("under_replicated_partitions", String.valueOf(underReplicated));
        }

        if (offlinePartitions > 0) {
            payload.put("offline_partitions", String.valueOf(offlinePartitions));
        }

        // Interpretation
        payload.put("assessment", buildAssessment(condition, heapPercent, expectedHeap, underReplicated, offlinePartitions));

        // Evidence
        payload.put("evidence", buildEvidence(heapPercent, expectedHeap, heapTrend, cpuUsage, underReplicated, offlinePartitions));

        // Recommendation (only if not STABLE)
        if (condition != Monitors.Condition.STABLE) {
            payload.put("recommendation", buildRecommendation(condition, heapPercent, expectedHeap, cpuUsage, underReplicated, offlinePartitions));
        }

        return payload;
    }

    /**
     * Build human-readable assessment for the condition.
     */
    private String buildAssessment(Monitors.Condition condition, double heapPercent, double expectedHeap, long underReplicated, long offlinePartitions) {
        return switch (condition) {
            case STABLE -> "Broker operating normally within expected parameters";
            case CONVERGING -> "Broker recovering from previous issues, metrics improving";
            case DIVERGING -> String.format("Broker diverging from baseline (heap %.1f%% vs %.1f%% expected)", heapPercent, expectedHeap);
            case ERRATIC -> "Broker showing unstable behavior with high metric variance";
            case DEGRADED -> "Broker experiencing operational degradation requiring attention";
            case DEFECTIVE -> underReplicated > 0
                ? String.format("Broker DEFECTIVE with %d under-replicated partitions", underReplicated)
                : "Broker in defective state with severe operational issues";
            case DOWN -> offlinePartitions > 0
                ? String.format("Broker DOWN with %d offline partitions", offlinePartitions)
                : "Broker is non-operational";
        };
    }

    /**
     * Build evidence string supporting the assessment.
     */
    private String buildEvidence(double heapPercent, double expectedHeap, String heapTrend, double cpuUsage, long underReplicated, long offlinePartitions) {
        StringBuilder evidence = new StringBuilder();

        if (offlinePartitions > 0) {
            evidence.append(String.format("%d offline partitions. ", offlinePartitions));
        }

        if (underReplicated > 0) {
            evidence.append(String.format("%d under-replicated partitions. ", underReplicated));
        }

        if (heapPercent > expectedHeap * 1.2) {
            evidence.append(String.format("Heap %.1f%% vs baseline %.1f%% (%s trend). ",
                heapPercent, expectedHeap, heapTrend));
        }

        if (cpuUsage > 0.80) {
            evidence.append(String.format("CPU %.0f%%. ", cpuUsage * 100));
        }

        String result = evidence.toString().trim();
        return result.isEmpty() ? "All metrics within expected ranges" : result;
    }

    /**
     * Build actionable recommendation based on condition.
     */
    private String buildRecommendation(Monitors.Condition condition, double heapPercent, double expectedHeap, double cpuUsage, long underReplicated, long offlinePartitions) {
        if (offlinePartitions > 0) {
            return "CRITICAL: Investigate offline partitions immediately - check broker logs and disk health";
        }

        if (underReplicated > 0) {
            return "Investigate replication lag: check broker connectivity, ISR status, and network health";
        }

        if (heapPercent > expectedHeap * 1.3) {
            return "Investigate heap usage: check for memory leaks, increase heap size, or analyze GC behavior";
        }

        if (cpuUsage > 0.90) {
            return "Investigate CPU usage: check request load, thread pool saturation, or consider scaling";
        }

        if (condition == Monitors.Condition.DIVERGING) {
            return "Monitor closely for trend changes - metrics diverging from baseline";
        }

        if (condition == Monitors.Condition.ERRATIC) {
            return "Investigate metric instability - check for bursty traffic patterns or resource contention";
        }

        return "Continue monitoring for changes";
    }

    /**
     * Raw JMX data container.
     * <p>
     * This is the input to the assessor - raw measurements without interpretation.
     * The assessor transforms this into a MonitorSignal with embedded meaning.
     */
    public record JmxData(
        double heapPercent,
        double cpuUsage,
        long requestRate,
        long underReplicatedPartitions,
        long offlinePartitions,
        long networkIdlePercent,
        long requestHandlerIdlePercent
    ) {
        public JmxData {
            if (heapPercent < 0 || heapPercent > 100) {
                throw new IllegalArgumentException("heapPercent must be 0-100, got: " + heapPercent);
            }
            if (cpuUsage < 0 || cpuUsage > 1.0) {
                throw new IllegalArgumentException("cpuUsage must be 0.0-1.0, got: " + cpuUsage);
            }
            if (requestRate < 0) {
                throw new IllegalArgumentException("requestRate must be non-negative, got: " + requestRate);
            }
            if (underReplicatedPartitions < 0) {
                throw new IllegalArgumentException("underReplicatedPartitions must be non-negative, got: " + underReplicatedPartitions);
            }
            if (offlinePartitions < 0) {
                throw new IllegalArgumentException("offlinePartitions must be non-negative, got: " + offlinePartitions);
            }
        }
    }
}
