package io.fullerstack.kafka.consumer.composers;

import io.fullerstack.kafka.consumer.models.ConsumerMetrics;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Cell Composer that transforms ConsumerMetrics into MonitorSignal.
 * <p>
 * Assesses consumer health based on lag, processing latency, and rebalance frequency.
 */
public class ConsumerHealthCellComposer implements Composer<Pipe<ConsumerMetrics>, MonitorSignal> {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerHealthCellComposer.class);

    @Override
    public Pipe<ConsumerMetrics> compose(Channel<MonitorSignal> channel) {
        Subject channelSubject = channel.subject();
        Pipe<MonitorSignal> outputPipe = channel.pipe();

        return metrics -> {
            Monitors.Condition condition = assessCondition(metrics);
            Monitors.Confidence confidence = assessConfidence(metrics);
            Map<String, String> context = buildContext(metrics);

            MonitorSignal signal = switch (condition) {
                case STABLE -> MonitorSignal.stable(channelSubject, context);
                case DEGRADED -> MonitorSignal.degraded(channelSubject, confidence, context);
                case DOWN -> MonitorSignal.down(channelSubject, confidence, context);
                default -> MonitorSignal.create(channelSubject, condition, confidence, context);
            };

            outputPipe.emit(signal);
            logger.trace("Transformed ConsumerMetrics → MonitorSignal: {}", condition);
        };
    }

    private Monitors.Condition assessCondition(ConsumerMetrics metrics) {
        // STABLE: lag < 1000, processing < 100ms, rebalances < 5
        if (metrics.totalLag() < 1000 && metrics.avgProcessingLatencyMs() < 100 && metrics.rebalanceCount() < 5) {
            return Monitors.Condition.STABLE;
        }

        // DOWN: lag > 10000, processing > 500ms, rebalances > 10
        if (metrics.totalLag() > 10000 || metrics.avgProcessingLatencyMs() > 500 || metrics.rebalanceCount() > 10) {
            return Monitors.Condition.DOWN;
        }

        // DEGRADED: moderate issues
        return Monitors.Condition.DEGRADED;
    }

    private Monitors.Confidence assessConfidence(ConsumerMetrics metrics) {
        long ageMs = System.currentTimeMillis() - metrics.timestamp();

        if (ageMs < 5000) {
            return Monitors.Confidence.CONFIRMED;  // < 5s
        } else if (ageMs < 30000) {
            return Monitors.Confidence.MEASURED;  // 5-30s
        } else {
            return Monitors.Confidence.TENTATIVE;  // > 30s
        }
    }

    private Map<String, String> buildContext(ConsumerMetrics metrics) {
        Map<String, String> context = new HashMap<>();
        context.put("totalLag", String.valueOf(metrics.totalLag()));
        context.put("avgLagPerPartition", String.format("%.2f", metrics.avgLagPerPartition()));
        context.put("fetchRate", String.format("%.2f", metrics.fetchRate()));
        context.put("avgProcessingLatencyMs", String.format("%.2f", metrics.avgProcessingLatencyMs()));
        context.put("rebalanceCount", String.valueOf(metrics.rebalanceCount()));
        context.put("assignedPartitions", String.valueOf(metrics.assignedPartitionCount()));
        return context;
    }
}
