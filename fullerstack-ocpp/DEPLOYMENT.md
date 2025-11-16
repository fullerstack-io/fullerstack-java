# OCPP Production Deployment Guide

This guide explains how to deploy the fullerstack-ocpp module in production with real OCPP chargers.

## Architecture Overview

The system uses the **ChargeTimeEU Java-OCA-OCPP** library for OCPP 1.6 protocol implementation with WebSocket transport. All OCPP messages are translated into Substrates signals for adaptive coordination.

## Prerequisites

- **Java 25** with preview features enabled
- **Maven 3.8+** for building
- **OCPP 1.6 compliant chargers** or simulators
- **Network access** from chargers to Central System

## Building

```bash
# Build the entire project
mvn clean install

# Build only the OCPP module
mvn clean install -pl fullerstack-ocpp

# Run tests
mvn test -pl fullerstack-ocpp
```

## Running the Demo Application

### Quick Start

```bash
# Run the demo (from project root)
cd fullerstack-ocpp
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

### Expected Output

```
====================================================
  Fullerstack OCPP - Production Demo
====================================================

[Layer 0] Creating OCPP Central System (port 8080)
[Layer 1] Creating instrumentation circuits
[Layer 1-2] Creating observers
[Layer 3] Creating reporter circuit
[Layer 4] Creating actor circuit
[Offline] Creating state manager
[API] Creating REST API (port 9090)

====================================================
  OCPP Central System READY
====================================================

OCPP WebSocket: ws://localhost:8080/{chargerId}
REST API:       http://localhost:9090/api/health

Signal Flow Architecture:
  Layer 0: OCPP Protocol (WebSocket)
  Layer 1: OBSERVE (Message → Signals)
  Layer 2: ORIENT (Health Monitoring)
  Layer 3: DECIDE (Urgency Assessment)
  Layer 4: ACT (Adaptive Commands)

Adaptive Responses:
  - CRITICAL health → Auto-disable charger
  - CRITICAL connector → Remote stop transaction
```

## Connecting Real Chargers

### OCPP Configuration

Configure your charger to connect to the Central System:

**WebSocket URL Format:**
```
ws://<central-system-ip>:8080/<charger-id>
```

**Example:**
```
ws://192.168.1.100:8080/CHARGER-001
```

### Charger Settings

- **Protocol**: OCPP 1.6J (JSON over WebSocket)
- **Port**: 8080 (configurable)
- **Heartbeat Interval**: 300 seconds (set by Central System)
- **Authentication**: None (add security in production)

### Supported OCPP Messages

#### From Charger to Central System

| Message | Support | Description |
|---------|---------|-------------|
| BootNotification | ✅ Full | Charger registration |
| StatusNotification | ✅ Full | Status changes |
| Heartbeat | ✅ Full | Keep-alive |
| MeterValues | ✅ Full | Energy readings |
| StartTransaction | ✅ Full | Session start |
| StopTransaction | ✅ Full | Session stop |
| Authorize | ✅ Full | User authorization |
| DataTransfer | ✅ Full | Vendor data |
| DiagnosticsStatusNotification | ⚠️ Partial | Logs only |
| FirmwareStatusNotification | ⚠️ Partial | Logs only |

#### From Central System to Charger

| Message | Support | Description |
|---------|---------|-------------|
| ChangeAvailability | ✅ Full | Enable/disable connector |
| RemoteStartTransaction | ✅ Full | Start charging remotely |
| RemoteStopTransaction | ✅ Full | Stop charging remotely |
| Reset | ✅ Full | Reboot charger |
| UnlockConnector | ✅ Full | Emergency unlock |
| UpdateFirmware | ✅ Full | Firmware update |
| GetDiagnostics | ✅ Full | Request logs |
| ChangeConfiguration | ❌ Not yet | Update settings |
| GetConfiguration | ❌ Not yet | Query settings |

## Testing with OCPP Simulator

### Using OCPP-J Test Client

Download test client:
```bash
git clone https://github.com/ChargeTimeEU/Java-OCA-OCPP.git
cd Java-OCA-OCPP/ocpp-v1_6-example-client
mvn exec:java
```

Configure client to connect to `ws://localhost:8080/TEST-CHARGER-01`

