package io.fullerstack.ocpp.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fullerstack.ocpp.model.Charger;
import io.fullerstack.ocpp.model.Transaction;
import io.fullerstack.ocpp.offline.OfflineStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API for querying charger status, transactions, and system health.
 * <p>
 * Provides external access to the OCPP observability system via HTTP endpoints.
 * Applications and dashboards can query:
 * - Charger availability and status
 * - Active and historical transactions
 * - System health and connectivity
 * </p>
 * <p>
 * Endpoints:
 * - GET /api/chargers - List all chargers
 * - GET /api/chargers/{id} - Get specific charger details
 * - GET /api/transactions - List active transactions
 * - GET /api/transactions/history/{chargerId} - Get transaction history
 * - GET /api/health - System health check
 * </p>
 */
public class OcppRestApi implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OcppRestApi.class);

    private final OfflineStateManager stateManager;
    private final int port;
    private final Gson gson;
    private com.sun.net.httpserver.HttpServer server;

    public OcppRestApi(OfflineStateManager stateManager, int port) {
        this.stateManager = stateManager;
        this.port = port;
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    }

    /**
     * Start the REST API server.
     */
    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);

        // Register endpoints
        server.createContext("/api/chargers", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetChargers(exchange);
            } else {
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
            }
        });

        server.createContext("/api/transactions", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetTransactions(exchange);
            } else {
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
            }
        });

        server.createContext("/api/health", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleHealthCheck(exchange);
            } else {
                sendResponse(exchange, 405, createErrorResponse("Method not allowed"));
            }
        });

        server.setExecutor(null);  // Use default executor
        server.start();

        logger.info("OCPP REST API started on port {}", port);
    }

    /**
     * Stop the REST API server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("OCPP REST API stopped");
        }
    }

    /**
     * GET /api/chargers - List all chargers
     */
    private void handleGetChargers(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Collection<Charger> chargers = stateManager.getAllChargers();

        Map<String, Object> response = new HashMap<>();
        response.put("count", chargers.size());
        response.put("chargers", chargers);

        sendResponse(exchange, 200, gson.toJson(response));
    }

    /**
     * GET /api/transactions - List active transactions
     */
    private void handleGetTransactions(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Collection<Transaction> transactions = stateManager.getActiveTransactions();

        Map<String, Object> response = new HashMap<>();
        response.put("count", transactions.size());
        response.put("transactions", transactions);

        sendResponse(exchange, 200, gson.toJson(response));
    }

    /**
     * GET /api/health - System health check
     */
    private void handleHealthCheck(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("cloudConnected", stateManager.isCloudConnected());
        health.put("lastSyncTime", stateManager.getLastSyncTime().toString());
        health.put("activeChargers", stateManager.getAllChargers().size());
        health.put("activeTransactions", stateManager.getActiveTransactions().size());
        health.put("unsynchronizedEvents", stateManager.getUnsynchronizedEvents().size());

        sendResponse(exchange, 200, gson.toJson(health));
    }

    /**
     * Send HTTP response.
     */
    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String response)
        throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Create error response JSON.
     */
    private String createErrorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return gson.toJson(error);
    }

    @Override
    public void close() {
        stop();
    }
}
