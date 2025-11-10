# Interactive OODA Loop Demo

A comprehensive, runnable demonstration of the **complete bidirectional OODA loop** with realistic Kafka cluster simulation.

## 🎯 What This Demo Shows

### **Bidirectional Flow Architecture**

```
UPWARD (Sensing):
  Partition queue overflow → Monitor.degraded()
    → Cell hierarchy aggregates
    → ClusterHealthReporter assesses CRITICAL
    → AlertActor sends alerts

DOWNWARD (Control):
  User/Actor issues Command.THROTTLE
    → CommandHierarchy broadcasts
    → All partition handlers receive command
    → Producers reduce throughput 50%
    → System stabilizes
```

## 🚀 Running the Demo

### Option 1: Maven

```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-core
mvn exec:java -Dexec.mainClass="io.fullerstack.kafka.core.demo.InteractiveOODADemo"
```

### Option 2: IDE

1. Open `InteractiveOODADemo.java`
2. Run the `main()` method
3. Interact with the menu in the console

### Option 3: Tests

```bash
mvn test -Dtest=BidirectionalOODALoopTest
```

## 📊 Demo Components

### **1. Kafka Cluster Simulator**

Realistic simulation including:

- **4 Producers** producing at 80-100 msg/s each
- **4 Partitions** with 1000-message queues
- **2 Brokers** with CPU/memory metrics
- **Real queue overflow** when producers overwhelm consumers
- **Realistic broker degradation** under load

### **2. OODA Observability System**

Complete integration:

- **Layer 1 (OBSERVE)**: Monitor conduits tracking health
- **Layer 2 (ORIENT)**: Cell hierarchy aggregating signals
- **Layer 3 (DECIDE)**: Reporters assessing urgency
- **Layer 4 (ACT)**: Actors issuing commands + sending alerts

### **3. Command Handlers**

Partition-level handlers responding to:

- `THROTTLE` - Reduce producer rate 50%
- `RESUME` - Restore normal rates
- `CIRCUIT_OPEN` - Stop all traffic
- `CIRCUIT_CLOSE` - Resume after circuit break
- `MAINTENANCE` - Enter read-only mode
- `SHUTDOWN` - Emergency stop

## 🎬 Interactive Menu

```
📋 MENU:
  1. Show cluster status
  2. Issue THROTTLE command (reduce load 50%)
  3. Issue RESUME command (restore normal rates)
  4. Issue CIRCUIT_OPEN command (stop all traffic)
  5. Run automated degradation → recovery scenario
  6. Exit
```

## 🎭 Automated Scenario

Choose option 5 to run a complete automated lifecycle:

```
[Phase 1] Normal operation (5 seconds)
  - Producers running at full rate
  - Queues filling up
  - Brokers handling load

[Phase 2] Load spike - producers overwhelm system
  - Queue utilization >95%
  - Brokers degrading (CPU/memory)
  - Partition overflow warnings

[Phase 3] OODA loop responds - issuing THROTTLE
  - Monitor.degraded() emitted
  - ClusterHealthReporter → CRITICAL
  - AlertActor sends PagerDuty/Slack/Teams alerts
  - THROTTLE command broadcast
  - All producers reduce rate 50%

[Phase 4] System stabilizes (5 seconds)
  - Queue utilization drops to <70%
  - Brokers recover to HEALTHY
  - Alerts stop

[Phase 5] Recovery - issuing RESUME
  - RESUME command broadcast
  - Producers restore normal rates
  - System back to steady state
```

## 📈 Real-Time Monitoring

The demo includes automatic monitoring that:

1. Checks partition queue depths every second
2. Detects overflow conditions (>95% utilization)
3. Emits `Monitor.degraded()` signals
4. Checks broker CPU/memory health
5. Triggers the OODA loop automatically

## 🔍 What to Watch For

### **Upward Flow Indicators**

```
⚠️  OVERFLOW: broker-1.orders.p0 queue at 96.2% (962/1000)
🏥 BROKER HEALTH: broker-1 HEALTHY → DEGRADED (CPU=78.3%, Mem=72.1%)
📟 PAGERDUTY: [critical] demo-cluster - Cluster health critical
💬 SLACK: [#alerts] Cluster health critical: DEGRADED
```

