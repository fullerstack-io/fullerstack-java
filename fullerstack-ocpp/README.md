# Fullerstack OCPP

Comprehensive OCPP (Open Charge Point Protocol) integration using the Substrates and Serventis frameworks for adaptive edge coordination.

## Overview

This module provides a complete OCPP 2.0+ Central System implementation that:

- ✅ Manages WebSocket connections to multiple EV chargers
- ✅ Translates OCPP messages into semantic Substrates signals
- ✅ Implements adaptive closed-loop control using signal-flow architecture
- ✅ Supports offline operation with automatic state synchronization
- ✅ Exposes REST API for external applications and dashboards
- ✅ Provides deterministic event processing with temporal ordering
- ✅ Implements operational safety with tiered confidence assessments

## Architecture

### Signal-Flow OODA Loop

The system follows a layered OODA (Observe-Orient-Decide-Act) loop architecture:

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 0: OCPP Protocol (WebSocket + JSON)                       │
│   - BootNotification, StatusNotification, Heartbeat             │
│   - MeterValues, StartTransaction, StopTransaction              │
└────────────────────────┬────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: OBSERVE - OcppMessageObserver                          │
│   Translates OCPP messages → Substrates signals                 │
│   Emits: Monitors, Counters, Gauges                             │
└────────────────────────┬────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 2: ORIENT - ChargerConnectionMonitor                      │
│   Tracks charger health and heartbeat status                    │
│   Emits: Monitor.Sign (STABLE, DEGRADED, DOWN, etc.)            │
└────────────────────────┬────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 3: DECIDE - ChargerHealthReporter                         │
│   Assesses urgency and situation awareness                      │
│   Emits: Reporter.Sign (NORMAL, WARNING, CRITICAL)              │
└────────────────────────┬────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 4: ACT - Semantic Command Actors                          │
│   - ChargerDisableActor (ChangeAvailability)                    │
│   - TransactionStopActor (RemoteStopTransaction)                │
│   Emits: Actor.Sign (DELIVER, DENY)                             │
└────────────────────────┬────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 5: Physical Actions (OCPP Commands to Chargers)           │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

#### Domain Models (`io.fullerstack.ocpp.model`)
- `Charger` - Immutable charger state representation
- `Transaction` - Charging session lifecycle
- `MeterValue` - Energy consumption metrics
- `ChargerStatus` - Operational status enum
- `TransactionStatus` - Session lifecycle enum

#### Server Infrastructure (`io.fullerstack.ocpp.server`)
- `OcppCentralSystem` - WebSocket server managing charger connections
- `OcppMessage` - Sealed interface for all OCPP messages
- `OcppCommand` - Sealed interface for semantic commands

#### Observers (`io.fullerstack.ocpp.observers`)
- `OcppMessageObserver` - Translates OCPP → Substrates signals

#### Monitors (`io.fullerstack.ocpp.monitors`)
- `ChargerConnectionMonitor` - Heartbeat monitoring and offline detection

#### Reporters (`io.fullerstack.ocpp.reporters`)
- `ChargerHealthReporter` - Urgency assessment (NORMAL/WARNING/CRITICAL)

#### Actors (`io.fullerstack.ocpp.actors`)
- `BaseActor` - Rate limiting, idempotency, speech acts
- `ChargerDisableActor` - Auto-disable critically unhealthy chargers
- `TransactionStopActor` - Remotely stop transactions on faults

#### Offline Operation (`io.fullerstack.ocpp.offline`)
- `OfflineStateManager` - Event sourcing for offline operation
- `OfflineEvent` - Immutable event log for synchronization

#### API (`io.fullerstack.ocpp.api`)
- `OcppRestApi` - HTTP REST API for external access

#### System Integration (`io.fullerstack.ocpp.system`)
- `OcppObservabilitySystem` - Complete wired system

## Usage

### Starting the System

```java
// Create and start the complete OCPP observability system
OcppObservabilitySystem system = new OcppObservabilitySystem(
    "my-charging-network",  // System name
    8080                     // OCPP WebSocket port
);

system.start();

// System is now running and accepting charger connections
```

### Simulating OCPP Messages (Testing)

```java
// Simulate a charger boot notification
OcppMessage.BootNotification boot = new OcppMessage.BootNotification(
    "charger-001",
    Instant.now(),
    "msg-001",
    "ChargePoint-Pro",
    "ACME Corporation",
    "SN-12345",
    "v2.0.1"
);

system.getCentralSystem().simulateIncomingMessage(boot);

// Simulate a status notification (charger becomes available)
OcppMessage.StatusNotification status = new OcppMessage.StatusNotification(
    "charger-001",
    Instant.now(),
    "msg-002",
    1,  // Connector 1
    "Available",
    "NoError"
);

system.getCentralSystem().simulateIncomingMessage(status);
```

