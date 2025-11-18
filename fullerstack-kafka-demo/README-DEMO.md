# Kafka Observability Demo - Distributed Coordination

> **Promise Theory + Speech Act Theory** demonstration for distributed coordination

## Overview

This demo showcases **distributed semiotic observability** using two theoretical frameworks:

1. **Promise Theory** (Mark Burgess) - Autonomous agents making local promises
2. **Speech Act Theory** (John Searle) - Conversational coordination between agents

###Architecture

```
Sidecar Agents (Autonomous):           Central Platform (Coordination):
  Agents API (Promise Theory)            Kafka Consumer (speech acts)
    ├─ promise(PROMISER)                   ├─ SpeechActListener
    ├─ fulfill(PROMISER) → 99%             ├─ RequestHandler
    └─ breach(PROMISER)  → 1%              │   ├─ Agents API (promises)
         ↓                                  │   └─ Actors API (responses)
  Actors API (Speech Act Theory)           └─ ResponseSender
    └─ request()  ──────────────────────→       Kafka Producer (responses)
         ↓ (Kafka: observability.speech-acts)          ↓
    Response Listener ←──────────────────────── (Kafka: observability.responses)
         ↓
    AgentCoordinationBridge
```

### Three Levels of Autonomy

| Level | Outcome | Communication | Frequency |
|-------|---------|---------------|-----------|
| **Level 1** | Agent fulfills promise | ❌ None (silent) | 99% |
| **Level 2** | Agent fulfills promise | ✅ REPORT (audit trail) | 0.9% |
| **Level 3** | Agent breaches promise | ✅ REQUEST (help needed) | 0.1% |

---

## Quick Start

### Prerequisites

- Java 25+ with preview features
- Docker and Docker Compose
- Maven 3.9+

### Step 1: Start Kafka Cluster

```bash
cd fullerstack-kafka-demo
docker-compose up -d

# Wait for brokers to be healthy (~30 seconds)
docker-compose ps
```

### Step 2: Build Project

```bash
cd ..
mvn clean install -Dmaven.test.skip=true \
    -pl fullerstack-kafka-producer,fullerstack-kafka-coordination,fullerstack-kafka-demo -am
```

### Step 3: Run Demo (Two Terminals)

**Terminal 1 - Central Platform:**
```bash
cd fullerstack-kafka-demo
./demo.sh central
```

**Terminal 2 - Producer Sidecar:**
```bash
cd fullerstack-kafka-demo
./demo.sh sidecar
```

---

## What You'll See

### Scenario 1: Level 1 - Silent Self-Regulation (99%)

```
[SIDECAR] 📝 Agent PROMISED to self-regulate buffer
[SIDECAR] ✅ Agent FULFILLED promise - Silent success (no communication)
```

**No network traffic** - agent handled everything locally.

---

### Scenario 2: Level 2 - Notable Event Report (0.9%)

```
[SIDECAR] 📝 Agent PROMISED to self-regulate buffer
[SIDECAR] ✅ Agent FULFILLED promise
[SIDECAR] 📊 Notable event detected - sending REPORT to central (audit trail)
[CENTRAL] [REPORT] From producer-sidecar-1: Self-regulation successful
```

**Minimal network traffic** - just audit trail for compliance.

---

### Scenario 3: Level 3 - Request Help (0.1%)

```
[SIDECAR] 📝 Agent PROMISED to self-regulate buffer
[SIDECAR] ⚠️  Local throttling FAILED - buffer still at 95%
[SIDECAR] ❌ Agent BREACHED promise - Cannot self-regulate!
[SIDECAR] 📞 Sending REQUEST to central platform for help...
[SIDECAR]    Speech Act: REQUEST(SCALE_RESOURCES)

[CENTRAL] [REQUEST] From producer-sidecar-1: SCALE_RESOURCES
[CENTRAL] [RESPONSE] Sent ACKNOWLEDGE
[CENTRAL] [PROMISE] Committed to scaling partition replicas
[CENTRAL] [ACTION] Scaling topic 'orders' to 5 partitions
[CENTRAL] [DELIVER] Partition replicas scaled

[SIDECAR] [ACTOR] Response from central: ACKNOWLEDGE
[SIDECAR] [ACTOR] Response from central: PROMISE
[SIDECAR] [ACTOR] Response from central: DELIVER
```