### **Downward Flow Indicators**

```
📨 [broker-1.orders.p0] Received command: THROTTLE
🔽 THROTTLE: producer-1 reduced to 50 msg/s (50%)
✅ RECOVERED: broker-1.orders.p0 queue at 68.5% (685/1000)
```

## 📝 Example Output

```
═══════════════════════════════════════════════════════════════════════════════
📊 CLUSTER STATUS: demo-cluster
═══════════════════════════════════════════════════════════════════════════════
Messages: Produced=15420, Consumed=12350, Lag=3070

🏭 PRODUCERS:
  producer-1: rate=100 msg/s, throttled=NO, total=3850
  producer-2: rate=100 msg/s, throttled=NO, total=3920
  producer-3: rate=80 msg/s, throttled=NO, total=3080
  producer-4: rate=90 msg/s, throttled=NO, total=3570

📦 PARTITIONS:
  broker-1.orders.p0: queue=962/1000 (96.2%), overflowing=YES
  broker-1.orders.p1: queue=948/1000 (94.8%), overflowing=NO
  broker-1.payments.p0: queue=810/1000 (81.0%), overflowing=NO
  broker-2.orders.p0: queue=850/1000 (85.0%), overflowing=NO

🖥️  BROKERS:
  broker-1: CPU=78.3%, Memory=72.1%, Health=DEGRADED
  broker-2: CPU=65.2%, Memory=61.5%, Health=WARNING
═══════════════════════════════════════════════════════════════════════════════
```

## 🎓 Learning Points

### **1. OODA Loop in Action**

See Boyd's OODA loop principles applied to real-time systems:
- **Observe**: Continuous monitoring of partition queues & broker health
- **Orient**: Cell hierarchy aggregates signals for cluster-wide view
- **Decide**: Reporters assess urgency (WARNING → CRITICAL)
- **Act**: Actors issue commands that cascade downward

### **2. Bidirectional Cell Hierarchy**

Understand how Substrates Cells enable:
- **Upward aggregation**: Partition → Topic → Broker → Cluster
- **Downward broadcast**: Cluster → Broker → Topic → Partition
- **Single hierarchy, dual flow**: Same structure for sensing + control

### **3. Adaptive Response**

Watch the system adapt to load:
- **Throttling** when overwhelmed
- **Circuit breaking** for cascading failures
- **Automatic recovery** when stable

## 🛠️ Architecture Components

### **Files**

- `InteractiveOODADemo.java` - Main demo application
- `KafkaClusterSimulator.java` - Cluster simulation engine
- `ProducerSimulator.java` - Producer with throttling
- `PartitionSimulator.java` - Partition queue simulation
- `BrokerSimulator.java` - Broker health simulation

### **Integration**

Uses the complete fullerstack-kafka-core system:
- `KafkaObservabilitySystem` - Main OODA integration
- `CommandHierarchy` - Downward command propagation
- `HierarchyManager` - Upward signal aggregation
- All 4 OODA layers (Monitors, Reporters, Actors)

## 🧪 Testing

Verify bidirectional flow with tests:

```bash
# Full test suite (165 tests)
mvn test

# Bidirectional flow tests only
mvn test -Dtest=BidirectionalOODALoopTest

# Full OODA loop tests
mvn test -Dtest=FullOODALoopTest

# Stack verification
mvn test -Dtest=FullStackVerificationTest
```

All tests demonstrate different aspects of the bidirectional OODA loop.

## 📚 Further Reading

- Boyd's OODA Loop: https://en.wikipedia.org/wiki/OODA_loop
- Humainary Substrates: https://github.com/humainary-io/substrates-java
- Fullerstack Substrates Implementation: `/workspaces/fullerstack-humainary/fullerstack-substrates`

## 🎉 Have Fun!

This demo proves that the complete bidirectional OODA loop works in a realistic scenario.

Experiment with:
- Different producer rates
- Manual command sequences (THROTTLE → CIRCUIT_OPEN → RESUME)
- Observing automatic recovery
- Understanding signal propagation timing

**The system is fully interactive - your commands have immediate, visible effects!**
