# Kafka Observability Demo

Production-realistic Kafka observability using Humainary Substrates API with JMX monitoring.

## Run

```bash
# Start Kafka cluster
docker-compose up -d

# Wait for brokers (30 seconds)
sleep 30

# Build
mvn clean package -DskipTests

# Terminal 1: Monitoring (WebSocket dashboard + JMX metrics collector)
java -cp target/kafka-observability-demo.jar \
  io.fullerstack.kafka.demo.KafkaObservabilityDemoApplication

# Terminal 2: Producer (with JMX enabled)
java -Dcom.sun.management.jmxremote.port=11001 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Dcom.sun.management.jmxremote.rmi.port=11001 \
     -cp target/kafka-observability-demo.jar \
     io.fullerstack.kafka.demo.StandaloneProducer

# Open WebSocket dashboard
open http://localhost:8080
```

## What it does

- **Monitoring app**: Collects JMX metrics from producer, emits Substrates signals (Queue/Gauge/Counter), broadcasts to WebSocket dashboard
- **Producer app**: Sends 10 msg/sec to Kafka, exposes JMX metrics on port 11001
- **Dashboard**: Real-time signal visualization at http://localhost:8080

## Signals

- `Queue.PUT` / `Queue.OVERFLOW` - Buffer utilization
- `Gauge.INCREMENT` / `Gauge.DECREMENT` - Buffer size changes
- `Counter.INCREMENT` - Exhaustion events

## Environment

```bash
KAFKA_BOOTSTRAP=localhost:9092,localhost:9093,localhost:9094  # Kafka brokers
JMX_URL=localhost:11001                                       # Producer JMX port
DASHBOARD_PORT=8080                                           # WebSocket dashboard port
```

## Cleanup

```bash
docker-compose down
```
