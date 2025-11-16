# OCPP Testing with Virtual Charge Points

Quick start guide for testing fullerstack-ocpp using Solidstudio Virtual Charge Point simulator.

## Quick Setup (5 minutes)

### 1. Install VCP Simulator

```bash
# From any directory
git clone https://github.com/solidstudiosh/ocpp-virtual-charge-point.git
cd ocpp-virtual-charge-point
npm install
```

### 2. Start OCPP Central System

```bash
# From fullerstack-java root
cd fullerstack-ocpp
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

Expected output:
```
====================================================
  OCPP Central System READY
====================================================

OCPP WebSocket: ws://localhost:8080/{chargerId}
REST API:       http://localhost:9090/api/health
```

### 3. Connect Simulated Charger

```bash
# From ocpp-virtual-charge-point directory
export OCPP_ENDPOINT="ws://localhost:8080/CP001"
export CHARGE_POINT_ID="CP001"

npx tsx index_16.ts
```

Expected output in Central System:
```
🟢 MONITOR [CP001.connection] → UP
🟢 MONITOR [CP001.boot] → STABLE
✅ REPORTER [CP001.health] → NORMAL
```

**Success!** Your simulated charger is connected.

---

## Test Scenarios

See [../TESTING.md](../TESTING.md) for detailed test scenarios including:
- Command verification (closed-loop feedback)
- Load balancing (5+ chargers)
- Failure handling (auto-disable)
- Grid demand response
- Predictive health monitoring

---

## Multi-Charger Test

Test load balancing with 5 chargers:

```bash
#!/bin/bash
# save as: start-5-chargers.sh

cd /path/to/ocpp-virtual-charge-point

for i in {1..5}; do
  CHARGER_ID=$(printf "CP%03d" $i)

  export OCPP_ENDPOINT="ws://localhost:8080/$CHARGER_ID"
  export CHARGE_POINT_ID="$CHARGER_ID"

  npx tsx index_16.ts > "logs/charger-$CHARGER_ID.log" 2>&1 &
  echo "Started $CHARGER_ID (PID: $!)"

  sleep 2  # Stagger connections
done

echo "All 5 chargers started. Check Central System logs for load balancing."
```

Make it executable:
```bash
chmod +x start-5-chargers.sh
mkdir -p logs
./start-5-chargers.sh
```

---

## Unit/Integration Tests

Run Java tests:

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=LoadBalancingIntegrationTest

# Run with debug output
mvn test -X
```

Key tests:
- `OcppMessageObserverTest` - Message parsing and signal emission
- `BootNotificationIntegrationTest` - Connection handling
- `LoadBalancingIntegrationTest` - Multi-charger power coordination

---

## Troubleshooting

### Charger won't connect

```bash
# Check Central System is running
curl http://localhost:9090/api/health

# Check port 8080 is listening
netstat -an | grep 8080

# Check firewall
sudo ufw status
```

### No signals in logs

Add debug logging to `logback.xml`:
```xml
<logger name="io.fullerstack.ocpp" level="DEBUG"/>
```

### VCP errors

```bash
# Update VCP
cd ocpp-virtual-charge-point
git pull
npm install

# Clear npm cache
npm cache clean --force
npm install
```

---

## Advanced: Scripted Scenarios

VCP supports scripted behavior. Example: Simulate increasing power consumption over time.

Create `scenario-load-increase.ts`:
```typescript
// Send MeterValues with increasing power every 10 seconds
let power = 10000;  // Start at 10kW

setInterval(() => {
  power += 1000;  // Increase by 1kW each cycle

  // Send MeterValues message
  // ... (VCP API code here)

  console.log(`Power: ${power}W`);
}, 10000);
```

---

## CI/CD Integration

See [../TESTING.md#automated-cicd-testing](../TESTING.md#automated-cicd-testing) for GitHub Actions example.

---

## Resources

- **Full Testing Guide**: [../TESTING.md](../TESTING.md)
- **Architecture**: [../ARCHITECTURE.md](../ARCHITECTURE.md)
- **VCP GitHub**: https://github.com/solidstudiosh/ocpp-virtual-charge-point
- **OCPP 1.6 Spec**: https://www.openchargealliance.org/protocols/ocpp-16/

---

## What to Test

Before production:
- [ ] Basic connectivity (BootNotification)
- [ ] Command execution (ChangeAvailability)
- [ ] Command verification (30s timeout)
- [ ] Multi-charger load balancing (5+ chargers)
- [ ] Failure detection (GroundFailure → auto-disable)
- [ ] Transaction handling (StartTransaction → RemoteStopTransaction)
- [ ] Smart Charging (SetChargingProfile → power limits)

See testing checklist in [TESTING.md](../TESTING.md) for complete list.
