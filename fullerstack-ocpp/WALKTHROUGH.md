# OCPP Adaptive System - Complete Walkthrough

This walkthrough demonstrates the **complete OODA loop** in action - from charger messages to autonomous agent responses. Follow along to see all 5 priorities working together.

## Prerequisites (5 minutes)

### 1. Install Dependencies

```bash
# Java 21+
java -version

# Node.js 18+ (for VCP simulator)
node --version
npm --version

# Maven 3.8+
mvn --version
```

### 2. Clone VCP Simulator

```bash
cd ~
git clone https://github.com/solidstudiosh/ocpp-virtual-charge-point.git
cd ocpp-virtual-charge-point
npm install
```

---

## Walkthrough 1: Basic Connectivity & Signal Flow (10 minutes)

**Goal:** See the signal-first architecture in action.

### Terminal 1: Start Central System

```bash
cd fullerstack-java/fullerstack-ocpp
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

**Expected output:**
```
====================================================
  OCPP Central System READY
====================================================

OCPP WebSocket: ws://localhost:8080/{chargerId}
REST API:       http://localhost:9090/api/health

Signal Flow Architecture:
  Layer 0: OCPP Protocol (WebSocket)
  Layer 1: OBSERVE (Message → Signals)
  Layer 2: ORIENT (Signal Flow)
  Layer 3: DECIDE (Urgency Assessment)
  Layer 4: ACT (Autonomous Agents - Promise Theory)
```

### Terminal 2: Connect a Charger

```bash
cd ~/ocpp-virtual-charge-point
export OCPP_ENDPOINT="ws://localhost:8080/CP001"
export CHARGE_POINT_ID="CP001"
npx tsx index_16.ts
```

### Terminal 1: Observe the Signal Flow

You'll see the complete OODA loop:

```
🟢 MONITOR [CP001.connection] → UP
  ↓ (Layer 1: OBSERVE - Connection established)

🟢 MONITOR [CP001.boot] → STABLE
  ↓ (Layer 1: OBSERVE - BootNotification received)

✅ REPORTER [CP001.health] → NORMAL
  ↓ (Layer 3: DECIDE - Health assessment)

  (No agent action needed - system healthy)
```

**What just happened?**

1. **Layer 0 (Protocol):** VCP charger connected via WebSocket
2. **Layer 1 (OBSERVE):** OcppMessageObserver translated BootNotification → STABLE signal
3. **Layer 2 (ORIENT):** Signal flowed through monitor circuit
4. **Layer 3 (DECIDE):** ChargerHealthReporter assessed NORMAL urgency
5. **Layer 4 (ACT):** No action needed (system healthy)

---

## Walkthrough 2: Failure Detection & Auto-Disable (15 minutes)

**Goal:** See ChargerDisableAgent autonomously respond to GroundFailure.

### Terminal 3: Simulate GroundFailure

```bash
cd fullerstack-java/fullerstack-ocpp/testing

# Install dependencies
npm install ws

# Run failure simulator
node simulate-failure-scenario.js ground-failure CP001
```

**Expected output:**
```
==================================================
  OCPP Failure Scenario Simulator
==================================================

Scenario:       ground-failure
Error Code:     GroundFailure
Charger ID:     CP001

Expected Adaptive Response:
  1. OcppMessageObserver emits DEFECTIVE signal
  2. ChargerHealthReporter assesses CRITICAL urgency
  3. ChargerDisableAgent autonomously disables charger
  4. ChangeAvailability(Inoperative) command sent
  5. CommandVerificationObserver verifies execution

✓ Connected to Central System
[1/3] Sending BootNotification...
[2/3] Simulating ground-failure (GroundFailure)...
✓ Failure signal sent

🔍 Watch Central System logs for adaptive response...
```

### Terminal 1: Observe Autonomous Response

```
🔴 MONITOR [CP001.connector] → DEFECTIVE (GroundFailure)
  ↓ (Layer 1: Safety hazard detected)

🚨 REPORTER [CP001.health] → CRITICAL
  ↓ (Layer 3: CRITICAL urgency - immediate action required)

🤝 AGENT [charger-disable-agent.disable-CP001] → PROMISED (committing to action)
  ↓ (Layer 4: Agent promises to disable charger)

[AGENTS] Autonomously disabling charger CP001 due to CRITICAL health
  ↓ (Executing ChangeAvailability command)