### Simulating Fault Scenarios

The system will automatically respond to these scenarios:

#### Scenario 1: Charger Fault

1. Send StatusNotification with `Faulted` status and `GroundFailure` error
2. **Expected Response**:
   - Monitor emits `DOWN` signal
   - Reporter assesses as `CRITICAL`
   - ChargerDisableActor sends `ChangeAvailability(Inoperative)`
   - Actor emits `DELIVER` speech act

#### Scenario 2: Heartbeat Timeout

1. Stop sending Heartbeat messages for >5 minutes
2. **Expected Response**:
   - ChargerConnectionMonitor emits `DOWN` signal
   - Reporter assesses as `CRITICAL`
   - ChargerDisableActor auto-disables

#### Scenario 3: Emergency Stop

1. Send StopTransaction with `reason=EmergencyStop`
2. **Expected Response**:
   - Monitor emits `ERRATIC` signal
   - Reporter assesses as `WARNING`
   - No actor intervention (WARNING level only)

## REST API Usage

### Health Check

```bash
curl http://localhost:9090/api/health
```

**Response:**
```json
{
  "status": "healthy",
  "cloudConnected": true,
  "lastSyncTime": "2025-11-16T01:30:00Z",
  "activeChargers": 3,
  "activeTransactions": 1,
  "unsynchronizedEvents": 0
}
```

### List Chargers

```bash
curl http://localhost:9090/api/chargers
```

**Response:**
```json
{
  "count": 3,
  "chargers": [
    {
      "chargerId": "CHARGER-001",
      "model": "ChargePoint-Pro",
      "vendor": "ACME",
      "status": "AVAILABLE",
      "isOnline": true,
      "lastHeartbeat": "2025-11-16T01:29:45Z"
    }
  ]
}
```

### List Active Transactions

```bash
curl http://localhost:9090/api/transactions
```

**Response:**
```json
{
  "count": 1,
  "transactions": [
    {
      "transactionId": "123456",
      "chargerId": "CHARGER-001",
      "connectorId": 1,
      "idTag": "USER-001",
      "status": "IN_PROGRESS",
      "meterStartKwh": 0.0,
      "energyConsumedKwh": 15.5
    }
  ]
}
```

## Production Configuration

### Security

**⚠️ Important: The demo has no security. For production:**

1. **Add TLS/SSL:**
```java
// Use wss:// instead of ws://
SSLContext sslContext = SSLContext.getInstance("TLS");
// Configure certificates
```

2. **Add Authentication:**
   - HTTP Basic Auth
   - API Keys
   - OAuth 2.0

3. **Add Authorization:**
   - Validate charger IDs
   - Check allowed users (idTags)
   - Implement access control

### Network Configuration

**Firewall Rules:**
```bash
# Allow OCPP WebSocket traffic
sudo ufw allow 8080/tcp

# Allow REST API traffic (internal only)
sudo ufw allow from 192.168.1.0/24 to any port 9090
```

**Port Configuration:**

Change ports in code:
```java
// OCPP port (constructor parameter)
RealOcppCentralSystem centralSystem = new RealOcppCentralSystem(8080);

// REST API port (constructor parameter)
OcppRestApi restApi = new OcppRestApi(stateManager, 9090);
```

### Performance Tuning

**Heap Size:**
```bash
java -Xms2G -Xmx4G -jar fullerstack-ocpp.jar
```

**Connection Limits:**

The server can handle:
- **100+ concurrent chargers** (tested)
- **1000+ messages/second** (estimated)

For higher loads, consider:
- Increase thread pool sizes
- Use connection pooling
- Enable WebSocket compression

### Logging Configuration

Add `logback.xml`:

```xml
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/ocpp-central-system.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/ocpp-%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <logger name="io.fullerstack.ocpp" level="INFO"/>
  <logger name="eu.chargetime.ocpp" level="DEBUG"/>

  <root level="INFO">
    <appender-ref ref="FILE"/>
  </root>
</configuration>
```

## Monitoring and Observability