### Accessing Signal Conduits

```java
// Subscribe to monitor signals
system.getMonitors().subscribe(cortex().subscriber(
    cortex().name("my-subscriber"),
    (subject, registrar) -> {
        registrar.register(sign -> {
            System.out.println("Monitor sign: " + sign);
        });
    }
));

// Subscribe to reporter signals (urgency assessments)
system.getReporters().subscribe(cortex().subscriber(
    cortex().name("urgency-subscriber"),
    (subject, registrar) -> {
        registrar.register(sign -> {
            if (sign == Reporters.Sign.CRITICAL) {
                System.out.println("CRITICAL health detected!");
            }
        });
    }
));
```

### Using the REST API

The system exposes a REST API on port 9090 (configurable):

```bash
# Get all chargers
curl http://localhost:9090/api/chargers

# Get active transactions
curl http://localhost:9090/api/transactions

# System health check
curl http://localhost:9090/api/health
```

## Signal Mappings

### OCPP Status → Monitor Signals

| OCPP Status | Monitor Sign | Description |
|-------------|--------------|-------------|
| Available, Charging | STABLE | Normal operation |
| Preparing, Finishing | CONVERGING | Transitioning to stable |
| SuspendedEV, SuspendedEVSE | ERRATIC | Unstable behavior |
| Unavailable | DEGRADED | Reduced capability |
| Faulted | DEFECTIVE | Functional problem |
| GroundFailure, HighTemperature | DOWN | Complete failure |

### Monitor Signs → Reporter Urgency

| Monitor Sign | Reporter Sign | Action Trigger |
|--------------|---------------|----------------|
| STABLE, CONVERGING | NORMAL | None |
| DEGRADED, ERRATIC, DIVERGING | WARNING | Monitoring only |
| DOWN, DEFECTIVE | CRITICAL | Actor intervention |

### Semantic Commands

| Reporter Sign | Actor | OCPP Command | Description |
|---------------|-------|--------------|-------------|
| CRITICAL | ChargerDisableActor | ChangeAvailability(Inoperative) | Disable charger |
| CRITICAL | TransactionStopActor | RemoteStopTransaction | Stop active session |

## Offline Operation

The system gracefully handles cloud connectivity loss:

1. **Event Logging**: All OCPP events are logged locally
2. **Local Coordination**: Adaptive control continues using Substrates
3. **State Caching**: Charger and transaction state maintained locally
4. **Synchronization**: Events pushed to cloud when connectivity resumes

```java
OfflineStateManager stateManager = new OfflineStateManager();

// Record events during offline operation
stateManager.recordChargerRegistration(charger);
stateManager.recordTransactionStart(transaction);

// Query local state
Collection<Charger> chargers = stateManager.getAllChargers();
Collection<Transaction> active = stateManager.getActiveTransactions();

// When connectivity resumes
List<OfflineEvent> unsyncedEvents = stateManager.getUnsynchronizedEvents();
// Push to cloud...
stateManager.markEventsSynchronized(Instant.now());
```

## Testing

### Unit Tests

```bash
mvn test -Dtest=OcppMessageObserverTest
```

### Integration Tests

```bash
# Complete OODA loop test
mvn test -Dtest=ChargerHealthOodaLoopIT

# Boot notification flow
mvn test -Dtest=BootNotificationIntegrationTest
```

### Example Test Scenario

The `ChargerHealthOodaLoopIT` demonstrates the complete adaptive response:

1. Charger reports `GroundFailure` fault
2. Observer emits `Monitor.DOWN` signal
3. Reporter assesses as `CRITICAL`
4. Actor executes `ChangeAvailability(Inoperative)`
5. Actor emits `DELIVER` speech act

## Dependencies

- **Substrates API** (`io.humainary.substrates:humainary-substrates-api`)
- **Serventis Extension** (`io.humainary.substrates.ext:humainary-substrates-ext-serventis`)
- **Fullerstack Substrates** (`io.fullerstack:fullerstack-substrates`)
- **WebSocket**: Java-WebSocket 1.5.3
- **JSON**: Gson 2.10.1
- **Testing**: JUnit 5, AssertJ, Mockito

## Building

```bash
# Build the module
mvn clean compile

# Run all tests
mvn test

# Create JAR
mvn package
```

## Production Deployment

For production use, integrate with a full OCPP library:

- [ChargeTimeEU/Java-OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP) - OCPP 2.0.1
- [steve-community/steve](https://github.com/steve-community/steve) - SteVe Central System

Replace the simplified `OcppCentralSystem` with a production WebSocket server
while keeping the Substrates integration layer intact.

## License

See parent project license.
