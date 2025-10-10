# Deployment Strategy: Local Development → AWS MSK Production

**Date:** 2025-10-10
**Context:** How to run kafka-obs locally for PoC, then deploy to AWS MSK

---

## TL;DR

**Local (PoC):** Docker Compose with sidecar agents ✅
**AWS MSK (Production):** Separate service agents connecting remotely ✅

---

## 1. Local Development Stack (Docker Compose)

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Docker Compose Stack                                       │
│                                                             │
│  ┌─────────────────┐  ┌─────────────────┐                 │
│  │  Kafka Broker   │  │  Zookeeper      │                 │
│  │  (Confluent)    │  │                 │                 │
│  │                 │  └─────────────────┘                 │
│  │  JMX Port: 9999 │                                       │
│  └────────┬────────┘                                       │
│           │                                                 │
│           │ localhost JMX                                   │
│  ┌────────▼─────────────────────────┐                      │
│  │  BrokerSensorAgent               │                      │
│  │  (Sidecar container)             │                      │
│  │                                  │                      │
│  │  • Connects to localhost:9999    │                      │
│  │  • Reads JMX MBeans              │                      │
│  │  • Emits MonitorSignals          │                      │
│  └────────┬─────────────────────────┘                      │
│           │                                                 │
│  ┌────────▼─────────────────────────┐                      │
│  │  ClientSensorAgent               │                      │
│  │  (Interceptor in producer/       │                      │
│  │   consumer containers)           │                      │
│  │                                  │                      │
│  │  • Kafka Interceptor             │                      │
│  │  • Emits ServiceSignals          │                      │
│  └────────┬─────────────────────────┘                      │
│           │                                                 │
│  ┌────────▼─────────────────────────┐                      │
│  │  PartitionSensorAgent            │                      │
│  │  (Separate container)            │                      │
│  │                                  │                      │
│  │  • Kafka Admin API               │                      │
│  │  • Emits QueueSignals            │                      │
│  └────────┬─────────────────────────┘                      │
│           │                                                 │
│           │ All emit to shared Cortex Runtime              │
│  ┌────────▼─────────────────────────┐                      │
│  │  Cortex Runtime                  │                      │
│  │  (Substrates engine)             │                      │
│  │                                  │                      │
│  │  • Circuits/Conduits             │                      │
│  │  • Pattern Detectors             │                      │
│  │  • Health Aggregators            │                      │
│  └────────┬─────────────────────────┘                      │
│           │                                                 │
│  ┌────────▼─────────────────────────┐                      │
│  │  RocksDB + Redis                 │                      │
│  │  (Persistence layer)             │                      │
│  └──────────────────────────────────┘                      │
│                                                             │
│  ┌──────────────────────────────────┐                      │
│  │  REST API (Replay Service)       │                      │
│  │  Port 8080                       │                      │
│  └──────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────┘
```

### Docker Compose File

```yaml
version: '3.8'

