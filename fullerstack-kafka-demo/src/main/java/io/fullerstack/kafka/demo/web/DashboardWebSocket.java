package io.fullerstack.kafka.demo.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket endpoint for dashboard real-time updates.
 *
 * PASSIVE OBSERVER - Does NOT affect production behavior.
 * Only mirrors OODA loop signals for visualization.
 */
@WebSocket
public class DashboardWebSocket {

    private static final CopyOnWriteArraySet<Session> sessions = new CopyOnWriteArraySet<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @OnWebSocketConnect
    public void onConnect(Session session) {
        sessions.add(session);
        System.out.println("Dashboard client connected: " + session.getRemoteAddress());

        // Send initial state
        try {
            String welcome = objectMapper.writeValueAsString(Map.of(
                "type", "connection",
                "status", "connected",
                "timestamp", System.currentTimeMillis()
            ));
            session.getRemote().sendString(welcome);
        } catch (IOException e) {
            System.err.println("Error sending welcome message: " + e.getMessage());
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        sessions.remove(session);
        System.out.println("Dashboard client disconnected: " + session.getRemoteAddress());
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("WebSocket error: " + error.getMessage());
        sessions.remove(session);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        System.out.println("Received message from dashboard: " + message);

        // Handle scenario triggers from UI
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            String type = (String) msg.get("type");

            if ("trigger-scenario".equals(type)) {
                String scenarioId = (String) msg.get("scenarioId");
                handleScenarioTrigger(scenarioId);
            }
        } catch (Exception e) {
            System.err.println("Error processing dashboard message: " + e.getMessage());
        }
    }

    /**
     * Broadcast OODA signal to all connected dashboard clients.
     * Called by production OODA loop (passive observation).
     */
    public static void broadcastSignal(String layer, String sidecarId, Map<String, Object> signal) {
        if (sessions.isEmpty()) {
            return; // No clients connected, skip serialization
        }

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                "type", "ooda-signal",
                "layer", layer,           // OBSERVE, ORIENT, DECIDE, ACT
                "sidecarId", sidecarId,
                "signal", signal,
                "timestamp", System.currentTimeMillis()
            ));

            broadcast(json);
        } catch (IOException e) {
            System.err.println("Error broadcasting signal: " + e.getMessage());
        }
    }

    /**
     * Broadcast system event to all connected clients.
     */
    public static void broadcastEvent(String eventType, Map<String, Object> details) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                "type", "system-event",
                "eventType", eventType,
                "details", details,
                "timestamp", System.currentTimeMillis()
            ));

            broadcast(json);
        } catch (IOException e) {
            System.err.println("Error broadcasting event: " + e.getMessage());
        }
    }

    private static void broadcast(String message) {
        sessions.removeIf(session -> {
            try {
                if (session.isOpen()) {
                    session.getRemote().sendString(message);
                    return false;
                } else {
                    return true; // Remove closed session
                }
            } catch (IOException e) {
                System.err.println("Error sending to client: " + e.getMessage());
                return true; // Remove failed session
            }
        });
    }

    private void handleScenarioTrigger(String scenarioId) {
        System.out.println("Scenario triggered from dashboard: " + scenarioId);

        // Broadcast to all clients that scenario was triggered
        broadcastEvent("scenario-triggered", Map.of(
            "scenarioId", scenarioId,
            "status", "starting"
        ));

        // TODO: Integrate with ChaosController when implemented
        // ChaosController.triggerScenario(scenarioId);
    }

    public static int getConnectedClients() {
        return sessions.size();
    }
}
