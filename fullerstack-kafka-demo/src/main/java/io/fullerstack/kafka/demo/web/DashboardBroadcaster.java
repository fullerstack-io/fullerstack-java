package io.fullerstack.kafka.demo.web;

import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Dashboard broadcaster - proper Substrates Subscriber pattern.
 * <p>
 * Creates subscriber that observes signals and broadcasts to WebSocket dashboard.
 * <p>
 * <b>Substrates Paradigm:</b>
 * <ul>
 *   <li>Dashboard is a SUBSCRIBER, not imperative broadcaster</li>
 *   <li>Signals flow: Circuit → Conduit → Channel → Pipe → Subscriber</li>
 *   <li>WebSocket broadcasting is internal implementation detail</li>
 * </ul>
 *
 * <b>Usage:</b>
 * <pre>
 * DashboardBroadcaster broadcaster = new DashboardBroadcaster();
 * queues.subscribe(broadcaster.subscriber("queues"));
 * actors.subscribe(broadcaster.subscriber("actors"));
 * </pre>
 */
public class DashboardBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(DashboardBroadcaster.class);

    /**
     * Creates a subscriber for observing signals and broadcasting to dashboard.
     *
     * @param conduitName conduit name for subscriber identification
     * @return Substrates Subscriber that broadcasts signals to WebSocket
     */
    public <S> Subscriber subscriber(String conduitName) {
        return cortex().subscriber(
            cortex().name("dashboard-" + conduitName),
            (Subject<Channel<S>> subject, Registrar<S> registrar) -> {
                registrar.register(signal -> {
                    try {
                        String entityId = subject.name().toString();
                        Map<String, Object> signalData = Map.of(
                            "type", signal.getClass().getSimpleName(),
                            "signal", signal.toString(),
                            "timestamp", System.currentTimeMillis()
                        );
                        DashboardWebSocket.broadcast(entityId, signalData);
                    } catch (Throwable e) {
                        logger.trace("Dashboard broadcast error (non-critical): {}", e.getMessage());
                    }
                });
            }
        );
    }
}
