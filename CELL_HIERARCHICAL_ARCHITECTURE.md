# Cell Hierarchical Architecture - Substrates API M15+

## Executive Summary

**Cell enables hierarchical reactive observability trees** by nesting Cells within Cells. Each Cell represents an observable dimension (e.g., cluster → broker → partition), with child Cells representing more granular observables. This creates a powerful composable architecture for building complex monitoring topologies like Kafka cluster observability.

---

## Core Architecture

### API Signature

```java
interface Cell<I, E> extends Pipe<I>, Container<Cell<I, E>, E>
//                                    ^^^^^^^^^^^^^^^^^^^^
//                                    Container of CHILD CELLS
```

**Key characteristics:**
- `Pipe<I>` - receives input of type I via `emit(I)`
- `Container<Cell<I, E>, E>` - **manages child Cells of the same Cell<I, E> type**
- Performs **type transformation**: receives I, transforms to E, children emit E
- **Hierarchical**: Child Cells can have their own children (recursive structure)

### Implementation Evidence

```java
// CellImpl.java:48
public class CellImpl<I, E> implements Cell<I, E> {
    private final Map<Name, Cell<I, E>> childCells;  // ← Holds CHILD CELLS
    private final Source<E> source;                   // ← Shared Source for all children
    private final Composer<Pipe<I>, E> composer;      // ← Creates child Cells
}
```

**Emission Flow:**
1. Parent Cell receives `I` via `emit(I)`
2. Parent broadcasts `I` to all child Cells (line 106-108)
3. Each child Cell transforms `I → E` via TransformingPipe
4. Transformed `E` emitted to parent's shared `Source<E>`
5. Subscribers receive aggregated emissions from all children

---

## Hierarchical Kafka Observability Example

### Use Case: Kafka Cluster Monitoring

```
Kafka Cluster Cell<KafkaMetric, Alert>
  ├─ Broker-1 Cell<KafkaMetric, Alert>
  │    ├─ Partition-0 Cell<KafkaMetric, Alert>
  │    ├─ Partition-1 Cell<KafkaMetric, Alert>
  │    └─ Partition-2 Cell<KafkaMetric, Alert>
  ├─ Broker-2 Cell<KafkaMetric, Alert>
  │    ├─ Partition-0 Cell<KafkaMetric, Alert>
  │    └─ Partition-1 Cell<KafkaMetric, Alert>
  └─ Broker-3 Cell<KafkaMetric, Alert>
       └─ Partition-0 Cell<KafkaMetric, Alert>
```

### Implementation

```java
// Create top-level cluster Cell
Cell<KafkaMetric, Alert> clusterCell = circuit.cell(
    CellComposer.typeTransforming(
        circuit,
        registryFactory,
        metric -> Alert.fromMetric(metric)  // KafkaMetric → Alert
    ),
    flow -> flow.guard(alert -> alert.severity() > WARN)
);

// Create broker-level Cells (children of cluster)
Cell<KafkaMetric, Alert> broker1 = clusterCell.get(name("broker-1"));
Cell<KafkaMetric, Alert> broker2 = clusterCell.get(name("broker-2"));
Cell<KafkaMetric, Alert> broker3 = clusterCell.get(name("broker-3"));

// Create partition-level Cells (children of brokers)
Cell<KafkaMetric, Alert> broker1Partition0 = broker1.get(name("partition-0"));
Cell<KafkaMetric, Alert> broker1Partition1 = broker1.get(name("partition-1"));
Cell<KafkaMetric, Alert> broker1Partition2 = broker1.get(name("partition-2"));

// Subscribe to cluster-level alerts (aggregated from all children)
clusterCell.source().subscribe(subscriber(
    name("cluster-alerting"),
    (subject, registrar) -> registrar.register(alert -> handleClusterAlert(alert))
));

// Emit metric to specific partition
broker1Partition0.emit(new KafkaMetric("lag", 1000));
// → Transforms to Alert
// → Emitted to broker1's Source
// → Emitted to clusterCell's Source
// → Subscriber receives alert
```

### Broadcast Behavior

```java
// Emit to parent broadcasts to ALL children
broker1.emit(new KafkaMetric("cpu", 95.0));
// → broker1Partition0 receives metric
// → broker1Partition1 receives metric
// → broker1Partition2 receives metric
// → Each transforms KafkaMetric → Alert
// → All alerts flow to broker1's Source
```

---

## Cell vs Conduit vs Circuit