✓ AGENT [charger-disable-agent.disable-CP001] → FULFILLED (promise kept)
  ↓ (Command executed successfully)

🟢 MONITOR [CP001.command-response] → STABLE
  ↓ (CommandVerificationObserver confirmed execution)
```

### Terminal 3: Observe Confirmation

```
← Received CALL: ChangeAvailability

🎉 SUCCESS: ChargerDisableAgent sent ChangeAvailability!
   Adaptive response working correctly.

→ Sent acceptance: ChangeAvailability accepted
```

**What just happened?**

1. **Failure detected:** StatusNotification(Faulted, GroundFailure) received
2. **Signal emitted:** OcppMessageObserver → DEFECTIVE
3. **Urgency assessed:** ChargerHealthReporter → CRITICAL
4. **Promise made:** ChargerDisableAgent.promise() → "I will disable this charger"
5. **Action executed:** ChangeAvailability(Inoperative) sent
6. **Verification:** CommandVerificationObserver confirmed within 30s
7. **Promise fulfilled:** Agent.fulfill() → "I kept my promise"

**Closed-loop feedback in action!** ✓

---

## Walkthrough 3: Load Balancing (20 minutes)

**Goal:** See LoadBalancingAgent coordinate 5 chargers to stay within 100kW grid capacity.

### Setup: Stop existing chargers

```bash
# Terminal 2 & 3: Ctrl+C to stop simulators

# Or from Terminal 3:
cd fullerstack-java/fullerstack-ocpp/testing
./stop-chargers.sh
```

### Start 5 Chargers

```bash
cd fullerstack-java/fullerstack-ocpp/testing
chmod +x start-multiple-chargers.sh
./start-multiple-chargers.sh 5 ~/ocpp-virtual-charge-point
```

**Expected output:**
```
=====================================================
  Multi-Charger Load Balancing Test
=====================================================

Starting 5 chargers...

  Starting CP001... OK (PID: 12345)
  Starting CP002... OK (PID: 12346)
  Starting CP003... OK (PID: 12347)
  Starting CP004... OK (PID: 12348)
  Starting CP005... OK (PID: 12349)

=====================================================
  All 5 chargers started successfully!
=====================================================

What to expect:
  1. Each charger connects via WebSocket
  2. Central System logs show MONITOR signals (UP, STABLE)
  3. If total power > 100kW, LoadBalancingAgent rebalances
  4. SetChargingProfile commands sent to reduce power
```

### Terminal 1: Observe Load Balancing

#### Initial State (5 chargers connected):
```
🟢 MONITOR [CP001.connection] → UP
🟢 MONITOR [CP002.connection] → UP
🟢 MONITOR [CP003.connection] → UP
🟢 MONITOR [CP004.connection] → UP
🟢 MONITOR [CP005.connection] → UP

✅ REPORTER [load-balancing.health] → NORMAL
   Total: 0kW / 100kW (0%)
```

#### Simulate Power Consumption

To trigger load balancing, we need chargers to send MeterValues with power > 100kW total.

**In a real scenario:**
- Each charger would send periodic MeterValues (every 60s)
- Power consumption reported (e.g., 25kW per charger)
- Total: 5 × 25kW = 125kW → **EXCEEDS 100kW capacity**

**For this demo:**
The VCP simulator sends MeterValues. If configured to report 25kW each:

```
🟢 MONITOR [CP001.power] → Measured (25000W)
🟢 MONITOR [CP002.power] → Measured (25000W)
🟢 MONITOR [CP003.power] → Measured (25000W)
🟢 MONITOR [CP004.power] → Measured (25000W)
🟢 MONITOR [CP005.power] → Measured (25000W)

🚨 REPORTER [load-balancing.health] → CRITICAL
   Total: 125kW / 100kW (125%) - EXCEEDS capacity!

🤝 AGENT [load-balancing-agent.rebalance] → PROMISED
   Rebalancing power: 5 chargers, target 20kW each

[AGENTS] Setting power limit for CP001: 20kW
[AGENTS] Setting power limit for CP002: 20kW
[AGENTS] Setting power limit for CP003: 20kW
[AGENTS] Setting power limit for CP004: 20kW
[AGENTS] Setting power limit for CP005: 20kW

✓ AGENT [load-balancing-agent.rebalance] → FULFILLED

