# Kafka Semiotic Observability Demo

Production-realistic demo showcasing **semiotic observability** with the full OODA loop (Observe → Orient → Decide → Act) using Humainary Substrates and Fullerstack implementation.

## Features

- ✅ **Real Kafka Cluster** (3 brokers, ZooKeeper, JMX enabled)
- ✅ **Full OODA Loop** (Layers 1-4 implementation)
- ✅ **Chaos Engineering** (Broker failures, consumer lag, producer bursts)
- 🚧 **Human Interface** (Vue.js UI for approving/denying actions - Phase 2)
- 🚧 **Traditional Comparison** (Side-by-side with Prometheus/Grafana - Phase 2)

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21+
- Maven 3.9+

### Phase 1: Console Demo (Current)

```bash
# 1. Start Kafka cluster
cd /workspaces/fullerstack-java/fullerstack-kafka-demo
docker-compose up -d

# 2. Wait for Kafka to be ready (30 seconds)
docker-compose logs -f kafka-broker-1 | grep "started (kafka.server.KafkaServer)"

# 3. Build demo application
mvn clean package

# 4. Run demo
java -jar target/quarkus-app/quarkus-run.jar

# Alternatively, use Quarkus dev mode
mvn quarkus:dev
```

### Run Chaos Scenarios

```bash
# Terminal 1: Watch demo logs
mvn quarkus:dev

# Terminal 2: Inject chaos
./scenarios/01-broker-failure.sh
```

### Expected Output

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
⚡ Circuit created: SequentialCircuit{name=kafka-demo}
📤 Initializing producer observers...
  ✅ ProducerBufferObserver initialized
📥 Initializing consumer observers...
  ✅ ConsumerLagObserver initialized
🖥️  Initializing broker observers...
  ✅ BrokerHealthObserver initialized
✅ All observers initialized
👀 Watching Kafka cluster for signals...
🔥 Use chaos scripts to inject failures (see scenarios/)

... (signal flow will appear here when chaos is injected)
```

## Chaos Scenarios

### 1. Broker Failure (`01-broker-failure.sh`)

**What it does:**
- Kills `kafka-broker-1`
- Waits 30 seconds
- Restarts broker

**Expected Signal Flow:**
```
[OBSERVE] 🔍 Probe: DISCONNECT (broker-1 heartbeat)
[ORIENT]  🧭 Monitor: DEGRADED (cluster.health, HIGH confidence)
[DECIDE]  🎯 Reporter: CRITICAL (cluster.health)
[ACT]     🤖 Agent: PROMISE to handle failover
[ACT]     🤖 Agent: FULFILL (failover complete)
```

### 2. Producer Buffer Overflow (coming soon)

### 3. Consumer Lag Spike (coming soon)

## Architecture

### Module Structure

```
fullerstack-kafka-demo/
├── pom.xml                       # Maven build (Quarkus + dependencies)
├── docker-compose.yml            # Kafka cluster + PostgreSQL
├── README.md                     # This file
│
├── src/main/java/                # Demo application
│   └── io/fullerstack/kafka/demo/
│       ├── KafkaObservabilityDemoApplication.java  # Main entry point
│       ├── DemoConfig.java                         # Configuration
│       ├── DemoMode.java                           # FULL/PRODUCER/CONSUMER/BROKER
│       ├── api/                                    # REST API (Phase 2)
│       ├── policy/                                 # PolicyRouter (Phase 2)
│       └── chaos/                                  # Chaos controller (Phase 2)
│
├── scenarios/                    # Chaos scenario scripts
│   ├── 01-broker-failure.sh
│   ├── 02-producer-burst.sh      # Coming soon
│   └── 03-consumer-lag.sh        # Coming soon
│
└── frontend/                     # Vue.js UI (Phase 2)
```

### Dependencies

**Existing Fullerstack Modules** (no new code needed):
- `fullerstack-kafka-core` - Core observers (Probes, Services, Queues, Gauges)
- `fullerstack-kafka-producer` - Producer observers
- `fullerstack-kafka-consumer` - Consumer observers
- `fullerstack-kafka-broker` - Broker observers
- `fullerstack-substrates` - Substrates implementation (545/545 tests passing)

**External Dependencies**:
- Quarkus 3.6.0 (REST API, WebSocket, Hibernate)
- PostgreSQL 15 (Actor audit trail)
- Docker Java 3.3.4 (Chaos controller)

## OODA Loop Layers

### Layer 1: OBSERVE
- **Probes**: Communication outcomes (CONNECT, DISCONNECT, TIMEOUT)
- **Services**: Request/response lifecycle (CALL, SUCCEEDED, FAILED)
- **Queues**: Flow control (PUT, TAKE, OVERFLOW, UNDERFLOW)
- **Gauges**: Measurements (buffer size, lag, heap usage)

### Layer 2: ORIENT
- **Monitors**: Condition assessment (STABLE, DEGRADED, ERRATIC, DOWN)
- **Resources**: Capacity tracking (GRANT, DENY)

### Layer 3: DECIDE
- **Reporters**: Urgency determination (NORMAL, WARNING, CRITICAL)

### Layer 4: ACT
- **Agents**: Autonomous promises (OFFER, PROMISE, FULFILL, BREACH)
- **Actors**: Conversational coordination (ASK, EXPLAIN, REQUEST, DELIVER)

## Phase 2: Human Interface (Next Week)

### Planned Features

1. **REST API** (`/api/v1/decisions`)
   - GET `/pending` - List decisions awaiting approval
   - POST `/{id}/approve` - Human approves action
   - POST `/{id}/deny` - Human denies action
   - GET `/policies` - View AUTONOMOUS vs SUPERVISED policies

2. **WebSocket** (`/api/v1/actors/stream`)
   - Real-time Actor signal streaming to UI
   - Sub-second updates when AI acts

3. **Vue.js Dashboard** (`http://localhost:3000`)
   - Live activity stream
   - Pending decisions with approve/deny buttons
   - Policy configuration
   - Audit trail

