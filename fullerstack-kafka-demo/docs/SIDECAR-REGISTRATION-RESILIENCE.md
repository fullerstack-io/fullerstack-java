# Sidecar Registration Resilience Analysis

## Critical Questions

1. **Does a sidecar poll until it connects?**
2. **Can sidecar start before central server and still register OK?**
3. **If central server restarts, will sidecars re-register?**

---

## Current Implementation Status

### ✅ What Works (Built-in Kafka Resilience)

#### 1. Kafka-Based Communication = Automatic Resilience

**Architecture:**
```
Sidecar → Kafka Topic → Central Platform
         (persistent)
```

**Key Properties:**
- ✅ **Persistent Queue:** Messages stored in Kafka until consumed
- ✅ **Automatic Reconnection:** Kafka clients handle connection failures
- ✅ **Order Independence:** Start order doesn't matter
- ✅ **Decoupling:** Sidecar and central completely independent

#### 2. Start Order Independence (Already Working!)

**Scenario A: Central starts first**
```bash
# Terminal 1
./central-platform.sh
# → Waiting for messages on 'observability.speech-acts'

# Terminal 2
./producer-sidecar.sh --id producer-1
# → Sends REQUEST to Kafka
# → Central consumes immediately
# ✅ Sidecar registered!
```

**Scenario B: Sidecar starts first** (⚠️ Current behavior)
```bash
# Terminal 1
./producer-sidecar.sh --id producer-1
# → Sends REQUEST to Kafka
# → Message buffered in topic (no consumer yet)

# Terminal 2
./central-platform.sh
# → Starts consuming from topic
# → Reads buffered REQUEST message
# ✅ Sidecar registered! (from buffered message)
```

**Why it works:**
- Kafka topics persist messages (default retention: 7 days)
- Consumer starts at `auto.offset.reset=latest` OR `earliest` (we use `latest`)
- Messages sent before consumer starts are LOST with `latest`, CONSUMED with `earliest`

**⚠️ ISSUE FOUND:** We use `auto.offset.reset=latest` in `SidecarDiscoveryListener.java`:
```java
props.put("auto.offset.reset", "latest");  // ← Will MISS buffered messages!
```

**FIX NEEDED:** Change to `earliest` to consume buffered registration messages:
```java
props.put("auto.offset.reset", "earliest");  // ← Will consume all messages from topic start
```

#### 3. Kafka Client Auto-Reconnection (Already Working!)

**Built-in Kafka Producer Resilience:**
```java
// Kafka producer config (automatic retries)
props.put("retries", Integer.MAX_VALUE);              // Retry forever
props.put("max.in.flight.requests.per.connection", 5);
props.put("delivery.timeout.ms", 120000);             // 2 minutes before giving up
props.put("reconnect.backoff.ms", 50);                // Start with 50ms
props.put("reconnect.backoff.max.ms", 1000);          // Max 1s between retries
```

**What happens when Kafka is down:**
```
Sidecar starts → Kafka unavailable → Producer buffers messages locally →
Kafka comes online → Producer auto-reconnects → Sends buffered messages
```

**Built-in Kafka Consumer Resilience:**
```java
// Kafka consumer automatically handles:
- Connection failures (auto-reconnect)
- Broker failures (failover to replica)
- Rebalancing (group coordination)
```

---

## ⚠️ MISSING: Heartbeat Mechanism

### Problem: One-Time Registration

**Current Behavior:**
```
1. Sidecar starts → Sends one REQUEST message → Registered
2. Central tracks lastSeenMs timestamp
3. After 30 seconds with no messages → Marked INACTIVE
4. ❌ Sidecar never sends follow-up heartbeats!
```

**Impact:**
- Sidecar will be marked INACTIVE after 30 seconds
- Dashboard shows sidecar as "degraded" or "offline"
- False negative: Sidecar is actually healthy!

### Solution: Add Heartbeat Sender

**Implementation needed:**

