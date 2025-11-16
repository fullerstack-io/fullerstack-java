# OCPP-Substrates Integration Architecture

## Design Principles

### 1. Signal-Flow Architecture

The system implements a deterministic signal-flow architecture with five layers:

- **Layer 0 (Protocol)**: Raw OCPP messages over WebSocket
- **Layer 1 (Observe)**: Message-to-signal translation
- **Layer 2 (Orient)**: Situation awareness and health tracking
- **Layer 3 (Decide)**: Urgency assessment and tiered confidence
- **Layer 4 (Act)**: Semantic command execution
- **Layer 5 (Physical)**: OCPP commands to chargers

### 2. Semantic Abstraction

OCPP protocol messages are abstracted into domain-agnostic semantic signals:

- **Monitors**: Operational status (STABLE, DEGRADED, DOWN, etc.)
- **Counters**: Event counting (transactions started/stopped)
- **Gauges**: Continuous measurements (power, energy, SoC)
- **Reporters**: Urgency levels (NORMAL, WARNING, CRITICAL)
- **Actors**: Speech acts (DELIVER, DENY)

### 3. Immutable Domain Models

All domain models are immutable records:

```java
public record Charger(
    String chargerId,
    String model,
    ChargerStatus status,
    Instant lastHeartbeat,
    boolean isOnline
) {
    public Charger withStatus(ChargerStatus newStatus) {
        return new Charger(chargerId, model, newStatus, lastHeartbeat, isOnline);
    }
}
```

This ensures:
- Thread safety
- Temporal reasoning
- Event sourcing compatibility

### 4. Closed-Loop Adaptive Control

The system implements closed-loop control:

```
Physical World (Chargers)
         ↓
   OCPP Messages
         ↓
   Observe (Signals)
         ↓
   Orient (Health)
         ↓
   Decide (Urgency)
         ↓
   Act (Commands)
         ↓
   OCPP Commands
         ↓
Physical World (Chargers)
```

### 5. Event Sourcing for Offline Operation

All state changes are logged as immutable events:

```java
sealed interface OfflineEvent {
    record ChargerRegistered(...) implements OfflineEvent {}
    record TransactionStarted(...) implements OfflineEvent {}
    record TransactionStopped(...) implements OfflineEvent {}
}
```

Events can be:
- Replayed for state reconstruction
- Synchronized with cloud backend
- Audited for compliance

## Component Responsibilities

### OcppMessageObserver

**Layer**: 1 (Observe)

**Input**: OCPP messages from chargers

**Output**: Substrates signals (Monitors, Counters, Gauges)

**Responsibilities**:
- Parse OCPP message semantics
- Map to appropriate signal types
- Emit signals through conduits

**Example**:
```java
// BootNotification → STABLE Monitor signal
private void handleBootNotification(BootNotification boot) {
    Monitors.Monitor chargerMonitor = monitors.percept(
        cortex().name(boot.chargerId())
    );
    chargerMonitor.stable(Monitors.Dimension.CONFIRMED);
}
```

### ChargerConnectionMonitor

**Layer**: 2 (Orient)

**Input**: Heartbeat timestamps

**Output**: DOWN signals for offline chargers

**Responsibilities**:
- Track heartbeat times for all chargers
- Periodically check for timeouts
- Emit DOWN signals for offline chargers

**Pattern**: Similar to JvmMetricsObserver - scheduled monitoring

### ChargerHealthReporter

**Layer**: 3 (Decide)

**Input**: Monitor signals

**Output**: Reporter signals (urgency assessment)

**Responsibilities**:
- Subscribe to charger monitor signals
- Assess urgency (NORMAL/WARNING/CRITICAL)
- Emit reporter signals

**Mapping**:
```java
private Reporters.Sign assessUrgency(Monitors.Sign sign) {
    return switch (sign) {
        case DOWN, DEFECTIVE -> Reporters.Sign.CRITICAL;
        case DEGRADED, ERRATIC, DIVERGING -> Reporters.Sign.WARNING;
        case STABLE, CONVERGING -> Reporters.Sign.NORMAL;
    };
}
```

### ChargerDisableActor

**Layer**: 4 (Act)

**Input**: CRITICAL reporter signals

**Output**: ChangeAvailability commands + DELIVER/DENY speech acts

**Responsibilities**:
- Subscribe to charger health reporters
- Filter for CRITICAL signals
- Execute ChangeAvailability(Inoperative) command
- Emit DELIVER on success, DENY on failure

**Protection Mechanisms**:
- Rate limiting (5 minute interval)
- Idempotency (prevent duplicate actions)
- Speech acts (observability of actor decisions)

### TransactionStopActor

**Layer**: 4 (Act)

**Input**: CRITICAL connector health signals

**Output**: RemoteStopTransaction commands + DELIVER/DENY speech acts

**Responsibilities**:
- Subscribe to connector health reporters
- Track active transactions
- Execute RemoteStopTransaction on critical faults
- Emit speech acts for observability

