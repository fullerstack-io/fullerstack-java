# OCPP Testing Guide

This guide shows how to test the fullerstack-ocpp adaptive system using the **Solidstudio Virtual Charge Point (VCP)** simulator.

## Overview

We use [Solidstudio Virtual Charge Point](https://github.com/solidstudiosh/ocpp-virtual-charge-point) - an open-source OCPP 1.6 charge point simulator that enables:
- ✅ Integration testing without real hardware
- ✅ Multi-charger scenarios (load balancing)
- ✅ Failure simulation (errors, faults)
- ✅ Automated CI/CD testing
- ✅ Command verification (closed-loop feedback)

## Prerequisites

**System Requirements:**
- Node.js 12+ (for VCP simulator)
- Java 21+ (for our OCPP Central System)
- Git

**Install VCP Simulator:**
```bash
# Clone the simulator
git clone https://github.com/solidstudiosh/ocpp-virtual-charge-point.git
cd ocpp-virtual-charge-point

# Install dependencies
npm install
```

## Quick Start - Basic Test

**Step 1: Start the OCPP Central System**

```bash
# From fullerstack-java root
cd fullerstack-ocpp
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

This starts:
- OCPP Central System WebSocket server on `ws://localhost:8080`
- REST API on `http://localhost:9090/api/health`
- All adaptive agents (ChargerDisableAgent, TransactionStopAgent, LoadBalancingAgent)

**Step 2: Start a Simulated Charger**

```bash
# From ocpp-virtual-charge-point directory
export OCPP_ENDPOINT="ws://localhost:8080/CP001"
export CHARGE_POINT_ID="CP001"

npx tsx index_16.ts
```

**Step 3: Observe the Signal Flow**

You should see in the Central System logs:
```
🟢 MONITOR [CP001.connection] → UP
🟢 MONITOR [CP001.boot] → STABLE
✅ REPORTER [CP001.health] → NORMAL
```

**Congratulations!** You've successfully connected a simulated charger to the adaptive OCPP system.

---

## Test Scenarios

### Scenario 1: Command Verification (Priority 1)

**Goal:** Test closed-loop feedback - verify that commands are executed and confirmed.

**Steps:**

1. Start Central System and one charger (CP001)

2. Send a ChangeAvailability command via REST API:
```bash
curl -X POST http://localhost:9090/api/command \
  -H "Content-Type: application/json" \
  -d '{
    "chargerId": "CP001",
    "command": "ChangeAvailability",
    "connectorId": 0,
    "type": "Inoperative"
  }'
```

3. **Expected behavior:**
   - Command sent to charger
   - CommandVerificationObserver registers pending command
   - Charger responds with StatusNotification(Unavailable)
   - Observer emits STABLE signal (command verified)
   - Within 30s, or DEFECTIVE if timeout

**Logs to watch for:**
```
[AGENTS] promise() → Setting charger CP001 to Inoperative
Registered command for verification: CP001:xxx (expecting: StatusNotification)
Command verified successfully: CP001:xxx (took 234ms)
[AGENTS] fulfill() → Command executed successfully
```

---

### Scenario 2: Load Balancing (Priority 3)

**Goal:** Test multi-charger power coordination with grid capacity limits.

**Setup:**
- Grid capacity: 100kW (default in OcppObservabilitySystem)
- 5 simulated chargers, each consuming 25kW
- Total: 125kW → **EXCEEDS capacity**

**Steps:**

1. Start Central System

2. Start 5 chargers in separate terminals:
```bash
# Terminal 1
export OCPP_ENDPOINT="ws://localhost:8080/CP001"
export CHARGE_POINT_ID="CP001"
npx tsx index_16.ts

# Terminal 2
export OCPP_ENDPOINT="ws://localhost:8080/CP002"
export CHARGE_POINT_ID="CP002"
npx tsx index_16.ts

# Repeat for CP003, CP004, CP005
```

3. Simulate each charger sending MeterValues (25kW power):
   - Use VCP admin interface or modify simulator to send periodic MeterValues
   - Each charger reports 25,000W active power

4. **Expected behavior:**
   - LoadBalancingReporter aggregates: 125kW total
   - Utilization: 125kW / 100kW = 125% → **CRITICAL**
   - LoadBalancingAgent autonomously rebalances
   - Each charger reduced to 20kW (5 × 20kW = 100kW total)
   - SetChargingProfile commands sent to all chargers

**Logs to watch for:**
```
[REPORTER] CRITICAL: Total power 125kW exceeds capacity 100kW
[AGENTS] Rebalancing power: 5 chargers, target 20kW each (total 100kW)
[AGENTS] promise() → Setting power limit for CP001: 20kW
[AGENTS] fulfill() → Power limit set successfully
Load balancing assessment: 100kW / 100kW (100%) → NORMAL
```

---

### Scenario 3: Failure Detection & Auto-Disable (Priorities 1 + ChargerDisableAgent)

**Goal:** Test that a charger with GroundFailure is automatically disabled.

**Steps:**

1. Start Central System and one charger (CP001)

2. Simulate a GroundFailure error using VCP:
   - Modify VCP to send StatusNotification with status "Faulted" and errorCode "GroundFailure"
   - Or use VCP admin interface to send custom message:
```json
[2, "uniqueId", "StatusNotification", {
  "connectorId": 1,
  "errorCode": "GroundFailure",
  "status": "Faulted",
  "timestamp": "2025-01-16T12:00:00Z"
}]
```

3. **Expected behavior:**
   - OcppMessageObserver emits DEFECTIVE signal for connector
   - ChargerHealthReporter assesses CRITICAL urgency
   - ChargerDisableAgent autonomously disables charger
   - ChangeAvailability(Inoperative) sent
   - CommandVerificationObserver verifies execution

**Logs to watch for:**
```
🔴 MONITOR [CP001.connector] → DEFECTIVE (GroundFailure)
🚨 REPORTER [CP001.health] → CRITICAL
[AGENTS] promise() → Autonomously disabling charger CP001 due to CRITICAL health
Command verified successfully: CP001:xxx (took 156ms)
[AGENTS] fulfill() → Charger disabled successfully
```

---

### Scenario 4: Grid Demand Response (Priority 4)

**Goal:** Test external grid demand signals causing charging reduction.

**Steps:**

1. Modify OcppObservabilitySystem to include GridDemandObserver:
```java
// Add to OcppObservabilitySystem constructor
GridDemandObserver gridObserver = new GridDemandObserver(
    monitors,
    new GridDemandObserver.TimeOfDayDemandSource(),
    60  // Poll every 60 seconds
);
```

2. Start system and chargers

3. Wait for peak demand period (4pm-9pm in TimeOfDayDemandSource)
   - Or modify TimeOfDayDemandSource to simulate EMERGENCY

4. **Expected behavior:**
   - GridDemandObserver emits CRITICAL signal (emergency demand-response)
   - Agents react to reduce/defer charging
   - Total grid load reduced

**Logs to watch for:**
```
Grid demand: EMERGENCY → CRITICAL
[REPORTER] Grid demand-response: CRITICAL
[AGENTS] Reducing charging due to grid emergency
```

---

### Scenario 5: Predictive Health Monitoring (Priority 5)

**Goal:** Test failure prediction before actual breakdown.

**Steps:**

1. Start Central System with PredictiveHealthMonitor enabled

2. Simulate degradation pattern over time:
   - Send DEGRADED connector status every 5 minutes
   - Increase frequency: 1 error → 2 errors → 4 errors → 8 errors per hour

3. **Expected behavior:**
   - PredictiveHealthMonitor detects increasing error frequency
   - Emits WARNING before actual failure
   - Enables preventive maintenance

**Logs to watch for:**
```
[PREDICTIVE] WARNING: CP001.connector - error frequency increasing
⚠️  PREDICTION: CP001.connector showing degradation trend - 12 degraded events in 24h
```

---

## Advanced Testing

### Multi-Charger Load Balancing Test Script

Create `test-load-balancing.sh`:

```bash
#!/bin/bash

# Start 10 chargers concurrently
for i in {1..10}; do
  CHARGER_ID=$(printf "CP%03d" $i)
  export OCPP_ENDPOINT="ws://localhost:8080/$CHARGER_ID"
  export CHARGE_POINT_ID="$CHARGER_ID"

  npx tsx index_16.ts > "charger-$CHARGER_ID.log" 2>&1 &
  echo "Started charger $CHARGER_ID (PID: $!)"
done

echo "All chargers started. Check Central System logs for load balancing."
```

### Automated CI/CD Testing

**GitHub Actions Example:**

```yaml
name: OCPP Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Setup Java
        uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'

      - name: Clone VCP Simulator
        run: |
          git clone https://github.com/solidstudiosh/ocpp-virtual-charge-point.git
          cd ocpp-virtual-charge-point
          npm install

      - name: Start OCPP Central System
        run: |
          mvn -f fullerstack-ocpp/pom.xml exec:java \
            -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo" &
          sleep 10  # Wait for server to start

      - name: Run Integration Tests
        run: |
          cd ocpp-virtual-charge-point
          export OCPP_ENDPOINT="ws://localhost:8080/CP001"
          export CHARGE_POINT_ID="CP001"
          npx tsx index_16.ts &
          sleep 5
          # Add assertions here (check logs, REST API health, etc.)
```

---

## Troubleshooting

### Charger won't connect

**Problem:** VCP shows "Connection refused"

**Solution:**
- Check Central System is running: `curl http://localhost:9090/api/health`
- Verify WebSocket port 8080 is open: `netstat -an | grep 8080`
- Check firewall settings

### No signals in logs

**Problem:** Charger connects but no MONITOR/REPORTER signals appear

**Solution:**
- Check OcppMessageObserver is registered: Look for "OcppMessageObserver initialized" in logs
- Verify circuits are active: "Monitor Circuit: ACTIVE"
- Add debug logging: `logger.setLevel(Level.DEBUG)`

### Load balancing not working

**Problem:** Multiple chargers but no rebalancing

**Solution:**
- Check chargers are sending MeterValues with power data
- Verify LoadBalancingAgent is initialized
- Check grid capacity configuration (default 100kW)
- Ensure gauges are emitting measured() values

---

## Testing Checklist

Before production deployment, verify:

- [ ] **Priority 1: Command Verification**
  - [ ] ChangeAvailability verified within 30s
  - [ ] Timeout triggers DEFECTIVE signal
  - [ ] Slow responses (>10s) trigger DEGRADED

- [ ] **Priority 2: Smart Charging**
  - [ ] SetChargingProfile accepted by charger
  - [ ] Power limits enforced (verify MeterValues)
  - [ ] ClearChargingProfile restores defaults

- [ ] **Priority 3: Load Balancing**
  - [ ] Multi-charger power aggregation
  - [ ] Automatic rebalancing when capacity exceeded
  - [ ] Fair proportional allocation

- [ ] **Priority 4: External Signals**
  - [ ] Grid demand observer responds to API
  - [ ] Pricing signals defer expensive charging
  - [ ] Renewable energy signals maximize clean charging

- [ ] **Priority 5: Predictive Monitoring**
  - [ ] Degradation trends detected
  - [ ] Warnings emitted before failure
  - [ ] Preventive maintenance triggered

---

## Next Steps

1. **Run basic connectivity test** (Scenario 1)
2. **Test load balancing** with 5+ chargers (Scenario 2)
3. **Simulate failure scenarios** (Scenario 3)
4. **Integrate with CI/CD** for continuous testing
5. **Add custom test scenarios** for your specific use cases

## Resources

- [Solidstudio VCP GitHub](https://github.com/solidstudiosh/ocpp-virtual-charge-point)
- [OCPP 1.6 Specification](https://www.openchargealliance.org/protocols/ocpp-16/)
- [fullerstack-ocpp Architecture](./ARCHITECTURE.md)
- [Substrates Signal-Flow](https://github.com/Humainary/substrates-java)

## Support

For issues or questions:
1. Check logs in both Central System and VCP
2. Review ARCHITECTURE.md for signal flow patterns
3. Open an issue on GitHub with logs and configuration