**Full conversation flow** - REQUEST → ACKNOWLEDGE → PROMISE → DELIVER

---

## Speech Acts Reference

| Speech Act | Direction | Meaning |
|-----------|-----------|---------|
| **REQUEST** | Sidecar → Central | "Please help me scale resources" |
| **ACKNOWLEDGE** | Central → Sidecar | "I received your request" |
| **PROMISE** | Central → Sidecar | "I will try to help" |
| **DELIVER** | Central → Sidecar | "I completed the action" |
| **DENY** | Central → Sidecar | "I cannot help (quota exceeded)" |
| **EXPLAIN** | Central → Sidecar | "Here's why/how" |
| **REPORT** | Sidecar → Central | "FYI: Notable event occurred" |

---

## Architecture Details

### Sidecar Components

1. **AgentCoordinationBridge** - Promise Theory + Speech Act Theory coordination
2. **KafkaCentralCommunicator** - Sends speech acts to Kafka
3. **SidecarResponseListener** - Receives responses from Kafka

### Central Platform Components

1. **SpeechActListener** - Kafka consumer for incoming requests
2. **RequestHandler** - Processes requests using Agents/Actors APIs
3. **ResponseSender** - Kafka producer for responses

### Substrates Infrastructure

- **Circuit** - Computational context
- **Conduit** - Dynamic routing (agents/actors from names)
- **Agents.Agent** - Promise-making entity (local autonomy)
- **Actors.Actor** - Speech act entity (distributed coordination)

---

## Kafka Topics

| Topic | Purpose | Partitions | Replication |
|-------|---------|------------|-------------|
| `observability.speech-acts` | Requests from sidecars | 3 | 3 |
| `observability.responses` | Responses from central | 3 | 3 |
| `orders` | Demo application data | 6 | 3 |
| `payments` | Demo application data | 6 | 3 |

---

## Testing

### Integration Test

```bash
cd fullerstack-kafka-demo
mvn test -Dtest=SpeechActCoordinationIntegrationTest
```

This test uses Testcontainers to:
1. Start Kafka in Docker
2. Start central platform
3. Start sidecar
4. Simulate agent breach
5. Verify REQUEST → ACKNOWLEDGE → PROMISE → DELIVER flow

---

## Configuration

### Environment Variables

**Sidecar:**
- `SIDECAR_ID` - Unique sidecar identifier (default: `producer-sidecar-1`)
- `KAFKA_BOOTSTRAP` - Kafka servers (default: `localhost:9092`)
- `REQUEST_TOPIC` - Speech act topic (default: `observability.speech-acts`)
- `RESPONSE_TOPIC` - Response topic (default: `observability.responses`)

**Central:**
- `KAFKA_BOOTSTRAP` - Kafka servers (default: `localhost:9092`)
- `REQUEST_TOPIC` - Speech act topic (default: `observability.speech-acts`)
- `RESPONSE_TOPIC` - Response topic (default: `observability.responses`)

---

## Troubleshooting

### Kafka not starting

```bash
docker-compose down -v
docker-compose up -d
```

### Topics not created

```bash
docker exec kafka-broker-1 kafka-topics --bootstrap-server localhost:9092 --list
```

### Connection refused

Wait 30 seconds for brokers to fully start:

```bash
docker-compose logs kafka-broker-1 | grep "started (kafka.server.KafkaServer)"
```

---

## Next Steps

1. **Add JMX Monitoring** - Connect to real producer JMX metrics
2. **Add REST API** - Query conversation state via HTTP
3. **Add WebSocket** - Stream speech acts to UI
4. **Add Vue.js Frontend** - Visualize Promise Theory + Speech Act Theory

---

## References

- **Promise Theory**: "In Search of Certainty" by Mark Burgess
- **Speech Act Theory**: "Speech Acts" by John Searle
- **Substrates API**: `/workspaces/fullerstack-humainary/fullerstack-substrates/docs/`
- **OODA Loop**: Boyd's Observe-Orient-Decide-Act framework

---

## License

This demo is part of the fullerstack-java project.