#### SidecarHeartbeatSender.java (NEW FILE)

```java
public class SidecarHeartbeatSender implements Runnable, AutoCloseable {
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;  // 10 seconds

    private final String sidecarId;
    private final KafkaCentralCommunicator communicator;
    private volatile boolean running = true;

    @Override
    public void run() {
        while (running) {
            try {
                // Send heartbeat (lightweight REPORT message)
                communicator.sendHeartbeat(sidecarId);

                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Failed to send heartbeat: {}", e.getMessage());
                // Continue - Kafka producer will retry
            }
        }
    }
}
```

**Start in ProducerSidecarApplication.java:**
```java
// After creating communicator, start heartbeat sender
SidecarHeartbeatSender heartbeatSender = new SidecarHeartbeatSender(
    sidecarId,
    centralCommunicator
);
Thread heartbeatThread = Thread.ofVirtual()
    .name("heartbeat-sender-" + sidecarId)
    .start(heartbeatSender);
```

**Heartbeat Message Format:**
```json
{
  "speechAct": "REPORT",
  "source": "producer-1",
  "reportType": "HEARTBEAT",
  "timestamp": 1700000000000,
  "status": "ACTIVE"
}
```

---

## Central Platform Restart Resilience

### Scenario: Central Platform Crashes and Restarts

**Before Heartbeat Implementation:**
```
1. Central platform running, sidecars registered ✅
2. Central platform crashes ❌
3. Central platform restarts
4. Central starts with empty SidecarRegistry (in-memory)
5. ❌ Sidecars NOT re-registered until next REQUEST (may never happen!)
```

**After Heartbeat Implementation:**
```
1. Central platform running, sidecars registered ✅
2. Central platform crashes ❌
3. Sidecars continue sending heartbeats to Kafka (buffered)
4. Central platform restarts
5. Central consumes buffered heartbeat messages
6. ✅ Sidecars automatically re-registered!
```

**Key Requirement:**
- Change `auto.offset.reset=earliest` OR
- Use consumer group with committed offsets

### Persistent Registry (Optional Enhancement)

**Problem:** In-memory `SidecarRegistry` lost on restart

**Solution 1: Kafka Topic as Source of Truth**
```java
// On startup, central platform:
1. Consume ALL messages from 'observability.speech-acts' (from beginning)
2. Rebuild SidecarRegistry from consumed messages
3. Continue consuming new messages
```

**Implementation:**
```java
// SidecarDiscoveryListener.java
props.put("auto.offset.reset", "earliest");  // ← Read from beginning
props.put("group.id", "central-platform-discovery");  // ← Stable group ID

// On first startup:
// - Reads all historical messages
// - Rebuilds registry
// - Marks inactive sidecars (no recent heartbeat)

// On subsequent startups:
// - Resumes from last committed offset (if using offset commits)
// OR
// - Re-reads from beginning (safe, idempotent)
```

**Solution 2: Persistent Storage (Database)**
```java
// SidecarRegistry.java
public class SidecarRegistry {
    private final Map<String, SidecarInfo> sidecars = new ConcurrentHashMap<>();
    private final SidecarDatabase database;  // PostgreSQL, Redis, etc.

    public void registerSidecar(String id, String type, String jmx) {
        SidecarInfo info = new SidecarInfo(id, type, jmx);
        sidecars.put(id, info);
        database.save(info);  // ← Persist to database
    }

    public void loadFromDatabase() {
        List<SidecarInfo> stored = database.loadAll();
        stored.forEach(info -> sidecars.put(info.getSidecarId(), info));
    }
}
```

---

## Recommended Implementation

### Phase 1: Minimum Viable Resilience (4 hours)

1. **Fix Consumer Offset Strategy**
   ```java
   // SidecarDiscoveryListener.java
   props.put("auto.offset.reset", "earliest");  // ← Change from "latest"
   ```

2. **Add Heartbeat Sender**
   - Create `SidecarHeartbeatSender.java`
   - Send REPORT every 10 seconds
   - Wire into `ProducerSidecarApplication.java`