✅ REPORTER [load-balancing.health] → NORMAL
   Total: 100kW / 100kW (100%) - Within capacity
```

**What just happened?**

1. **Power aggregation:** LoadBalancingReporter summed all charger power
2. **Capacity exceeded:** 125kW > 100kW → CRITICAL urgency
3. **Fair allocation:** 100kW ÷ 5 chargers = 20kW each
4. **Commands sent:** SetChargingProfile(20kW) to all 5 chargers
5. **Verification:** Each command confirmed within 30s
6. **Balance restored:** System back to NORMAL (100kW total)

### Cleanup

```bash
cd fullerstack-java/fullerstack-ocpp/testing
./stop-chargers.sh
```

---

## Walkthrough 4: External Signals - Grid Demand Response (15 minutes)

**Goal:** See how external signals (grid demand, pricing, renewables) influence charging.

### Modify OcppDemo.java (temporary for demo)

Add external signal observers:

```java
// In OcppObservabilitySystem constructor, add:
GridDemandObserver gridObserver = new GridDemandObserver(
    monitors,
    new GridDemandObserver.TimeOfDayDemandSource(),
    60  // Poll every 60 seconds
);

PricingSignalObserver pricingObserver = new PricingSignalObserver(
    monitors,
    new PricingSignalObserver.TimeOfUsePricingSource(),
    60
);

RenewableEnergyObserver renewableObserver = new RenewableEnergyObserver(
    monitors,
    new RenewableEnergyObserver.SolarProductionSource(),
    60
);
```

### Restart Central System

```bash
# Terminal 1: Ctrl+C, then restart
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

### Observe Time-Based Signals

The demo uses time-of-day sources to simulate external signals:

#### Off-Peak (2am):
```
🟢 MONITOR [grid.demand] → STABLE
   Grid: NORMAL demand

🟢 MONITOR [pricing.electricity] → STABLE
   Price: $0.06/kWh (cheap)

🟢 MONITOR [renewable.energy] → STABLE
   Renewable: 30% (wind)

→ Ideal time for EV charging!
```

#### Peak Demand (6pm):
```
🔴 MONITOR [grid.demand] → CRITICAL
   Grid: EMERGENCY demand-response

🔴 MONITOR [pricing.electricity] → CRITICAL
   Price: $0.35/kWh (expensive)

🟡 MONITOR [renewable.energy] → DEGRADED
   Renewable: 25% (solar gone, limited wind)

→ Defer charging to avoid stress + cost
```

#### Solar Peak (12pm):
```
🟢 MONITOR [grid.demand] → STABLE
   Grid: NORMAL demand

🟡 MONITOR [pricing.electricity] → DEGRADED
   Price: $0.12/kWh (moderate)

🟢 MONITOR [renewable.energy] → STABLE
   Renewable: 85% (peak solar + baseline)

→ Maximize charging during clean energy!
```

**What just happened?**

External world signals → Monitor signals → Influence agent decisions

In production, these would connect to:
- Grid: Utility demand-response APIs (OpenADR, MQTT)
- Pricing: Real-time pricing APIs (WattTime, utility APIs)
- Renewables: Grid mix data (ElectricityMap, solar production)

---

## Walkthrough 5: Predictive Health Monitoring (30 minutes)

**Goal:** See PredictiveHealthMonitor detect degradation trends and warn BEFORE failure.

### Enable Predictive Monitoring

Add to OcppObservabilitySystem:

```java
PredictiveHealthMonitor predictiveMonitor = new PredictiveHealthMonitor(
    monitors,
    reporters
);
```

### Simulate Degradation Pattern

Send repeated DEGRADED signals to simulate connector degrading over time:

```bash
# Send 10 DEGRADED connector errors over 20 minutes
for i in {1..10}; do
  node simulate-failure-scenario.js connector-lock CP001
  sleep 120  # Wait 2 minutes
done
```

### Observe Prediction

After several errors, before actual DEFECTIVE:

```
[PREDICTIVE] WARNING: CP001.connector - error frequency increasing
⚠️  PREDICTION: CP001.connector showing degradation trend - 8 degraded events in 24h

🟡 REPORTER [predictive.CP001.connector] → WARNING
   Dimension: PREDICTED (not actual failure yet)
```

**What to do:**

Schedule preventive maintenance for CP001 connector during next low-demand period.