services:
  # Zookeeper
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  # Kafka Broker with JMX enabled
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
      - "9999:9999"  # JMX port
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

      # JMX Configuration
      KAFKA_JMX_PORT: 9999
      KAFKA_JMX_HOSTNAME: kafka
      KAFKA_JMX_OPTS: >
        -Dcom.sun.management.jmxremote
        -Dcom.sun.management.jmxremote.authenticate=false
        -Dcom.sun.management.jmxremote.ssl=false
        -Dcom.sun.management.jmxremote.local.only=false
        -Dcom.sun.management.jmxremote.port=9999
        -Dcom.sun.management.jmxremote.rmi.port=9999
        -Djava.rmi.server.hostname=kafka

  # BrokerSensorAgent - Monitors JMX
  broker-sensor:
    build:
      context: ./sensor-agents
      dockerfile: Dockerfile.broker-sensor
    depends_on:
      - kafka
      - cortex-runtime
    environment:
      KAFKA_JMX_URL: service:jmx:rmi:///jndi/rmi://kafka:9999/jmxrmi
      CORTEX_URL: http://cortex-runtime:8080
      AGENT_ID: broker-sensor-1
    networks:
      - kafka-obs

  # PartitionSensorAgent - Monitors lag via Admin API
  partition-sensor:
    build:
      context: ./sensor-agents
      dockerfile: Dockerfile.partition-sensor
    depends_on:
      - kafka
      - cortex-runtime
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      CORTEX_URL: http://cortex-runtime:8080
      AGENT_ID: partition-sensor-1
      POLL_INTERVAL_MS: 10000
    networks:
      - kafka-obs

  # Cortex Runtime - Substrates engine
  cortex-runtime:
    build:
      context: ./substrates-runtime
      dockerfile: Dockerfile
    ports:
      - "8081:8080"
    depends_on:
      - rocksdb
      - redis
    environment:
      ROCKSDB_PATH: /data/rocksdb
      REDIS_URL: redis://redis:6379
    volumes:
      - rocksdb-data:/data/rocksdb
    networks:
      - kafka-obs

  # RocksDB (embedded in Cortex, but showing conceptually)
  # Redis for fast lookups
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    networks:
      - kafka-obs

  # Replay Service - Query API
  replay-service:
    build:
      context: ./replay-service
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    depends_on:
      - cortex-runtime
    environment:
      ROCKSDB_PATH: /data/rocksdb
    volumes:
      - rocksdb-data:/data/rocksdb
    networks:
      - kafka-obs

  # Test Producer (with ClientSensorAgent interceptor)
  test-producer:
    build:
      context: ./test-clients
      dockerfile: Dockerfile.producer
    depends_on:
      - kafka
      - cortex-runtime
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      CORTEX_URL: http://cortex-runtime:8080
      # Interceptor config
      KAFKA_PRODUCER_INTERCEPTOR_CLASSES: io.kafkaobs.sensors.client.ProducerSignalInterceptor
    networks:
      - kafka-obs

  # Test Consumer (with ClientSensorAgent interceptor)
  test-consumer:
    build:
      context: ./test-clients
      dockerfile: Dockerfile.consumer
    depends_on:
      - kafka
      - cortex-runtime
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      CORTEX_URL: http://cortex-runtime:8080
      # Interceptor config
      KAFKA_CONSUMER_INTERCEPTOR_CLASSES: io.kafkaobs.sensors.client.ConsumerSignalWrapper
    networks:
      - kafka-obs

volumes:
  rocksdb-data:

networks:
  kafka-obs:
    driver: bridge
```

### Running Locally

```bash
# Start the entire stack
docker-compose up -d

# View logs from broker sensor
docker-compose logs -f broker-sensor

# View Cortex runtime processing
docker-compose logs -f cortex-runtime

# Query replay service
curl http://localhost:8080/api/signals/recent

# Trigger test scenario (producer burst)
docker-compose exec test-producer ./burst-messages.sh

# Watch narrative construction
curl http://localhost:8080/api/narratives/latest
```

---

## 2. BrokerSensorAgent Implementation (Local)

### JMX Connection Code

```java
package io.kafkaobs.sensors.broker;

import javax.management.*;
import javax.management.remote.*;
import java.util.*;
import io.kafkaobs.shared.models.*;

public class BrokerSensorAgent {
    private final MBeanServerConnection mbeanConn;
    private final CortexClient cortexClient;
    private final VectorClockManager vectorClock;

    public BrokerSensorAgent(String jmxUrl, String cortexUrl, String agentId)
        throws Exception {

        // Connect to Kafka broker JMX
        JMXServiceURL url = new JMXServiceURL(jmxUrl);
        JMXConnector connector = JMXConnectorFactory.connect(url);
        this.mbeanConn = connector.getMBeanServerConnection();

        this.cortexClient = new CortexClient(cortexUrl);
        this.vectorClock = new VectorClockManager(agentId);
    }

