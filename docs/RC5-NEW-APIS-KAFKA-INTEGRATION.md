# RC5 New APIs - Kafka Integration Analysis

## Overview

Serventis RC5 introduces three new instrument APIs that complement our existing Kafka monitoring infrastructure:

1. **Caches API** - Cache interaction tracking
2. **Counters API** - Monotonic counter tracking
3. **Gauges API** - Bidirectional metric tracking

This document analyzes how these APIs integrate with Kafka observability.

## Existing Kafka Monitoring (RC1 APIs)

### Current Instrument Usage

| API | Use Case | Example |
|-----|----------|---------|
| **Queues** | Producer buffer, Consumer lag | `ProducerBufferMonitor` |
| **Services** | Producer/Consumer lifecycle | `ProducerEventInterceptor` |
| **Resources** | Thread pool capacity | `ThreadPoolResourceMonitor` |
| **Monitors** | Health assessment | `ThreadPoolResourceMonitor` |

### Signal Flow Pattern

```
JMX Metrics → Sensor/Monitor → Instrument Method → Signal Emission
```

**Example**: `ProducerBufferMonitor`
```java
// 1. Collect JMX metrics
long availableBytes = getJmxMetric("buffer-available-bytes");
double utilization = 1.0 - (availableBytes / totalBytes);

// 2. Call instrument method based on state
if (utilization >= 0.95) {
    bufferQueue.overflow((long)(utilization * 100));  // OVERFLOW signal
} else if (utilization >= 0.80) {
    bufferQueue.put((long)(utilization * 100));       // PUT with pressure
} else {
    bufferQueue.put();                                 // Normal PUT
}
```

## New RC5 APIs - Kafka Use Cases

### 1. Caches API - Kafka Query Cache Monitoring

**Kafka Use Case**: Metadata cache, consumer group cache, topic configuration cache

**Signals**:
- `LOOKUP` - Cache access attempt
- `HIT` / `MISS` - Cache hit ratio tracking
- `STORE` - Cache population
- `EVICT` - LRU eviction due to capacity
- `EXPIRE` - TTL expiration
- `REMOVE` - Explicit invalidation

**Integration Example**: `MetadataCacheMonitor`

```java
public class MetadataCacheMonitor {
    private final Cache metadataCache;

    public void monitorMetadataAccess(String topic) {
        metadataCache.lookup();

        TopicMetadata metadata = cache.get(topic);
        if (metadata != null) {
            metadataCache.hit();      // Cache hit
        } else {
            metadataCache.miss();     // Cache miss
            metadata = fetchFromBroker(topic);
            cache.put(topic, metadata);
            metadataCache.store();    // Cache population
        }
    }

    public void onCacheEviction(String topic) {
        metadataCache.evict();        // LRU eviction
    }

    public void onTTLExpiration(String topic) {
        metadataCache.expire();       // TTL expiration
    }
}
```

**Observability Value**:
- Track cache hit ratio → Optimize cache size
- Detect cache thrashing → Eviction patterns
- Identify hot topics → Cache optimization targets

---

### 2. Counters API - Kafka Cumulative Metrics

**Kafka Use Case**: Request counts, byte counters, message counts

**Signals**:
- `INCREMENT` - Counter increased
- `OVERFLOW` - Counter wrapped (rare with Long)
- `UNDERFLOW` - Invalid decrement attempt (error detection)
- `RESET` - Daily/hourly rollover

**Integration Example**: `MessageCounterMonitor`

```java
public class MessageCounterMonitor {
    private final Counter messagesSent;
    private final Counter bytesSent;

    public void onMessageSent(int messageSize) {
        messagesSent.increment();     // Message count
        bytesSent.increment();        // Bytes sent (could increment by size)
    }

    public void onDailyRollover() {
        messagesSent.reset();         // Reset daily counter
        bytesSent.reset();
    }

    public void onCounterOverflow() {
        messagesSent.overflow();      // Counter wrapped (Long.MAX_VALUE)
    }
}
```

**Why Counters > Gauges for Kafka**:
- ✅ **Counters**: Messages sent, bytes produced (never decrease)
- ❌ **Gauges**: Not suitable - these metrics are monotonic

**Observability Value**:
- Track cumulative throughput
- Detect counter overflow (architectural attention needed)
- Calculate rates via observer agents (messages/sec)

---

### 3. Gauges API - Kafka Bidirectional Metrics

**Kafka Use Case**: Active connections, in-flight requests, partition reassignments

**Signals**:
- `INCREMENT` - Metric increased
- `DECREMENT` - Metric decreased
- `OVERFLOW` - Exceeded maximum capacity
- `UNDERFLOW` - Fell below minimum
- `RESET` - Reset to baseline

**Integration Example**: `ConnectionPoolGaugeMonitor`

```java
public class ConnectionPoolGaugeMonitor {
    private final Gauge activeConnections;
    private final Gauge inflightRequests;

    public void onConnectionEstablished() {
        activeConnections.increment();

        if (getConnectionCount() >= maxConnections) {
            activeConnections.overflow();  // Hit capacity limit
        }
    }

    public void onConnectionClosed() {
        activeConnections.decrement();
    }

    public void onRequestSent() {
        inflightRequests.increment();

        if (getInflightCount() >= maxInflightRequests) {
            inflightRequests.overflow();   // Backpressure signal
        }
    }

    public void onResponseReceived() {
        inflightRequests.decrement();
    }

    public void onPoolReset() {
        activeConnections.reset();         // Pool recycled
        inflightRequests.reset();
    }
}
```

**Why Gauges > Counters for Kafka**:
- ✅ **Gauges**: Connections, in-flight requests (bidirectional)
- ❌ **Counters**: Not suitable - these metrics increase AND decrease