| Component | Contains | Purpose | Type Transform |
|-----------|----------|---------|----------------|
| **Cell<I, E>** | Child Cells (`Cell<I, E>`) | Hierarchical type transformation | Yes (I → E) |
| **Conduit<Pipe<E>, E>** | Pipes (`Pipe<E>`) | Fan-out without transformation | No (E → E) |
| **Circuit** | Conduits & Cells | Execution context (queue, scheduler) | N/A (orchestration) |

### When to Use Each

**Use Cell when:**
- You need **hierarchical observables** (cluster → broker → partition)
- You need **type transformation** (KafkaMetric → Alert)
- You want **parent-to-children broadcast** semantics
- Example: Multi-level monitoring topologies

**Use Conduit when:**
- You need **fan-out** without hierarchy (multiple listeners to same stream)
- **No type transformation** needed (Alert → Alert)
- Example: Multiple alert handlers consuming same alerts

**Use Circuit when:**
- You need **execution orchestration** (FIFO ordering, backpressure)
- You're managing multiple Conduits/Cells in a domain
- Example: Kafka observability domain with multiple monitoring streams

---

## Hierarchical Cell Patterns

### Pattern 1: Geographic Hierarchy

```java
// Multi-region Kafka deployment
Cell<KafkaMetric, Alert> globalCell = circuit.cell(composer);

Cell<KafkaMetric, Alert> usEastRegion = globalCell.get(name("us-east"));
Cell<KafkaMetric, Alert> usWestRegion = globalCell.get(name("us-west"));
Cell<KafkaMetric, Alert> euRegion = globalCell.get(name("eu"));

Cell<KafkaMetric, Alert> usEastCluster1 = usEastRegion.get(name("cluster-1"));
Cell<KafkaMetric, Alert> usEastCluster2 = usEastRegion.get(name("cluster-2"));

// Emit to region broadcasts to all clusters
usEastRegion.emit(new KafkaMetric("network.latency", 500));
```

### Pattern 2: Metric Type Hierarchy

```java
// Organize by metric type
Cell<KafkaMetric, Alert> metricsRoot = circuit.cell(composer);

Cell<KafkaMetric, Alert> performanceMetrics = metricsRoot.get(name("performance"));
Cell<KafkaMetric, Alert> reliabilityMetrics = metricsRoot.get(name("reliability"));

Cell<KafkaMetric, Alert> cpuMetrics = performanceMetrics.get(name("cpu"));
Cell<KafkaMetric, Alert> memoryMetrics = performanceMetrics.get(name("memory"));

Cell<KafkaMetric, Alert> lagMetrics = reliabilityMetrics.get(name("lag"));
Cell<KafkaMetric, Alert> replicationMetrics = reliabilityMetrics.get(name("replication"));

// Emit CPU metric - only flows through performance hierarchy
cpuMetrics.emit(new KafkaMetric("cpu.usage", 95.0));
```

### Pattern 3: Time-Series Aggregation

```java
// Hierarchical time windows
Cell<KafkaMetric, Alert> timeSeriesRoot = circuit.cell(composer);

Cell<KafkaMetric, Alert> minuteWindow = timeSeriesRoot.get(name("1min"));
Cell<KafkaMetric, Alert> hourWindow = timeSeriesRoot.get(name("1hour"));
Cell<KafkaMetric, Alert> dayWindow = timeSeriesRoot.get(name("1day"));

// Each window can apply different Flow transformations
Cell<KafkaMetric, Alert> minuteAggregated = circuit.cell(
    composer,
    flow -> flow.reduce(Alert.NONE, (a1, a2) -> Alert.max(a1, a2))
);
```

---

## Type Transformation in Hierarchical Cells

### How I → E Transformation Works

```java
// Each Cell in hierarchy has same transformer
Composer<Pipe<I>, E> composer = CellComposer.typeTransforming(
    circuit,
    registryFactory,
    metric -> new Alert(metric)  // KafkaMetric → Alert
);

// All Cells use this composer
Cell<KafkaMetric, Alert> parent = circuit.cell(composer);
Cell<KafkaMetric, Alert> child = parent.get(name("child"));
Cell<KafkaMetric, Alert> grandchild = child.get(name("grandchild"));

// Transformation happens at each level
grandchild.emit(kafkaMetric);
// → TransformingPipe transforms KafkaMetric → Alert
// → Alert emitted to child's Source
// → Alert emitted to parent's Source
// → Subscribers receive Alert
```

### Recursive Composer Pattern

```java
// CellComposer.java:70-86
public static <I, E> Composer<Pipe<I>, E> typeTransforming(...) {
    return channel -> {
        Pipe<I> transformingPipe = new TransformingPipe<>(
            channel.pipe(),
            transformer  // I → E
        );

        return new CellImpl<>(
            channel.subject().name(),
            typeTransforming(scheduler, registryFactory, transformer),  // ← Recursive!
            //               ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            //               Child Cells get same transformer
            scheduler,
            registryFactory,
            null
        );
    };
}
```