    public void start() {
        // Poll JMX every 10 seconds
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                collectAndEmitMetrics();
            }
        }, 0, 10_000);
    }

    private void collectAndEmitMetrics() {
        try {
            // Heap Memory Usage
            ObjectName heapMemory = new ObjectName(
                "java.lang:type=Memory"
            );
            CompositeData heapUsage = (CompositeData) mbeanConn.getAttribute(
                heapMemory, "HeapMemoryUsage"
            );

            long used = (Long) heapUsage.get("used");
            long max = (Long) heapUsage.get("max");
            double usagePercent = (double) used / max;

            // Determine condition
            Monitors.Condition condition;
            Monitors.Confidence confidence;

            if (usagePercent > 0.90) {
                condition = Monitors.Condition.DEFECTIVE;
                confidence = Monitors.Confidence.CONFIRMED;
            } else if (usagePercent > 0.80) {
                condition = Monitors.Condition.DEGRADED;
                confidence = Monitors.Confidence.SUSPECTED;
            } else {
                condition = Monitors.Condition.STABLE;
                confidence = Monitors.Confidence.CONFIRMED;
            }

            // Emit MonitorSignal
            MonitorSignal signal = MonitorSignal.create(
                "kafka.broker.health",
                "broker-1.jvm.heap",  // subject
                condition,
                confidence,
                Map.of(
                    "heapUsed", String.valueOf(used),
                    "heapMax", String.valueOf(max),
                    "heapPercent", String.format("%.2f", usagePercent * 100)
                )
            );

            // Attach VectorClock
            signal = signal.withVectorClock(vectorClock.increment());

            // Send to Cortex
            cortexClient.emit(signal);

            // GC Time (pause detection)
            ObjectName gcName = new ObjectName(
                "java.lang:type=GarbageCollector,name=*"
            );
            Set<ObjectName> gcBeans = mbeanConn.queryNames(gcName, null);

            for (ObjectName gc : gcBeans) {
                Long collectionTime = (Long) mbeanConn.getAttribute(gc, "CollectionTime");
                Long collectionCount = (Long) mbeanConn.getAttribute(gc, "CollectionCount");

                // Detect long GC pauses
                if (collectionTime > 2000) {  // >2s GC pause
                    MonitorSignal gcSignal = MonitorSignal.defective(
                        "kafka.broker.health",
                        "broker-1.jvm.gc",
                        Monitors.Confidence.CONFIRMED,
                        Map.of(
                            "gcName", gc.getKeyProperty("name"),
                            "collectionTime", String.valueOf(collectionTime),
                            "collectionCount", String.valueOf(collectionCount)
                        )
                    ).withVectorClock(vectorClock.increment());

                    cortexClient.emit(gcSignal);
                }
            }

            // Request Rate Metrics
            ObjectName requestMetrics = new ObjectName(
                "kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec"
            );
            Double requestRate = (Double) mbeanConn.getAttribute(
                requestMetrics, "OneMinuteRate"
            );

            ServiceSignal serviceSignal = ServiceSignal.create(
                "kafka.broker.requests",
                "broker-1.messages-in",
                requestRate > 10000 ? Services.Signal.SUCCESS : Services.Signal.FAIL,
                Map.of("rate", String.format("%.2f", requestRate))
            ).withVectorClock(vectorClock.increment());

            cortexClient.emit(serviceSignal);

        } catch (Exception e) {
            // Log error, but don't stop polling
            System.err.println("Error collecting metrics: " + e.getMessage());
        }
    }
}
```

### Dockerfile for BrokerSensorAgent

```dockerfile
FROM eclipse-temurin:24-jdk-alpine

WORKDIR /app

# Copy sensor agent jar
COPY target/broker-sensor-agent.jar /app/

# JMX connection requires network access
ENV KAFKA_JMX_URL="service:jmx:rmi:///jndi/rmi://kafka:9999/jmxrmi"
ENV CORTEX_URL="http://cortex-runtime:8080"
ENV AGENT_ID="broker-sensor-1"