**Without prediction:**
- Wait for catastrophic failure
- Emergency maintenance ($$$)
- Customer downtime

**With prediction:**
- Fix during scheduled maintenance
- Lower cost, no customer impact
- Prevent safety hazards

---

## Walkthrough 6: Full System Test (30 minutes)

**Combine everything:**

1. **Start Central System** with all observers/agents enabled
2. **Start 5 chargers** using multi-charger script
3. **Simulate scenarios:**
   - Load balancing (125kW → 100kW rebalance)
   - Failure on CP003 (auto-disable)
   - Grid demand spike (reduce charging)
   - Degradation pattern on CP001 (predictive warning)

Watch the complete adaptive system coordinate autonomous responses across multiple simultaneous events!

---

## Integration Test Suite

Run automated tests:

```bash
# Unit tests
mvn test

# Specific integration test
mvn test -Dtest=LoadBalancingIntegrationTest

# All tests
mvn verify
```

---

## CI/CD Pipeline

Automated testing runs on every push:

`.github/workflows/ocpp-integration-tests.yml`

- Unit tests
- Integration tests with VCP simulator
- Multi-charger load balancing
- Failure scenarios
- Code quality checks

View results in GitHub Actions tab.

---

## Production Deployment Checklist

Before deploying to production:

- [ ] All 5 walkthroughs completed successfully
- [ ] Integration tests passing (green in CI/CD)
- [ ] Load balancing tested with 10+ chargers
- [ ] Failure scenarios verified (GroundFailure, OverCurrent, etc.)
- [ ] Command verification timeout tested (30s)
- [ ] External signal sources configured (grid API, pricing API)
- [ ] Predictive monitoring thresholds tuned for your data
- [ ] Real charger testing (Kempower, Red Phase, or production hardware)
- [ ] Performance testing (100+ chargers)
- [ ] Security review (WebSocket TLS, authentication)
- [ ] Monitoring & alerting configured
- [ ] Documentation updated for operations team

---

## Troubleshooting

### "Central System not responding"
```bash
# Check if running
curl http://localhost:9090/api/health

# Check logs
# Look for "OCPP Central System READY"

# Check port
netstat -an | grep 8080
```

### "Charger won't connect"
```bash
# Verify endpoint
echo $OCPP_ENDPOINT

# Test WebSocket manually
wscat -c ws://localhost:8080/CP001

# Check firewall
sudo ufw status
```

### "No signals in logs"
```bash
# Enable debug logging in logback.xml
<logger name="io.fullerstack.ocpp" level="DEBUG"/>
```

### "Load balancing not triggering"
- Chargers must send MeterValues with Power.Active.Import
- VCP simulator may not send MeterValues by default
- Modify VCP or use custom simulator to send power data

---

## Next Steps

1. **Explore the code:**
   - `fullerstack-ocpp/src/main/java/io/fullerstack/ocpp/`
   - Start with `ARCHITECTURE.md` for signal-flow overview

2. **Customize for your deployment:**
   - Grid capacity limits
   - External signal sources (real APIs)
   - Charger power limits (min/max)
   - Predictive monitoring thresholds

3. **Add new agents:**
   - PricingOptimizationAgent (defer charging during expensive hours)
   - RenewableMaximizationAgent (maximize during solar peak)
   - FleetPriorityAgent (prioritize emergency vehicles)

4. **Production integration:**
   - Connect to real OCPP chargers
   - Integrate with utility demand-response programs
   - Add monitoring dashboards (Grafana + Prometheus)

---

## Resources

- **Testing Guide:** [TESTING.md](./TESTING.md)
- **Architecture:** [ARCHITECTURE.md](./ARCHITECTURE.md)
- **VCP Simulator:** https://github.com/solidstudiosh/ocpp-virtual-charge-point
- **OCPP 1.6 Spec:** https://www.openchargealliance.org/protocols/ocpp-16/
- **Substrates Framework:** https://github.com/Humainary/substrates-java

---

**Congratulations!** 🎉

You've completed the full walkthrough of the adaptive OCPP system. You've seen:
- Signal-first architecture (OODA loop)
- Autonomous agents (Promise Theory)
- Closed-loop feedback (command verification)
- Multi-charger coordination (load balancing)
- External signal integration (grid, pricing, renewables)
- Predictive health monitoring (failure prevention)

The system is production-ready for adaptive EV charging infrastructure!