**Observability Value**:
- Track resource utilization (connections, requests)
- Detect capacity saturation (OVERFLOW signals)
- Identify resource depletion (UNDERFLOW signals)

---

## Integration Architecture

### Sensor Layer Structure

```
┌─────────────────────────────────────────────────────────────┐
│                    Kafka JMX Metrics                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Sensor/Monitor Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  RC1 APIs    │  │  RC5 APIs    │  │  Future      │     │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤     │
│  │ Queues       │  │ Caches       │  │ ...          │     │
│  │ Services     │  │ Counters     │  │              │     │
│  │ Resources    │  │ Gauges       │  │              │     │
│  │ Monitors     │  │              │  │              │     │
│  │ Probes       │  │              │  │              │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Substrates Circuit Layer                     │
│  ┌──────────────────────┐  ┌──────────────────────┐        │
│  │ QueueFlowCircuit     │  │ CacheFlowCircuit     │        │
│  │ (existing)           │  │ (NEW RC5)            │        │
│  └──────────────────────┘  └──────────────────────┘        │
│  ┌──────────────────────┐  ┌──────────────────────┐        │
│  │ CounterFlowCircuit   │  │ GaugeFlowCircuit     │        │
│  │ (NEW RC5)            │  │ (NEW RC5)            │        │
│  └──────────────────────┘  └──────────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

### Circuit Implementation Pattern

All new RC5 circuits follow the proven `QueueFlowCircuit` pattern:

```java
public class XxxFlowCircuit implements AutoCloseable {
    private final Cortex cortex;
    private final Circuit circuit;
    private final Conduit<Instrument, Sign> conduit;

    public XxxFlowCircuit() {
        this.cortex = cortex();
        this.circuit = cortex.circuit(cortex.name("xxx.flow"));
        this.conduit = circuit.conduit(
            cortex.name("xxx-monitoring"),
            Xxxs::composer  // Method reference to Serventis composer
        );
    }

    public Instrument xxxFor(String entityName) {
        return conduit.get(cortex.name(entityName));
    }

    public void subscribe(String name,
        BiConsumer<Subject<Channel<Sign>>, Registrar<Sign>> subscriber) {
        conduit.subscribe(cortex.subscriber(cortex.name(name), subscriber));
    }

    @Override
    public void close() {
        circuit.close();
    }
}
```

## Recommended Implementation Priorities

### Phase 1: Gauges API (Highest Value)
**Why First**:
- Directly applicable to Kafka connection pools
- Complements existing Queue/Resource APIs
- Enables capacity saturation detection

**Targets**:
1. `ConnectionPoolGaugeMonitor` - Broker/Producer/Consumer connections
2. `InflightRequestGaugeMonitor` - Request pipelining tracking
3. `PartitionReassignmentGaugeMonitor` - Cluster rebalancing

### Phase 2: Counters API (Medium Value)
**Why Second**:
- Cumulative metrics foundation
- Rate calculation via observer agents
- Overflow detection for architectural review

**Targets**:
1. `MessageCounterMonitor` - Messages sent/received
2. `ByteCounterMonitor` - Bytes produced/consumed
3. `ErrorCounterMonitor` - Error accumulation

### Phase 3: Caches API (Lower Priority)
**Why Third**:
- Less common in Kafka core (more in client applications)
- Useful for metadata caching optimization
- Nice-to-have for advanced scenarios

**Targets**:
1. `MetadataCacheMonitor` - Topic/partition metadata
2. `ConsumerGroupCacheMonitor` - Group coordinator cache
3. `TopicConfigCacheMonitor` - Configuration cache

## Migration Path

### Step 1: Add Circuit Classes ✅
- [x] `CacheFlowCircuit` created
- [x] `CounterFlowCircuit` created
- [x] `GaugeFlowCircuit` created

### Step 2: Create Sensor/Monitor Classes
- [ ] `ConnectionPoolGaugeMonitor` (Gauges)
- [ ] `InflightRequestGaugeMonitor` (Gauges)
- [ ] `MessageCounterMonitor` (Counters)
- [ ] `ByteCounterMonitor` (Counters)
- [ ] `MetadataCacheMonitor` (Caches)

### Step 3: Integration Tests
- [ ] Test gauge increment/decrement patterns
- [ ] Test counter monotonic accumulation
- [ ] Test cache hit/miss ratio tracking
- [ ] Test boundary violations (overflow/underflow)

### Step 4: Wire Into Runtime
- [ ] Add new circuits to `KafkaObsConfig`
- [ ] Initialize monitors in runtime startup
- [ ] Subscribe observers for signal processing

## Key Differences: RC1 vs RC5 APIs

| Aspect | RC1 (Queues, Services) | RC5 (Caches, Counters, Gauges) |
|--------|------------------------|--------------------------------|
| **Pattern** | Same - method-based emission | Same - method-based emission |
| **Composer** | `Queues::composer` | `Caches::composer`, etc. |
| **Subject** | In Channel, not Signal | In Channel, not Signal |
| **Signals** | Record types (Sign + units) | Enum types (just Sign) |
| **Usage** | `queue.overflow(95L)` | `cache.hit()`, `counter.increment()` |

**No breaking changes** - New APIs follow same RC1 pattern we're already using!

## Conclusion

The RC5 APIs provide natural extensions to our existing Kafka monitoring:

- **Gauges** → Connection pools, in-flight requests (bidirectional metrics)
- **Counters** → Message/byte totals (monotonic accumulation)
- **Caches** → Metadata caching (hit ratio optimization)

All three follow the proven RC1 pattern with Circuits, Conduits, and Instrument method calls. Integration is straightforward and non-breaking.

**Recommendation**: Start with **Gauges API** for connection pool monitoring as it provides immediate value for capacity management and complements existing Queue-based buffer monitoring.
