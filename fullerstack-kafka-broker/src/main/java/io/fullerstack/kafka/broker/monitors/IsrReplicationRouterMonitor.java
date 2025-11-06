package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.IsrMetrics;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Routers;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Emits Routers.Signal for ISR replication metrics using Serventis RC6 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with Routers API vocabulary (SEND/RECEIVE/DROP), NOT interpretations.
 *
 * <h3>RC6 Routers API - ISR Replication Topology</h3>
 * Models Kafka ISR replication as a routing problem where the leader "routes" replication
 * data to follower replicas:
 * <ul>
 *   <li><b>SEND</b> - Leader sending replication data to follower (normal replication)</li>
 *   <li><b>RECEIVE</b> - Follower successfully receiving and in-sync (ISR expand)</li>
 *   <li><b>DROP</b> - Follower dropped from ISR topology (ISR shrink, lag threshold exceeded)</li>
 * </ul>
 *
 * <h3>Signal Emission Logic</h3>
 * <pre>
 * ISR Shrink (Count increased):
 *   - router.drop() - Follower fell behind and removed from ISR topology
 *
 * ISR Expand (Count increased):
 *   - router.receive() - Follower caught up and added back to ISR topology
 *
 * Replica Lag (MaxLag > 1000):
 *   - leader.send() + follower lag tracking - Early warning before ISR shrink
 *
 * Fetch Rate (MinFetchRate < 1.0):
 *   - router.receive() with low rate - Replication slowing down
 * </pre>
 *
 * <p><b>Router Naming Convention</b>:
 * <ul>
 *   <li>Leader router: {@code broker-{id}.leader}</li>
 *   <li>Follower router: {@code broker-{id}.follower}</li>
 * </ul>
 *
 * <p><b>Note:</b> Simple threshold-based signal emission. Contextual assessment using baselines,
 * trends, and recommendations will be added in Epic 2 via Observers (Layer 4 - Semiosphere).
 *
 * @see IsrMetrics
 * @see Routers
 */
public class IsrReplicationRouterMonitor {

    private static final Logger logger = LoggerFactory.getLogger(IsrReplicationRouterMonitor.class);

    private final Name circuitName;
    private final Channel<Routers.Sign> channel;
    private final Router leaderRouter;
    private final Router followerRouter;

    /**
     * Creates an IsrReplicationRouterMonitor.
     *
     * @param circuitName Circuit name for logging
     * @param conduit     Conduit for Routers
     * @param brokerId    Broker identifier (e.g., "broker-1")
     * @throws NullPointerException if any parameter is null
     */
    public IsrReplicationRouterMonitor(
        Name circuitName,
        Conduit<Router, Routers.Sign> conduit,
        String brokerId
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        Objects.requireNonNull(conduit, "conduit cannot be null");
        Objects.requireNonNull(brokerId, "brokerId cannot be null");

        // Get routers from Conduit (creates via composer)
        this.leaderRouter = conduit.get(cortex().name(brokerId + ".leader"));
        this.followerRouter = conduit.get(cortex().name(brokerId + ".follower"));
        this.channel = null; // Not needed
    }

    /**
     * Emits Routers.Signal for ISR replication metrics (delta-based).
     * <p>
     * Caller must provide delta metrics (current - previous) to detect new events.
     * Cumulative counts will not trigger signals.
     *
     * @param deltaMetrics Delta ISR metrics (shrinks/expands since last poll)
     * @throws NullPointerException if deltaMetrics is null
     */
    public void emit(IsrMetrics deltaMetrics) {
        Objects.requireNonNull(deltaMetrics, "deltaMetrics cannot be null");

        try {
            // ISR Shrink - follower dropped from ISR topology
            if (deltaMetrics.hasShrinkEvents()) {
                long shrinkCount = deltaMetrics.isrShrinkCount();
                // Emit DROP signal for each shrink event
                for (int i = 0; i < shrinkCount; i++) {
                    followerRouter.drop();
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted {} DROP signals for ISR shrinks on {}",
                        shrinkCount, deltaMetrics.brokerId());
                }
            }

            // ISR Expand - follower added back to ISR topology
            if (deltaMetrics.hasExpandEvents()) {
                long expandCount = deltaMetrics.isrExpandCount();
                // Emit RECEIVE signal for each expand event
                for (int i = 0; i < expandCount; i++) {
                    followerRouter.receive();
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted {} RECEIVE signals for ISR expands on {}",
                        expandCount, deltaMetrics.brokerId());
                }
            }

            // Replica Lag - leader sending, follower lagging
            if (deltaMetrics.isReplicaLagging()) {
                // Leader is sending replication data
                leaderRouter.send();

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted SEND signal for replica lag {} on {}",
                        deltaMetrics.replicaMaxLag(), deltaMetrics.brokerId());
                }
            }

            // Fetch Rate - replication receive rate tracking
            if (deltaMetrics.isFetchRateDegraded()) {
                // Low fetch rate indicates replication slowing down
                // Still emitting RECEIVE but at degraded rate
                followerRouter.receive();

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted RECEIVE signal for degraded fetch rate {} on {}",
                        deltaMetrics.replicaMinFetchRate(), deltaMetrics.brokerId());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to emit Routers.Signal for {}: {}",
                deltaMetrics.brokerId(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Emits Routers.Signal for current ISR metrics (absolute values).
     * <p>
     * This variant is used when delta computation is not available (e.g., first poll).
     * Only emits signals for current lag/fetch rate conditions, not shrink/expand events.
     *
     * @param metrics Current ISR metrics
     * @throws NullPointerException if metrics is null
     */
    public void emitAbsolute(IsrMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            // Only emit lag and fetch rate signals (not shrink/expand without delta)
            if (metrics.isReplicaLagging()) {
                leaderRouter.send();

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted SEND signal for current replica lag {} on {}",
                        metrics.replicaMaxLag(), metrics.brokerId());
                }
            }

            if (metrics.isFetchRateDegraded()) {
                followerRouter.receive();

                if (logger.isDebugEnabled()) {
                    logger.debug("Emitted RECEIVE signal for current fetch rate {} on {}",
                        metrics.replicaMinFetchRate(), metrics.brokerId());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to emit absolute Routers.Signal for {}: {}",
                metrics.brokerId(),
                e.getMessage(),
                e);
        }
    }
}
