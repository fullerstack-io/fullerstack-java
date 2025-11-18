# Getting Started with Kafka Observability Demo

**Created**: 2025-11-16
**Status**: Phase 1 Complete (Console Demo)

---

## What We Just Built

✅ **Standalone demo module** (`fullerstack-kafka-demo`)
✅ **Docker Compose** (3 Kafka brokers + ZooKeeper + PostgreSQL)
✅ **Demo application** (Quarkus-based, uses existing observers)
✅ **Chaos scenarios** (Broker failure script)
✅ **Zero new observer code** (reuses Phases 1-3 implementations)

---

## Start Demo in 3 Steps

### Step 1: Start Kafka Cluster

```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
docker-compose up -d
```

**Wait for brokers to be ready** (~30 seconds):
```bash
docker-compose logs -f kafka-broker-1 | grep "started (kafka.server.KafkaServer)"
```

### Step 2: Build & Run Demo

```bash
# Build (first time only)
mvn clean package -DskipTests

# Run demo
mvn quarkus:dev
```

**Expected banner**:
```
╔══════════════════════════════════════════════════════════════════╗
║                                                                  ║
║    Kafka Semiotic Observability Demo                           ║
║    Fullerstack + Humainary Substrates                           ║
║                                                                  ║
║    OODA Loop: Observe → Orient → Decide → Act                   ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝

🚀 Starting Kafka Observability Demo
📊 Kafka brokers: localhost:9092,localhost:9093,localhost:9094
🔍 JMX URL: localhost:9999
🎯 Mode: FULL
✅ All observers initialized
👀 Watching Kafka cluster for signals...
```

### Step 3: Inject Chaos

**In a new terminal**:
```bash
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
./scenarios/01-broker-failure.sh
```

**Watch the demo terminal** for signal flow:
```
[OBSERVE] 🔍 Probe: DISCONNECT (broker-1)
[ORIENT]  🧭 Monitor: DEGRADED (cluster.health)
[DECIDE]  🎯 Reporter: CRITICAL (cluster.health)
[ACT]     🤖 Agent: PROMISE to handle failover
[ACT]     🤖 Agent: FULFILL
```

---

## What's Next?

### Phase 2 (Next Week): Human Interface

**To Add**:
1. REST API endpoints (`/api/v1/decisions`)
2. WebSocket for real-time Actor signals
3. PolicyRouter (AUTONOMOUS vs SUPERVISED)
4. Vue.js frontend (decision approval UI)

**New files needed**:
- `src/main/java/io/fullerstack/kafka/demo/api/DecisionsResource.java`
- `src/main/java/io/fullerstack/kafka/demo/policy/PolicyRouter.java`
- `src/main/java/io/fullerstack/kafka/demo/actor/HumanApprovalService.java`
- `frontend/` (Vue.js 3 app)

---

## Troubleshooting

### "Connection refused" to Kafka

**Problem**: Kafka not ready yet
**Solution**: Wait 30 seconds, check with:
```bash
docker-compose logs kafka-broker-1 | grep "started"
```

### "Port 8080 already in use"

**Problem**: Another app using port 8080
**Solution**: Use custom port:
```bash
mvn quarkus:dev -Dquarkus.http.port=8081
```

### Build fails

**Problem**: Parent POM not found
**Solution**: Build from parent first:
```bash
cd /workspaces/fullerstack-java
mvn clean install -DskipTests
```

---

## Architecture Overview

```
Demo Application (Quarkus)
    ↓
Uses Existing Modules (Zero New Code):
├── fullerstack-kafka-producer  (ProducerBufferObserver)
├── fullerstack-kafka-consumer  (ConsumerLagObserver)
├── fullerstack-kafka-broker    (BrokerHealthObserver)
└── fullerstack-substrates      (Circuit, Conduits, Agents)
    ↓
Observes Real Kafka Cluster:
├── kafka-broker-1 (JMX: 9999)
├── kafka-broker-2 (JMX: 9998)
└── kafka-broker-3 (JMX: 9997)
```

---

## Quick Reference

### Docker Commands

```bash
# Start cluster
docker-compose up -d

# View logs
docker-compose logs -f

# Stop cluster
docker-compose down

# Full cleanup (removes volumes)
docker-compose down -v

# Check broker status
docker ps --filter "name=kafka-demo-broker"
```

### Maven Commands

```bash
# Build
mvn clean package

# Run with live reload
mvn quarkus:dev

# Build Docker image (Phase 2)
mvn package -Pdocker

# Skip tests
mvn clean package -DskipTests
```

### Environment Variables

```bash
# Kafka brokers
export KAFKA_BOOTSTRAP=localhost:9092,localhost:9093,localhost:9094

# JMX endpoint
export JMX_URL=localhost:9999

# Demo mode (FULL, PRODUCER, CONSUMER, BROKER)
export DEMO_MODE=FULL
```

---

## What's Working Now (Phase 1)

✅ Docker Compose with 3 Kafka brokers
✅ JMX endpoints exposed (9999, 9998, 9997)
✅ Demo application starts and initializes observers
✅ Chaos scenario scripts (broker failure)
✅ Signal flow visible in console logs

---

## What's Coming (Phase 2)

⏳ REST API for decision approval
⏳ WebSocket for real-time Actor signals
⏳ PolicyRouter (AUTONOMOUS vs SUPERVISED)
⏳ Vue.js UI with approval buttons
⏳ PostgreSQL audit trail
⏳ Grafana dashboards (traditional vs semiotic)

---

**Questions?** Check:
- `/workspaces/kafka-obs/docs/architecture/DEMO-IMPLEMENTATION-PLAN.md`
- `/workspaces/kafka-obs/docs/architecture/REALISTIC-DEMO-ARCHITECTURE.md`
- `/workspaces/fullerstack-java/fullerstack-kafka-demo/README.md`

---

**Ready to run?** Just `docker-compose up -d && mvn quarkus:dev`!