4. **PolicyRouter**
   - AUTONOMOUS: Agent acts immediately
   - SUPERVISED: Actor requests human approval
   - ADVISORY: Agent acts + Actor explains
   - EMERGENCY: Agent bypasses approval for critical situations

## Environment Variables

```bash
# Kafka cluster
KAFKA_BOOTSTRAP=localhost:9092,localhost:9093,localhost:9094

# JMX for broker metrics
JMX_URL=localhost:9999

# Demo mode
DEMO_MODE=FULL  # or PRODUCER, CONSUMER, BROKER

# Database (Phase 2)
DATABASE_URL=jdbc:postgresql://localhost:5432/kafka_obs_demo
DATABASE_USER=demo_user
DATABASE_PASSWORD=demo_pass
```

## Development

### Build

```bash
# Clean build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Build Docker image
mvn package -Pdocker
```

### Run in Development Mode

```bash
# Live reload with Quarkus
mvn quarkus:dev

# Custom Kafka bootstrap
KAFKA_BOOTSTRAP=kafka1:9092,kafka2:9093 mvn quarkus:dev
```

### Cleanup

```bash
# Stop and remove containers
docker-compose down

# Remove volumes (full cleanup)
docker-compose down -v
```

## Troubleshooting

### Kafka not ready

```bash
# Check broker logs
docker-compose logs kafka-broker-1

# Wait for "started (kafka.server.KafkaServer)" message
```

### JMX connection refused

```bash
# Verify JMX port is exposed
docker-compose ps

# Test JMX connection
echo "get -d java.lang:type=Runtime" | java -jar jmxterm.jar -l localhost:9999
```

### Demo application fails to start

```bash
# Check if port 8080 is available
lsof -i :8080

# Use custom port
mvn quarkus:dev -Dquarkus.http.port=8081
```

## Next Steps

### Phase 2 Tasks

1. ✅ Create demo module structure
2. ✅ Add Docker Compose (Kafka cluster)
3. ✅ Create main application class
4. ✅ Add chaos scenario scripts
5. ⏳ Add REST API endpoints
6. ⏳ Create PolicyRouter
7. ⏳ Build Vue.js UI
8. ⏳ Add WebSocket bridge

### Future Enhancements

- [ ] Grafana dashboards (traditional vs semiotic)
- [ ] Prometheus integration (comparison)
- [ ] Advanced chaos scenarios (network partition, memory pressure)
- [ ] Demo walkthrough videos
- [ ] One-click deployment to cloud

## Contributing

This is a demo module showcasing the Fullerstack + Humainary integration. For production use:
- Use modules directly: `fullerstack-kafka-core`, `fullerstack-kafka-producer`, etc.
- Customize observers for your specific use case
- Adjust policies (AUTONOMOUS vs SUPERVISED) per your requirements

## License

Same as parent project (Fullerstack Kafka)

---

**Questions?** Check the main documentation in `/workspaces/kafka-obs/docs/architecture/`
