package io.fullerstack.ocpp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OCPP Central System server that manages connections to multiple chargers.
 * <p>
 * This is a simplified implementation focused on demonstrating the
 * Substrates integration patterns. A production implementation would use
 * a full OCPP library (e.g., ChargeTimeEU, SteVe) with WebSocket support.
 * </p>
 * <p>
 * Key responsibilities:
 * - Manage WebSocket connections to chargers
 * - Route incoming messages to registered handlers
 * - Execute commands by routing them to chargers
 * - Track connection state and heartbeat monitoring
 * </p>
 */
public class OcppCentralSystem implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OcppCentralSystem.class);

    private final int port;
    private final Map<String, ChargerConnection> connections;
    private final CopyOnWriteArrayList<OcppMessageHandler> messageHandlers;
    private volatile boolean running;

    public OcppCentralSystem(int port) {
        this.port = port;
        this.connections = new ConcurrentHashMap<>();
        this.messageHandlers = new CopyOnWriteArrayList<>();
        this.running = false;
    }

    /**
     * Register a handler for incoming OCPP messages.
     * Multiple handlers can be registered - all will be notified of messages.
     */
    public void registerMessageHandler(OcppMessageHandler handler) {
        messageHandlers.add(handler);
        logger.info("Registered OCPP message handler: {}", handler.getClass().getSimpleName());
    }

    /**
     * Start the Central System server and begin accepting connections.
     */
    public void start() {
        if (running) {
            logger.warn("OCPP Central System already running");
            return;
        }

        logger.info("Starting OCPP Central System on port {}", port);
        running = true;

        // In a real implementation, this would:
        // 1. Start a WebSocket server (e.g., using Java-WebSocket library)
        // 2. Listen for incoming connections from chargers
        // 3. Handle OCPP message framing and JSON parsing
        // 4. Route messages to registered handlers

        logger.info("OCPP Central System started successfully");
    }

    /**
     * Stop the Central System server and close all connections.
     */
    public void stop() {
        if (!running) {
            logger.warn("OCPP Central System not running");
            return;
        }

        logger.info("Stopping OCPP Central System");
        running = false;

        // Close all charger connections
        connections.values().forEach(ChargerConnection::close);
        connections.clear();

        logger.info("OCPP Central System stopped");
    }

    /**
     * Execute a command by sending it to the target charger.
     */
    public boolean executeCommand(OcppCommand command) {
        ChargerConnection connection = connections.get(command.chargerId());
        if (connection == null) {
            logger.warn("Cannot execute command {}: charger {} not connected",
                command.getClass().getSimpleName(), command.chargerId());
            return false;
        }

        return connection.sendCommand(command);
    }

    /**
     * Simulate receiving a message from a charger.
     * In production, this would be called by the WebSocket handler.
     */
    public void simulateIncomingMessage(OcppMessage message) {
        logger.debug("Received OCPP message from {}: {}",
            message.chargerId(), message.getClass().getSimpleName());

        // Ensure connection exists
        connections.computeIfAbsent(
            message.chargerId(),
            id -> new ChargerConnection(id)
        );

        // Notify all registered handlers
        for (OcppMessageHandler handler : messageHandlers) {
            try {
                handler.handleMessage(message);
            } catch (Exception e) {
                logger.error("Error in message handler {}: {}",
                    handler.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Check if a charger is currently connected.
     */
    public boolean isChargerConnected(String chargerId) {
        ChargerConnection connection = connections.get(chargerId);
        return connection != null && connection.isConnected();
    }

    /**
     * Get the number of connected chargers.
     */
    public int getConnectedChargerCount() {
        return (int) connections.values().stream()
            .filter(ChargerConnection::isConnected)
            .count();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Represents a connection to a single charger.
     */
    private static class ChargerConnection {
        private final String chargerId;
        private volatile boolean connected;

        ChargerConnection(String chargerId) {
            this.chargerId = chargerId;
            this.connected = true;
        }

        boolean sendCommand(OcppCommand command) {
            if (!connected) {
                logger.warn("Cannot send command to {}: not connected", chargerId);
                return false;
            }

            logger.info("Sending command {} to charger {}",
                command.getClass().getSimpleName(), chargerId);

            // In production, this would:
            // 1. Serialize command to OCPP JSON format
            // 2. Send via WebSocket to charger
            // 3. Wait for acknowledgment
            // 4. Handle response/error

            return true;  // Simulate success
        }

        boolean isConnected() {
            return connected;
        }

        void close() {
            connected = false;
            logger.info("Closed connection to charger {}", chargerId);
        }
    }
}