### Signal Metrics

Subscribe to circuits for metrics export:

```java
// Export monitor signals to Prometheus
monitors.subscribe(cortex().subscriber(
    cortex().name("prometheus-exporter"),
    (subject, registrar) -> {
        registrar.register(sign -> {
            prometheusRegistry.counter(
                "ocpp_monitor_signals_total",
                "charger", subject.name().path(),
                "sign", sign.name()
            ).inc();
        });
    }
));
```

### Health Checks

The REST API `/api/health` endpoint provides:
- Cloud connectivity status
- Active charger count
- Active transaction count
- Unsynchronized event count
- Last sync timestamp

### Alerting

Subscribe to CRITICAL reporter signals for alerts:

```java
reporters.subscribe(cortex().subscriber(
    cortex().name("alerting"),
    (subject, registrar) -> {
        registrar.register(sign -> {
            if (sign == Reporters.Sign.CRITICAL) {
                // Send alert to PagerDuty, Slack, etc.
                alertService.sendAlert(
                    "Charger " + subject.name() + " is CRITICAL"
                );
            }
        });
    }
));
```

## Offline Operation

The system supports offline mode with event sourcing:

### Event Log

All OCPP events are logged locally:
```java
List<OfflineEvent> events = stateManager.getUnsynchronizedEvents();
// Events include: ChargerRegistered, TransactionStarted, etc.
```

### State Synchronization

When cloud connectivity resumes:

```java
// Get unsynchronized events
List<OfflineEvent> events = stateManager.getUnsynchronizedEvents();

// Push to cloud
cloudSync.uploadEvents(events);

// Mark as synchronized
stateManager.markEventsSynchronized(Instant.now());
```

### Local Coordination

During offline operation:
- Adaptive coordination continues using Substrates
- Chargers can start/stop transactions
- Auto-disable on faults still works
- REST API provides local state

## Troubleshooting

### Charger Won't Connect

**Check:**
1. Network connectivity: `ping <central-system-ip>`
2. Port is open: `telnet <central-system-ip> 8080`
3. WebSocket URL is correct: `ws://ip:port/{chargerId}`
4. Server is running: Check logs for "OCPP Central System READY"

**Logs to check:**
```
[Layer 0] Creating OCPP Central System (port 8080)
New session: charger {id} connected (session: {uuid})
```

### No Signals Being Emitted

**Check:**
1. Message observer is registered: Look for "Registered message handler" log
2. Circuits are created: Look for "[Layer X] Creating" logs
3. Wait for signal processing: Circuits are asynchronous

**Debug:**
```java
// Add signal logging
monitors.subscribe(cortex().subscriber(
    cortex().name("debug"),
    (subject, registrar) -> {
        registrar.register(sign -> {
            System.out.println("SIGNAL: " + subject.name() + " -> " + sign);
        });
    }
));
```

### Actor Not Responding

**Check:**
1. Reporter is emitting CRITICAL: Look for "REPORTER [...] → CRITICAL" log
2. Rate limiting: Actor waits 5 minutes between actions
3. Command execution: Check for "ChangeAvailability response" logs

**Force action:**
```java
// Manually trigger for testing
reporters.percept(cortex().name("test-charger.health")).critical();
reporterCircuit.await();
```

## Production Deployment Checklist

- [ ] Build with `mvn clean install`
- [ ] Add TLS/SSL certificates
- [ ] Configure authentication
- [ ] Set up firewall rules
- [ ] Configure logging (logback.xml)
- [ ] Set heap size appropriately
- [ ] Test with OCPP simulator
- [ ] Set up monitoring/alerting
- [ ] Configure cloud synchronization
- [ ] Test offline operation
- [ ] Document charger configuration
- [ ] Create runbook for operators
- [ ] Set up automated backups
- [ ] Test disaster recovery

## Support

For issues or questions:
- Check logs in `logs/ocpp-central-system.log`
- Review OCPP 1.6 specification
- See ChargeTimeEU library docs: https://github.com/ChargeTimeEU/Java-OCA-OCPP
- File issues: https://github.com/fullerstack-io/fullerstack-java/issues