3. **Test Resilience Scenarios**
   ```bash
   # Test 1: Sidecar before central
   ./producer-sidecar.sh &
   sleep 5
   ./central-platform.sh
   # Expected: Sidecar registered from buffered message

   # Test 2: Central restart
   ./central-platform.sh &
   ./producer-sidecar.sh &
   sleep 10
   pkill -f central-platform
   sleep 5
   ./central-platform.sh
   # Expected: Sidecar re-registered from heartbeat messages
   ```

**Deliverable:** Reliable registration regardless of start order or restart

### Phase 2: Production-Grade Resilience (Optional, 8 hours)

1. **Persistent Registry with Database**
   - Add PostgreSQL or Redis dependency
   - Implement `SidecarDatabase` interface
   - Save on registration, load on startup

2. **Graceful Degradation**
   - If Kafka unavailable, sidecar continues running
   - Buffers messages locally (Kafka producer handles this)
   - Auto-reconnects when Kafka available

3. **Health Checks**
   - Sidecar exposes `/health` endpoint
   - Central can poll sidecars directly (backup to heartbeat)

---

## Testing Checklist

### Start Order Tests

- [ ] **Test 1: Normal order (central first)**
  ```bash
  ./central-platform.sh &
  sleep 2
  ./producer-sidecar.sh
  # Expected: Sidecar registered within 1 second
  ```

- [ ] **Test 2: Reverse order (sidecar first)**
  ```bash
  ./producer-sidecar.sh &
  sleep 5
  ./central-platform.sh
  # Expected: Sidecar registered within 1 second (from buffered message)
  ```

- [ ] **Test 3: Simultaneous start**
  ```bash
  ./central-platform.sh & ./producer-sidecar.sh &
  sleep 3
  # Expected: Sidecar registered (race condition safe)
  ```

### Restart Tests

- [ ] **Test 4: Central restart**
  ```bash
  ./central-platform.sh &
  ./producer-sidecar.sh &
  sleep 10
  pkill -f central-platform
  sleep 5
  ./central-platform.sh
  # Expected: Sidecar re-registered from heartbeat
  ```

- [ ] **Test 5: Sidecar restart**
  ```bash
  ./central-platform.sh &
  ./producer-sidecar.sh &
  sleep 10
  pkill -f producer-sidecar
  sleep 35  # Wait for inactive threshold
  # Expected: Sidecar marked INACTIVE in dashboard
  ./producer-sidecar.sh
  # Expected: Sidecar marked ACTIVE again
  ```

### Network Partition Tests

- [ ] **Test 6: Kafka unavailable**
  ```bash
  docker-compose up -d kafka
  ./central-platform.sh &
  docker-compose stop kafka
  ./producer-sidecar.sh &
  # Expected: Sidecar buffers messages locally
  docker-compose start kafka
  sleep 5
  # Expected: Buffered messages sent, sidecar registered
  ```

---

## Summary

### Current Status

| Feature | Status | Notes |
|---------|--------|-------|
| Start order independence | ⚠️ Partial | Works with `earliest`, NOT with `latest` |
| Kafka auto-reconnection | ✅ Built-in | Kafka client handles this |
| Heartbeat mechanism | ❌ Missing | Need to implement |
| Central restart recovery | ⚠️ Partial | Works with `earliest` + heartbeat |
| Persistent registry | ❌ Missing | Optional enhancement |

### Immediate Fixes Needed (4 hours)

1. Change `auto.offset.reset` to `earliest`
2. Implement `SidecarHeartbeatSender`
3. Test start order scenarios

### Result After Fixes

✅ **Sidecar can start before central** - Messages buffered in Kafka
✅ **Central can restart** - Re-discovers sidecars from heartbeat messages
✅ **No polling needed** - Kafka push model (consumer poll loop is async)
✅ **Production-grade resilience** - Same pattern as Kafka Connect/Streams