**Use Case**: Safely terminate charging when connector faults detected

### OfflineStateManager

**Layer**: Cross-cutting (State Management)

**Responsibilities**:
- Cache charger and transaction state locally
- Log all events for synchronization
- Provide state queries for API
- Support state reconciliation on reconnect

**Event Log**:
```java
private final List<OfflineEvent> eventLog;

private void logEvent(OfflineEvent event) {
    synchronized (eventLogLock) {
        eventLog.add(event);
    }
}
```

### OcppRestApi

**Layer**: External Interface

**Endpoints**:
- `GET /api/chargers` - List all chargers
- `GET /api/transactions` - Active transactions
- `GET /api/health` - System health check

**Data Source**: OfflineStateManager

### OcppObservabilitySystem

**Layer**: System Integration

**Responsibilities**:
- Create all circuits and conduits
- Wire observers, reporters, actors together
- Provide lifecycle management (start/stop)
- Expose conduits for testing

**Wiring Pattern**:
```java
// Layer 1: Instrumentation
this.monitorCircuit = cortex().circuit(cortex().name(systemName + "-monitors"));
this.monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

// Layer 1-2: Observers
this.messageObserver = new OcppMessageObserver(monitors, counters, gauges);
centralSystem.registerMessageHandler(messageObserver);

// Layer 3: Reporters
this.reporterCircuit = cortex().circuit(cortex().name(systemName + "-reporters"));
this.reporters = reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);
this.chargerHealthReporter = new ChargerHealthReporter(monitors, reporters);

// Layer 4: Actors
this.actorCircuit = cortex().circuit(cortex().name(systemName + "-actors"));
this.actors = actorCircuit.conduit(cortex().name("actors"), Actors::composer);
this.chargerDisableActor = new ChargerDisableActor(reporters, actors, commandExecutor);
```

## Concurrency and Threading

### Circuit Thread Model

Each circuit processes signals on its own thread:

- **Monitor Circuit**: Processes all monitor signal emissions
- **Reporter Circuit**: Processes all reporter assessments
- **Actor Circuit**: Processes all actor executions

### Deterministic Ordering

Substrates guarantees:
- Temporal ordering within circuits
- Happens-before relationships across layers
- No signal loss or reordering

### Thread Safety

- Immutable domain models (thread-safe by design)
- ConcurrentHashMap for mutable caches
- Synchronized blocks for event log
- Atomic operations for timestamps

## Error Handling

### Observer Level

```java
try {
    handler.handleMessage(message);
} catch (Exception e) {
    logger.error("Error in message handler: {}", e.getMessage(), e);
    // Continue processing other messages
}
```

### Actor Level

```java
try {
    action.run();
    emitDeliver(actionKey);
} catch (Exception e) {
    logger.error("Actor action failed: {}", e.getMessage(), e);
    emitDeny(actionKey, "failed: " + e.getMessage());
}
```

### System Level

- Actors emit DENY speech acts on failure
- Reporters continue assessing despite individual failures
- System remains operational even if actors fail

## Testing Strategy

### Unit Tests

Test individual components in isolation:

```java
@Test
void testBootNotificationEmitsStable() {
    observer.handleMessage(bootMessage);
    monitorCircuit.await();

    assertThat(monitorSigns).contains(Monitors.Sign.STABLE);
}
```

### Integration Tests

Test complete OODA loops:

```java
@Test
void testCompleteOodaLoop() {
    // Send fault status
    system.getCentralSystem().simulateIncomingMessage(faultStatus);
    system.awaitSignalProcessing();

    // Verify complete flow
    assertThat(monitorSigns).contains(Monitors.Sign.DOWN);
    assertThat(reporterSigns).contains(Reporters.Sign.CRITICAL);
    assertThat(actorSigns).contains(Actors.Sign.DELIVER);
}
```

## Performance Considerations

### Signal Backpressure

Substrates handles backpressure automatically through circuits:

- Bounded queues prevent unbounded memory growth
- Circuit.await() synchronizes for testing
- Non-blocking signal emission in production

### Heartbeat Monitoring

- Single scheduled executor for all chargers
- O(n) complexity for timeout checks
- Runs every 60 seconds (configurable)

### Event Log Size

- Max 10,000 events (configurable)
- Oldest 10% removed when limit reached
- Events cleared after successful sync

## Future Enhancements

### Production OCPP Integration

Replace simplified OcppCentralSystem with:
- ChargeTimeEU OCPP-J library
- WebSocket session management
- OCPP JSON serialization/deserialization
- Message validation and error handling

### Cloud Synchronization

Implement actual backend sync:
- Batch event upload on reconnect
- Conflict resolution for state divergence
- Policy download from cloud

### Advanced Coordination

- Load balancing across chargers
- Dynamic pricing based on grid demand
- Reservation management
- Smart charging profiles

### Monitoring and Observability

- Metrics export (Prometheus)
- Distributed tracing (OpenTelemetry)
- Dashboard integration (Grafana)
