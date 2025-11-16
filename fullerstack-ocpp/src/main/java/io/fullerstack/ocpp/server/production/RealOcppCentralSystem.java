package io.fullerstack.ocpp.server.production;

import eu.chargetime.ocpp.JSONServer;
import eu.chargetime.ocpp.ServerEvents;
import eu.chargetime.ocpp.feature.profile.ServerCoreEventHandler;
import eu.chargetime.ocpp.feature.profile.ServerCoreProfile;
import eu.chargetime.ocpp.model.core.*;
import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.fullerstack.ocpp.server.OcppMessage;
import io.fullerstack.ocpp.server.OcppMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Production OCPP Central System implementation using ChargeTimeEU library.
 * <p>
 * This implementation:
 * - Uses JSONServer with WebSocket transport
 * - Handles OCPP 1.6 protocol messages
 * - Translates OCPP events to our domain model
 * - Forwards to registered message handlers (Substrates observers)
 * - Supports command execution to chargers
 * </p>
 */
public class RealOcppCentralSystem implements OcppCommandExecutor, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RealOcppCentralSystem.class);

    private final JSONServer server;
    private final ServerCoreProfile coreProfile;
    private final Map<String, UUID> chargerSessions;  // chargerId -> sessionId
    private final List<OcppMessageHandler> messageHandlers;
    private volatile boolean running;

    public RealOcppCentralSystem(int port) {
        this.chargerSessions = new ConcurrentHashMap<>();
        this.messageHandlers = new CopyOnWriteArrayList<>();

        // Create core profile with event handler
        this.coreProfile = new ServerCoreProfile(new CoreEventHandler());

        // Create JSON server
        this.server = new JSONServer(coreProfile);

        // Set server event handler
        this.server.open("localhost", port, new ServerEventHandler());

        this.running = false;
        logger.info("RealOcppCentralSystem created on port {}", port);
    }

    /**
     * Register a message handler to receive OCPP events.
     */
    public void registerMessageHandler(OcppMessageHandler handler) {
        messageHandlers.add(handler);
        logger.info("Registered message handler: {}", handler.getClass().getSimpleName());
    }

    /**
     * Start the server.
     */
    public void start() {
        if (running) {
            logger.warn("Server already running");
            return;
        }

        running = true;
        logger.info("OCPP Central System started");
    }

    /**
     * Stop the server.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        server.close();
        logger.info("OCPP Central System stopped");
    }

    /**
     * Execute a command by sending it to a charger.
     */
    @Override
    public boolean executeCommand(OcppCommand command) {
        UUID sessionId = chargerSessions.get(command.chargerId());
        if (sessionId == null) {
            logger.warn("Cannot execute command: charger {} not connected", command.chargerId());
            return false;
        }

        try {
            switch (command) {
                case OcppCommand.ChangeAvailability changeAvail -> {
                    return sendChangeAvailability(sessionId, changeAvail);
                }
                case OcppCommand.RemoteStopTransaction remoteStop -> {
                    return sendRemoteStopTransaction(sessionId, remoteStop);
                }
                case OcppCommand.RemoteStartTransaction remoteStart -> {
                    return sendRemoteStartTransaction(sessionId, remoteStart);
                }
                case OcppCommand.Reset reset -> {
                    return sendReset(sessionId, reset);
                }
                case OcppCommand.UnlockConnector unlock -> {
                    return sendUnlockConnector(sessionId, unlock);
                }
                case OcppCommand.UpdateFirmware updateFirmware -> {
                    return sendUpdateFirmware(sessionId, updateFirmware);
                }
                case OcppCommand.GetDiagnostics getDiagnostics -> {
                    return sendGetDiagnostics(sessionId, getDiagnostics);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to execute command {}: {}", command.getClass().getSimpleName(),
                e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send ChangeAvailability request to charger.
     */
    private boolean sendChangeAvailability(UUID sessionId, OcppCommand.ChangeAvailability command) {
        ChangeAvailabilityRequest request = new ChangeAvailabilityRequest();
        request.setConnectorId(command.connectorId());
        request.setType(AvailabilityType.fromValue(command.type()));

        try {
            ChangeAvailabilityConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("ChangeAvailability response: {}", confirmation.getStatus());
            return confirmation.getStatus() == AvailabilityStatus.Accepted;
        } catch (Exception e) {
            logger.error("ChangeAvailability failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send RemoteStopTransaction request to charger.
     */
    private boolean sendRemoteStopTransaction(UUID sessionId, OcppCommand.RemoteStopTransaction command) {
        RemoteStopTransactionRequest request = new RemoteStopTransactionRequest();
        request.setTransactionId(Integer.parseInt(command.transactionId()));

        try {
            RemoteStopTransactionConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("RemoteStopTransaction response: {}", confirmation.getStatus());
            return confirmation.getStatus() == RemoteStartStopStatus.Accepted;
        } catch (Exception e) {
            logger.error("RemoteStopTransaction failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send RemoteStartTransaction request to charger.
     */
    private boolean sendRemoteStartTransaction(UUID sessionId, OcppCommand.RemoteStartTransaction command) {
        RemoteStartTransactionRequest request = new RemoteStartTransactionRequest();
        request.setIdTag(command.idTag());
        request.setConnectorId(command.connectorId());

        try {
            RemoteStartTransactionConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("RemoteStartTransaction response: {}", confirmation.getStatus());
            return confirmation.getStatus() == RemoteStartStopStatus.Accepted;
        } catch (Exception e) {
            logger.error("RemoteStartTransaction failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send Reset request to charger.
     */
    private boolean sendReset(UUID sessionId, OcppCommand.Reset command) {
        ResetRequest request = new ResetRequest();
        request.setType(ResetType.fromValue(command.type()));

        try {
            ResetConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("Reset response: {}", confirmation.getStatus());
            return confirmation.getStatus() == ResetStatus.Accepted;
        } catch (Exception e) {
            logger.error("Reset failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send UnlockConnector request to charger.
     */
    private boolean sendUnlockConnector(UUID sessionId, OcppCommand.UnlockConnector command) {
        UnlockConnectorRequest request = new UnlockConnectorRequest();
        request.setConnectorId(command.connectorId());

        try {
            UnlockConnectorConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("UnlockConnector response: {}", confirmation.getStatus());
            return confirmation.getStatus() == UnlockStatus.Unlocked;
        } catch (Exception e) {
            logger.error("UnlockConnector failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send UpdateFirmware request to charger.
     */
    private boolean sendUpdateFirmware(UUID sessionId, OcppCommand.UpdateFirmware command) {
        UpdateFirmwareRequest request = new UpdateFirmwareRequest();
        request.setLocation(command.firmwareUrl());
        request.setRetrieveDate(ZonedDateTime.now().plusMinutes(5));  // Schedule for 5 minutes from now

        try {
            UpdateFirmwareConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("UpdateFirmware accepted");
            return true;
        } catch (Exception e) {
            logger.error("UpdateFirmware failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send GetDiagnostics request to charger.
     */
    private boolean sendGetDiagnostics(UUID sessionId, OcppCommand.GetDiagnostics command) {
        GetDiagnosticsRequest request = new GetDiagnosticsRequest();
        request.setLocation(command.uploadUrl());

        try {
            GetDiagnosticsConfirmation confirmation = server.send(sessionId, request)
                .toCompletableFuture()
                .get();

            logger.info("GetDiagnostics accepted, filename: {}", confirmation.getFileName());
            return confirmation.getFileName() != null;
        } catch (Exception e) {
            logger.error("GetDiagnostics failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Notify all message handlers of an OCPP message.
     */
    private void notifyHandlers(OcppMessage message) {
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
     * Check if a charger is connected.
     */
    public boolean isChargerConnected(String chargerId) {
        return chargerSessions.containsKey(chargerId);
    }

    /**
     * Get the number of connected chargers.
     */
    public int getConnectedChargerCount() {
        return chargerSessions.size();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Server event handler for connection/disconnection events.
     */
    private class ServerEventHandler implements ServerEvents {
        @Override
        public void newSession(UUID sessionId, SessionInformation information) {
            String chargerId = information.getIdentifier();
            chargerSessions.put(chargerId, sessionId);
            logger.info("New session: charger {} connected (session: {})", chargerId, sessionId);
        }

        @Override
        public void lostSession(UUID sessionId) {
            // Find and remove charger
            String chargerId = chargerSessions.entrySet().stream()
                .filter(entry -> entry.getValue().equals(sessionId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown");

            chargerSessions.remove(chargerId);
            logger.info("Lost session: charger {} disconnected (session: {})", chargerId, sessionId);

            // TODO: Emit signal for charger offline
        }
    }

    /**
     * Core event handler for OCPP messages.
     */
    private class CoreEventHandler implements ServerCoreEventHandler {

        @Override
        public BootNotificationConfirmation handleBootNotificationRequest(
            UUID sessionId,
            BootNotificationRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.info("BootNotification from {}: {} {} (firmware: {})",
                chargerId, request.getChargePointVendor(), request.getChargePointModel(),
                request.getFirmwareVersion());

            // Translate to our domain model
            OcppMessage.BootNotification bootMessage = new OcppMessage.BootNotification(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString(),
                request.getChargePointModel(),
                request.getChargePointVendor(),
                request.getChargePointSerialNumber(),
                request.getFirmwareVersion()
            );

            notifyHandlers(bootMessage);

            // Accept the boot notification
            BootNotificationConfirmation confirmation = new BootNotificationConfirmation();
            confirmation.setCurrentTime(ZonedDateTime.now());
            confirmation.setInterval(300);  // Heartbeat interval: 300 seconds
            confirmation.setStatus(RegistrationStatus.Accepted);
            return confirmation;
        }

        @Override
        public StatusNotificationConfirmation handleStatusNotificationRequest(
            UUID sessionId,
            StatusNotificationRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.debug("StatusNotification from {}: connector {} = {}, error: {}",
                chargerId, request.getConnectorId(), request.getStatus(), request.getErrorCode());

            // Translate to our domain model
            OcppMessage.StatusNotification statusMessage = new OcppMessage.StatusNotification(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString(),
                request.getConnectorId(),
                request.getStatus().toString(),
                request.getErrorCode().toString()
            );

            notifyHandlers(statusMessage);

            return new StatusNotificationConfirmation();
        }

        @Override
        public HeartbeatConfirmation handleHeartbeatRequest(UUID sessionId, HeartbeatRequest request) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.trace("Heartbeat from {}", chargerId);

            // Translate to our domain model
            OcppMessage.Heartbeat heartbeatMessage = new OcppMessage.Heartbeat(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString()
            );

            notifyHandlers(heartbeatMessage);

            HeartbeatConfirmation confirmation = new HeartbeatConfirmation();
            confirmation.setCurrentTime(ZonedDateTime.now());
            return confirmation;
        }

        @Override
        public MeterValuesConfirmation handleMeterValuesRequest(
            UUID sessionId,
            MeterValuesRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.trace("MeterValues from {}: connector {}", chargerId, request.getConnectorId());

            // Extract meter values (simplified - get first sampled value from first meter value)
            Map<String, Double> values = new ConcurrentHashMap<>();
            if (request.getMeterValue() != null && request.getMeterValue().length > 0) {
                MeterValue meterValue = request.getMeterValue()[0];
                if (meterValue.getSampledValue() != null && meterValue.getSampledValue().length > 0) {
                    for (SampledValue sampledValue : meterValue.getSampledValue()) {
                        String key = sampledValue.getMeasurand() != null ?
                            sampledValue.getMeasurand().toString() : "Energy.Active.Import.Register";
                        values.put(key, Double.parseDouble(sampledValue.getValue()));
                    }
                }
            }

            // Translate to our domain model
            String transactionId = request.getTransactionId() != null ?
                String.valueOf(request.getTransactionId()) : null;

            OcppMessage.MeterValues meterMessage = new OcppMessage.MeterValues(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString(),
                request.getConnectorId(),
                transactionId,
                values
            );

            notifyHandlers(meterMessage);

            return new MeterValuesConfirmation();
        }

        @Override
        public StartTransactionConfirmation handleStartTransactionRequest(
            UUID sessionId,
            StartTransactionRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            int transactionId = generateTransactionId();
            logger.info("StartTransaction from {}: connector {}, user {}, transaction {}",
                chargerId, request.getConnectorId(), request.getIdTag(), transactionId);

            // Translate to our domain model
            OcppMessage.StartTransaction startMessage = new OcppMessage.StartTransaction(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString(),
                request.getConnectorId(),
                request.getIdTag(),
                Double.parseDouble(request.getMeterStart())
            );

            notifyHandlers(startMessage);

            StartTransactionConfirmation confirmation = new StartTransactionConfirmation();
            confirmation.setTransactionId(transactionId);
            confirmation.setIdTagInfo(new IdTagInfo(AuthorizationStatus.Accepted));
            return confirmation;
        }

        @Override
        public StopTransactionConfirmation handleStopTransactionRequest(
            UUID sessionId,
            StopTransactionRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.info("StopTransaction from {}: transaction {}, reason {}",
                chargerId, request.getTransactionId(), request.getReason());

            // Translate to our domain model
            String reason = request.getReason() != null ? request.getReason().toString() : "Local";
            OcppMessage.StopTransaction stopMessage = new OcppMessage.StopTransaction(
                chargerId,
                java.time.Instant.now(),
                UUID.randomUUID().toString(),
                String.valueOf(request.getTransactionId()),
                request.getIdTag(),
                Double.parseDouble(request.getMeterStop()),
                reason
            );

            notifyHandlers(stopMessage);

            StopTransactionConfirmation confirmation = new StopTransactionConfirmation();
            confirmation.setIdTagInfo(new IdTagInfo(AuthorizationStatus.Accepted));
            return confirmation;
        }

        @Override
        public AuthorizeConfirmation handleAuthorizeRequest(UUID sessionId, AuthorizeRequest request) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.info("Authorize from {}: {}", chargerId, request.getIdTag());

            // Accept all authorizations
            AuthorizeConfirmation confirmation = new AuthorizeConfirmation();
            confirmation.setIdTagInfo(new IdTagInfo(AuthorizationStatus.Accepted));
            return confirmation;
        }

        @Override
        public DataTransferConfirmation handleDataTransferRequest(
            UUID sessionId,
            DataTransferRequest request
        ) {
            String chargerId = findChargerIdBySession(sessionId);
            logger.info("DataTransfer from {}: vendor {}, message {}",
                chargerId, request.getVendorId(), request.getMessageId());

            DataTransferConfirmation confirmation = new DataTransferConfirmation();
            confirmation.setStatus(DataTransferStatus.Accepted);
            return confirmation;
        }

        /**
         * Find charger ID by session ID.
         */
        private String findChargerIdBySession(UUID sessionId) {
            return chargerSessions.entrySet().stream()
                .filter(entry -> entry.getValue().equals(sessionId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown-" + sessionId);
        }

        /**
         * Generate a unique transaction ID.
         */
        private int generateTransactionId() {
            return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        }
    }
}
