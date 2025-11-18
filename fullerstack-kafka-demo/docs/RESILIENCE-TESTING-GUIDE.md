# Resilience Testing Guide - Actual Implementation

**Date:** 2025-11-18
**Purpose:** Test sidecar auto-discovery resilience with ACTUAL code and naming conventions

---

## Actual Naming Conventions

**⚠️ IMPORTANT:** This guide uses the REAL defaults from the code, not assumed examples.

```java
// From ProducerSidecarApplication.java:43
String sidecarId = System.getenv().getOrDefault("SIDECAR_ID", "producer-sidecar-1");

// From CentralPlatformApplication.java:47-49
String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
String requestTopic = System.getenv().getOrDefault("REQUEST_TOPIC", "observability.speech-acts");
String responseTopic = System.getenv().getOrDefault("RESPONSE_TOPIC", "observability.responses");
```

**Real sidecar IDs:**
- Default: `"producer-sidecar-1"` (NOT "producer-1")
- Can be customized via `SIDECAR_ID` environment variable
- JMX endpoint inferred from LAST numeric component (e.g., "-1" → port 11001)

---

## Prerequisites

### 1. Kafka Running

```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
docker-compose up -d

# Verify Kafka is ready
docker-compose ps | grep kafka-broker-1
# Should show "Up" and "healthy"
```

### 2. Build Latest Code

```bash
cd /workspaces/fullerstack-java

# Build coordination module (production code)
cd fullerstack-kafka-coordination
mvn clean install -DskipTests

# Build producer module
cd ../fullerstack-kafka-producer
mvn clean install -DskipTests

# Build demo module
cd ../fullerstack-kafka-demo
mvn clean package -DskipTests
```

---

## Test 1: Normal Registration (Central Started First)

**Goal:** Verify sidecar auto-discovery works in normal startup order.

### Steps

**Terminal 1 - Start Central:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication
```

**Expected logs:**
```
✅ Created SidecarRegistry (auto-discovery enabled)
✅ Created SpeechActListener (with auto-discovery)
SpeechActListener started - waiting for speech acts...
```

**Terminal 2 - Start Sidecar:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

# Use default SIDECAR_ID="producer-sidecar-1"
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
```

**Expected logs in Terminal 1 (Central):**
```
[RECEIVED] Speech act: REQUEST from producer-sidecar-1
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1 (type=producer, jmx=localhost:11001)
```

**✅ PASS Criteria:**
- Central logs show "AUTO-DISCOVERED sidecar: producer-sidecar-1"
- Type detected as "producer"
- JMX endpoint inferred as "localhost:11001"

---

## Test 2: Start Order Independence (Sidecar Started First)

**Goal:** Verify sidecar messages are buffered in Kafka and consumed when central starts later.

### Steps

**Terminal 1 - Start Sidecar FIRST:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

export SIDECAR_ID="producer-sidecar-1"

java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
```

**Sidecar will send messages, but no central is listening yet.**

**Wait 15 seconds** (allow heartbeats to buffer in Kafka topic)

**Terminal 2 - Start Central AFTER:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication
```

**Expected logs in Terminal 2:**
```
SpeechActListener initialized: topic=observability.speech-acts, bootstrap=localhost:9092
Auto-discovery enabled with offset strategy: earliest
SpeechActListener started - waiting for speech acts...
[RECEIVED] Speech act: REQUEST from producer-sidecar-1  ← Buffered message!
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1 (type=producer, jmx=localhost:11001)
💓 Heartbeat from sidecar: producer-sidecar-1  ← Buffered heartbeat!
```

**✅ PASS Criteria:**
- Central discovers sidecar even though sidecar started first
- Central consumes buffered REQUEST and HEARTBEAT messages
- Registry shows sidecar as active

---

## Test 3: Central Restart Recovery

**Goal:** Verify central can restart and re-discover sidecars from buffered heartbeats.

### Steps

**Terminal 1 - Start Both:**
```bash
# Start central
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication &
CENTRAL_PID=$!

# Wait for central to be ready
sleep 5

# Start sidecar
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication &
SIDECAR_PID=$!
```

**Verify registration:**
```
# Central logs should show:
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1
```

**Kill Central (simulate crash):**
```bash
kill $CENTRAL_PID
```

**Wait 15 seconds** (allow heartbeats to buffer while central is down)

**Restart Central:**
```bash
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication
```

**Expected logs:**
```
Auto-discovery enabled with offset strategy: earliest
[RECEIVED] Speech act: REPORT from producer-sidecar-1  ← Buffered heartbeat!
💓 Heartbeat from sidecar: producer-sidecar-1
```

**✅ PASS Criteria:**
- Central re-discovers sidecar from buffered heartbeats
- Registry shows sidecar as active
- No manual re-registration needed

---

## Test 4: Multiple Sidecars

**Goal:** Verify multiple sidecars with different IDs get unique JMX endpoints.

### Steps

**Terminal 1 - Central:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication
```

**Terminal 2 - Sidecar 1:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

export SIDECAR_ID="producer-sidecar-1"
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
```

**Terminal 3 - Sidecar 2:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo

export SIDECAR_ID="producer-sidecar-2"
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
```

**Expected logs in Terminal 1:**
```
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1 (type=producer, jmx=localhost:11001)
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-2 (type=producer, jmx=localhost:11002)
```

**✅ PASS Criteria:**
- Both sidecars discovered
- Each gets unique JMX endpoint (11001, 11002)
- No port collision

---

## Test 5: Heartbeat Mechanism

**Goal:** Verify heartbeats are sent every 10 seconds and registry tracks last-seen.

### Steps

**Start central + sidecar:**
```bash
# Terminal 1
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication

# Terminal 2
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
```

**Watch logs for 60 seconds:**

**Expected in Terminal 1 (Central) - every 10 seconds:**
```
💓 Heartbeat from sidecar: producer-sidecar-1  (TRACE level)
```

**Note:** Set logging level to TRACE to see heartbeats:
```bash
# In logback.xml or via JVM args:
-Dorg.slf4j.simpleLogger.log.io.fullerstack.kafka.coordination.central.SidecarRegistry=TRACE
```

**✅ PASS Criteria:**
- Heartbeat logs appear every ~10 seconds
- No log spam (heartbeats are TRACE level)
- Sidecar shows as "active" in registry

---

## Test 6: Inactive Detection

**Goal:** Verify sidecars marked INACTIVE after 30 seconds without heartbeat.

### Steps

**Start central + sidecar:**
```bash
# Start both as in Test 5
```

**After registration, kill sidecar (simulate crash):**
```bash
pkill -f ProducerSidecarApplication
```

**Watch central logs - after 30 seconds:**

If you add registry monitoring to central (future enhancement):
```
⚠️ Sidecar INACTIVE: producer-sidecar-1 (last seen: 32 seconds ago)
```

**Manual verification:**

Add test endpoint to CentralPlatformApplication:
```java
// Print registry status every 15 seconds
Thread.ofVirtual().start(() -> {
    while (true) {
        Thread.sleep(15_000);
        logger.info("Active sidecars: {}", registry.getActiveSidecars());
        logger.info("Inactive sidecars: {}", registry.getInactiveSidecars());
    }
});
```

**✅ PASS Criteria:**
- Sidecar moves from active to inactive list after 30s
- No exceptions or errors

---

## Test 7: No Duplicate Consumers

**Goal:** Verify only ONE Kafka consumer group exists (not two).

### Steps

**Start central:**
```bash
java --enable-preview -cp target/kafka-observability-demo.jar \
    io.fullerstack.kafka.demo.central.CentralPlatformApplication
```

**Check Kafka consumer groups:**
```bash
docker exec kafka-demo-broker-1 \
    kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --list
```

**Expected output:**
```
central-platform
```

**❌ BAD output (if refactoring wasn't done):**
```
central-platform
central-platform-discovery  ← Should NOT exist!
```

**✅ PASS Criteria:**
- Only ONE consumer group: "central-platform"
- No "central-platform-discovery" group

---

## Test 8: JMX Endpoint Inference

**Goal:** Verify JMX endpoint inference works for different sidecar ID formats.

### Test Cases

```bash
# Test 1: Default format "producer-sidecar-1"
export SIDECAR_ID="producer-sidecar-1"
# Expected JMX: localhost:11001 ✅

# Test 2: Short format "producer-1" (if someone uses it)
export SIDECAR_ID="producer-1"
# Expected JMX: localhost:11001 ✅

# Test 3: Consumer "consumer-sidecar-2"
export SIDECAR_ID="consumer-sidecar-2"
# Expected JMX: localhost:11102 ✅

# Test 4: Edge case "producer-foo-bar-3"
export SIDECAR_ID="producer-foo-bar-3"
# Expected JMX: localhost:11003 ✅
```

**Verification:**

Check central logs:
```
🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1 (type=producer, jmx=localhost:11001)
🆕 AUTO-DISCOVERED sidecar: consumer-sidecar-2 (type=consumer, jmx=localhost:11102)
```

**✅ PASS Criteria:**
- All formats parse correctly
- JMX endpoint matches expected value
- No NumberFormatException in logs

---

## Common Issues

### Issue 1: Kafka Not Running

**Error:**
```
Error connecting to node localhost:9092
```

**Fix:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
docker-compose up -d
```

### Issue 2: Build Artifacts Missing

**Error:**
```
Error: Could not find or load main class
```

**Fix:**
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
mvn clean package -DskipTests
```

### Issue 3: Port Already in Use

**Error:**
```
Address already in use: bind
```

**Fix:**
```bash
# Kill existing processes
pkill -f CentralPlatformApplication
pkill -f ProducerSidecarApplication
```

---

## Success Criteria Summary

All tests must PASS for resilience to be considered production-ready:

- [x] Fix JMX endpoint parsing bug (COMPLETED)
- [ ] Test 1: Normal registration works
- [ ] Test 2: Sidecar-first startup works
- [ ] Test 3: Central restart recovery works
- [ ] Test 4: Multiple sidecars work
- [ ] Test 5: Heartbeats sent every 10s
- [ ] Test 6: Inactive detection after 30s
- [ ] Test 7: No duplicate consumers
- [ ] Test 8: JMX inference handles all formats

**Current Status:** Code fixes complete, manual testing in progress

---

## Next Steps

1. Run all 8 tests manually
2. Document results for each test
3. Add automated integration tests
4. Add registry monitoring/logging
5. Create shell scripts for common test scenarios