**This ensures:**
- All child Cells use same I → E transformation
- Consistent type transformation across hierarchy
- Proper type safety (`Cell<I, E>` all the way down)

---

## Flow Transformations (E → E) vs Type Transformation (I → E)

### Two Levels of Transformation

```java
Cell<KafkaMetric, Alert> cell = circuit.cell(
    // 1. Type transformation (I → E) - happens in Composer
    CellComposer.typeTransforming(
        circuit,
        registryFactory,
        metric -> new Alert(metric)  // KafkaMetric → Alert
    ),

    // 2. Flow transformation (E → E) - happens after type transformation
    flow -> flow
        .guard(alert -> alert.severity() > WARN)      // Filter alerts
        .replace(alert -> alert.withTimestamp(now())) // Transform Alert → Alert
        .limit(100)                                    // Rate limiting
);
```

**Execution order:**
1. `Cell.emit(KafkaMetric)` - receives type I
2. **Type transformation**: `KafkaMetric → Alert` (in TransformingPipe)
3. **Flow transformations**: `Alert → Alert` (guard, replace, limit)
4. Emit to `Source<Alert>`

---

## Hierarchical Emission Semantics

### Upward Emission (Leaf → Root)

```java
// Emit at leaf level - flows UP to root
leaf.emit(metric);
// → Transforms to Alert in leaf
// → Emitted to parent's Source
// → Emitted to grandparent's Source
// → Emitted to root's Source
```

### Downward Broadcast (Root → Leaves)

```java
// Emit at parent level - broadcasts DOWN to children
parent.emit(metric);
// → Broadcasts to child1, child2, child3
// → Each child transforms metric → alert
// → Each child emits to parent's Source
// → All alerts aggregated at parent's Source
```

### Sibling Independence

```java
// Siblings don't communicate directly
broker1.emit(metric);  // Only affects broker1's children
broker2.emit(metric);  // Only affects broker2's children

// Both emit to shared parent Source
clusterCell.source().subscribe(...);  // Receives from all brokers
```

---

## Best Practices

### 1. Organize by Domain Hierarchy

```java
// Good - matches business domain
Cluster → Broker → Partition → Topic
Region → Zone → Cluster → Broker
```

### 2. Use Shared Source for Aggregation

```java
// Subscribe at parent level to aggregate children
parentCell.source().subscribe(subscriber(
    name("aggregated-monitoring"),
    (subject, registrar) -> registrar.register(alert -> aggregateAlert(alert))
));
```

### 3. Apply Flow Transformations at Appropriate Levels

```java
// Global filtering at root
Cell<Metric, Alert> root = circuit.cell(
    composer,
    flow -> flow.guard(alert -> alert.severity() >= ERROR)  // Only critical alerts
);

// Specific filtering at leaves
Cell<Metric, Alert> cpuCell = root.get(name("cpu"));
cpuCell = circuit.cell(
    composer,
    flow -> flow.guard(alert -> alert.value() > 90)  // CPU-specific threshold
);
```

### 4. Name Cells Hierarchically

```java
// Hierarchical naming
Name clusterName = name("kafka-cluster-1");
Name brokerName = clusterName.name("broker-2");
Name partitionName = brokerName.name("partition-0");

Cell<Metric, Alert> cluster = circuit.cell(composer);
Cell<Metric, Alert> broker = cluster.get(name("broker-2"));
Cell<Metric, Alert> partition = broker.get(name("partition-0"));
```

---

## Summary

| Aspect | Description |
|--------|-------------|
| **Structure** | Cell contains child Cells (recursive hierarchy) |
| **Purpose** | Hierarchical reactive observability trees |
| **Type Transform** | I → E transformation at each level |
| **Emission** | Upward (leaf → root) and downward (parent → children) |
| **Aggregation** | Parent's Source receives emissions from all children |
| **Use Case** | Multi-level monitoring (cluster → broker → partition) |

**Key Insight:** Cells enable **compositional observability** - build complex monitoring topologies by nesting Cells, each representing a granular observable dimension, with type-safe transformation and aggregation throughout the hierarchy.

---

## References

- Substrates API M15+: `io.humainary.substrates.api.Substrates.Cell`
- Implementation: `io.fullerstack.substrates.cell.CellImpl`
- Type Transformation: `io.fullerstack.substrates.pipe.TransformingPipe`
- Composer Pattern: `io.fullerstack.substrates.functional.CellComposer`