# Run sensor
CMD ["java", "--enable-preview", "-jar", "broker-sensor-agent.jar"]
```

---

## 3. AWS MSK Production Deployment

### Why Sidecar Won't Work for MSK

**MSK = Managed Service:**
- ❌ You don't control broker containers
- ❌ Can't add sidecar containers
- ❌ Can't modify broker configuration
- ❌ Limited JMX access (may not be exposed)

### Solution: External Sensor Services

```
┌─────────────────────────────────────────────────────────┐
│  AWS VPC                                                │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  MSK Cluster (Managed by AWS)                   │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐      │   │
│  │  │ Broker 1 │  │ Broker 2 │  │ Broker 3 │      │   │
│  │  │          │  │          │  │          │      │   │
│  │  │ JMX?     │  │ JMX?     │  │ JMX?     │      │   │
│  │  └─────┬────┘  └─────┬────┘  └─────┬────┘      │   │
│  └────────┼─────────────┼─────────────┼───────────┘   │
│           │             │             │               │
│           │ Kafka protocol (9092)     │               │
│           │                           │               │
│  ┌────────▼───────────────────────────▼───────────┐   │
│  │  ECS Fargate / EKS                             │   │
│  │                                                 │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │  BrokerSensorAgent (if JMX available)    │  │   │
│  │  │  OR BrokerStateInferrer (client-based)   │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                                                 │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │  PartitionSensorAgent                    │  │   │
│  │  │  (Uses Kafka Admin API)                  │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                                                 │   │
│  │  ┌──────────────────────────────────────────┐  │   │
│  │  │  Cortex Runtime                          │  │   │
│  │  │  (Substrates engine)                     │  │   │
│  │  └──────────────────────────────────────────┘  │   │
│  │                                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  RDS for RocksDB Alternative                    │   │
│  │  OR S3 + DynamoDB for event storage             │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  ElastiCache Redis                              │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Application VPC (Customer workloads)                   │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐                    │
│  │  Producer    │  │  Consumer    │                    │
│  │              │  │              │                    │
│  │  +Interceptor│  │  +Interceptor│                    │
│  └──────────────┘  └──────────────┘                    │
│         │                  │                           │
│         └──────────┬───────┘                           │
│                    │                                    │
│              ClientSensorAgent                          │
│              (embedded in clients)                      │
└─────────────────────────────────────────────────────────┘
```

### MSK Deployment Options

#### Option 1: JMX Available (Check MSK Configuration)

If MSK exposes JMX (uncommon, but check your MSK config):

```java
// BrokerSensorAgent connects remotely
String jmxUrl = "service:jmx:rmi:///jndi/rmi://b-1.msk-cluster.kafka.region.amazonaws.com:9999/jmxrmi";
BrokerSensorAgent agent = new BrokerSensorAgent(jmxUrl, cortexUrl, "broker-sensor-1");
```

**Deploy as ECS Fargate task:**
```yaml
# ECS Task Definition
{
  "family": "broker-sensor-agent",
  "containerDefinitions": [
    {
      "name": "broker-sensor",
      "image": "your-ecr/broker-sensor-agent:latest",
      "environment": [
        {
          "name": "KAFKA_JMX_URL",
          "value": "service:jmx:rmi:///jndi/rmi://b-1.msk-cluster:9999/jmxrmi"
        },
        {
          "name": "CORTEX_URL",
          "value": "http://cortex-runtime:8080"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/broker-sensor",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ],
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512"
}
```

#### Option 2: No JMX (Most Likely for MSK)

**Infer broker state from client behavior:**

```java
public class BrokerStateInferrer {
    private final CortexClient cortexClient;

    // Subscribe to client signals
    public void start() {
        cortexClient.subscribe("kafka.client.interactions", signal -> {
            if (signal instanceof ServiceSignal serviceSignal) {
                inferBrokerHealth(serviceSignal);
            }
        });
    }

    private void inferBrokerHealth(ServiceSignal clientSignal) {
        // Analyze client behavior patterns

        // Pattern: Multiple producer timeouts → broker issue
        List<ServiceSignal> recentFailures = getRecentFailures(1_000);  // last 1s

        if (recentFailures.size() > 10) {  // 10 failures in 1s
            // Infer broker degradation
            MonitorSignal inferredSignal = MonitorSignal.degraded(
                "kafka.broker.health",
                "broker-unknown.inferred",  // subject
                Monitors.Confidence.SUSPECTED,  // Lower confidence - inferred
                Map.of(
                    "inference", "high-client-failure-rate",
                    "failureCount", String.valueOf(recentFailures.size()),
                    "source", "client-behavior-pattern"
                )
            );

            cortexClient.emit(inferredSignal);
        }
    }
}
```

**This works without JMX!** We infer broker problems from client symptoms.

#### Option 3: Hybrid (Recommended for MSK)

1. **PartitionSensorAgent** - Kafka Admin API (no JMX)
   - Consumer lag
   - Partition metrics
   - Replication status

2. **ClientSensorAgent** - Kafka Interceptors (no JMX)
   - Producer send outcomes
   - Consumer poll behavior
   - Request latencies

3. **BrokerStateInferrer** - Pattern analysis (no JMX)
   - Infer broker health from client signals
   - Lower confidence, but still valuable

4. **(Optional) BrokerSensorAgent** - If JMX available
   - Direct broker metrics
   - High confidence signals

**70-80% of value achievable without broker JMX.**

---

## 4. Migration Path: Local → AWS

### Phase 1: Local PoC (Docker Compose)
```bash
# Week 1-2: Build and test locally
docker-compose up -d

# Verify all components:
# ✅ BrokerSensorAgent reads JMX
# ✅ PartitionSensorAgent reads lag
# ✅ ClientSensorAgent intercepts
# ✅ Cortex processes signals
# ✅ Patterns detect cascading failures
# ✅ Narratives construct automatically
# ✅ RocksDB stores for replay
```

### Phase 2: Containerize for AWS
```bash
# Build production images
docker build -t your-ecr/broker-sensor:latest ./sensor-agents
docker build -t your-ecr/partition-sensor:latest ./sensor-agents
docker build -t your-ecr/cortex-runtime:latest ./substrates-runtime
docker build -t your-ecr/replay-service:latest ./replay-service

# Push to ECR
aws ecr get-login-password | docker login --username AWS --password-stdin your-ecr
docker push your-ecr/broker-sensor:latest
# ... push others
```

### Phase 3: Deploy to ECS/EKS
```bash
# Option A: ECS Fargate (serverless)
aws ecs create-cluster --cluster-name kafka-obs
aws ecs register-task-definition --cli-input-json file://task-def.json
aws ecs create-service --cluster kafka-obs --service-name broker-sensor --task-definition broker-sensor

# Option B: EKS (Kubernetes)
kubectl apply -f k8s/broker-sensor-deployment.yaml
kubectl apply -f k8s/cortex-runtime-deployment.yaml
kubectl apply -f k8s/replay-service-deployment.yaml
```

### Phase 4: Configure Client Interceptors
```java
// In your MSK producer/consumer applications
Properties props = new Properties();
props.put("bootstrap.servers", "b-1.msk-cluster.kafka.us-east-1.amazonaws.com:9092");

// Add interceptor
props.put(
    ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
    "io.kafkaobs.sensors.client.ProducerSignalInterceptor"
);

// Configure interceptor
props.put("cortex.url", "http://cortex-runtime.kafka-obs.internal:8080");
props.put("agent.id", "producer-orders-service");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

### Phase 5: Verify End-to-End
```bash
# Trigger test scenario in MSK
aws kafka-cluster invoke-command --cluster-arn ... --command "./test-scenarios/producer-burst.sh"

# Query narratives
curl https://replay-service.your-domain.com/api/narratives/latest

# Should see:
# {
#   "situation": "Producer burst detected",
#   "causalChain": [...],
#   "rootCause": "...",
#   "recommendation": "..."
# }
```

---

## 5. Key Differences: Local vs AWS

| Aspect | Local (Docker Compose) | AWS MSK Production |
|--------|------------------------|-------------------|
| **Kafka** | Confluent container | AWS MSK (managed) |
| **JMX Access** | ✅ localhost:9999 | ❓ Check MSK config |
| **BrokerSensor** | Sidecar container | ECS/EKS task (remote) |
| **Deployment** | `docker-compose up` | ECS/EKS + Terraform |
| **Storage** | Local RocksDB volume | S3 + DynamoDB |
| **Networking** | Bridge network | VPC + Security Groups |
| **Persistence** | Docker volume | EBS/EFS/S3 |

---

## 6. Recommended Approach

### For PoC (Next 2-4 Weeks):

1. **Start with Docker Compose** ✅
   - Full control
   - JMX access guaranteed
   - Fast iteration
   - Test entire signal flow

2. **Build Core Components:**
   - BrokerSensorAgent (JMX → MonitorSignals)
   - PartitionSensorAgent (Admin API → QueueSignals)
   - ClientSensorAgent (Interceptor → ServiceSignals)
   - Cortex Runtime (Substrates + Observers)
   - Pattern Detectors (CascadingFailure, SlowConsumer)
   - Replay Service (RocksDB query API)

3. **Prove Concepts:**
   - VectorClock causal ordering works
   - Pattern detection catches cascading failures
   - Narratives construct automatically
   - Event replay reconstructs incidents

### For Production (Month 2-3):

1. **Check MSK JMX Availability**
   ```bash
   # Test if MSK exposes JMX
   aws kafka describe-cluster --cluster-arn ... | jq '.ClusterInfo.OpenMonitoring'
   ```

2. **Deploy Strategy Based on JMX:**
   - **If JMX available:** Deploy BrokerSensorAgent as ECS task
   - **If NO JMX:** Use BrokerStateInferrer + client-based monitoring

3. **Always Deploy:**
   - PartitionSensorAgent (Admin API works everywhere)
   - ClientSensorAgent (Interceptors in your apps)
   - Cortex Runtime (ECS/EKS)
   - Replay Service (ECS/EKS)

4. **Storage:**
   - RocksDB → S3 snapshots + EBS volumes
   - OR migrate to DynamoDB Streams for event sourcing

---

## 7. Startup Commands

### Local Development:
```bash
# Clone and build
git clone https://github.com/your-org/kafka-obs
cd kafka-obs
mvn clean package

# Start Docker Compose stack
docker-compose up -d

# Tail logs
docker-compose logs -f

# Run test scenario
./scripts/test-cascading-failure.sh

# Query results
curl http://localhost:8080/api/narratives/latest | jq

# Cleanup
docker-compose down -v
```

### AWS Production:
```bash
# Deploy infrastructure
cd terraform
terraform init
terraform apply

# Deploy services
aws ecs update-service --cluster kafka-obs --service cortex-runtime --force-new-deployment

# Monitor
aws logs tail /ecs/cortex-runtime --follow

# Query
curl https://api.kafka-obs.your-domain.com/narratives/latest
```

---

## Conclusion

**Local PoC Strategy:**
✅ Use Docker Compose with full JMX access
✅ Sidecar BrokerSensorAgent works perfectly
✅ Fast iteration, complete control

**AWS MSK Strategy:**
✅ Deploy sensor agents as separate ECS/EKS services
✅ Use Admin API + Interceptors (works without JMX)
✅ Optionally add remote JMX if MSK supports it
✅ 70% of value achievable without broker JMX

**The sidecar limitation only applies to MSK. For local development, sidecars are ideal.**

Start with Docker Compose, prove the concepts, then containerize for AWS deployment.

Ready to build the Docker Compose stack?
