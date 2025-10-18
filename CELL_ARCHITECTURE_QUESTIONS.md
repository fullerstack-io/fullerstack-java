# Cell Architecture Questions for Substrates M15+

## Context
I've successfully migrated fullerstack-substrates from API M13 to M15+ (all 269 tests passing). During this migration, I encountered architectural questions about the **Cell** component that I'd appreciate clarification on.

## Cell Understanding So Far

From the M15+ API, I understand:

```java
interface Cell<I, E> extends Pipe<I>, Container<Cell<I, E>, E>
```

**Key characteristics:**
- Implements `Pipe<I>` - can receive input of type I via `emit(I)`
- Extends `Container<Cell<I, E>, E>` - manages a pool of child Cells
- Performs **type transformation**: receives I, children emit E
- Differs from `Conduit<Pipe<E>, E>` which has no type transformation

**Critical insight about Composer:**
The `Composer<Pipe<I>, E>` passed to `Circuit.cell()` cannot create just a simple Pipe - since Cell implements BOTH Pipe<I> AND Container<Cell<I,E>, E>, the Composer must somehow create or compose Cells, not just Pipes. This is the source of my architectural questions.

## Reactive Programming Context

Through researching RxJava and reactive patterns, I recognize Cell's dual nature maps to **BehaviorSubject**:

### RxJava → Substrates Mapping

| Substrates Concept | RxJava Equivalent | Description |
|-------------------|-------------------|-------------|
| **Cell** | `BehaviorSubject` | Holds state and replays to new subscribers |
| **Pipe** | `map()`, `filter()`, operators | Transforms or filters data stream |
| **Channel** | `Subject` | Connects producers and consumers |
| **Conduit** | `ConnectableObservable` | Hot-emitting transport for shared streams |
| **Source** | `Observable` / `Publisher` | Upstream emitter of values/events |
| **Subscriber** | `Observer` | Consumes data and reacts to updates |

### Understanding Cell as BehaviorSubject

**RxJava's BehaviorSubject:**
- IS-A `Observable` (can be subscribed to - producers emit to it)
- IS-A `Observer` (can receive values - consumers subscribe to it)
- Stores current state
- Replays last value to new subscribers

**Substrates' Cell:**
- IS-A `Pipe<I>` (receives input - like Observer)
- IS-A `Container<Cell<I,E>, E>` (manages children that emit - like Observable)
- Performs type transformation I → E
- Children receive emissions and emit transformed results

### Type Transformation Pattern

This pattern mirrors RxJava's operator chaining:

```java
// RxJava pattern
BehaviorSubject<SensorReading> sensor = BehaviorSubject.create();
Observable<Alert> alerts = sensor.map(reading -> toAlert(reading));

// Substrates pattern
Cell<SensorReading, Alert> cell = circuit.cell(
    composer,  // The transformation logic (reading → alert)
    flow -> flow.map(...)
);
```

**What I understand:**
- Cell's dual nature (Pipe + Container) matches reactive Subject pattern
- The I → E transformation is analogous to RxJava's `.map()` operator
- Composer provides the transformation logic

**What I'm uncertain about:**
- How Composer creates objects that satisfy both Pipe<I> AND Cell<I,E> interfaces
- Whether there's a Cell-specific Composer pattern I'm missing

## Implementation Questions

### Question 1: Is there a Composer.cell() method?

I noticed the pattern:
- `Composer.pipe()` creates `Composer<Pipe<E>, E>`
- `Composer.channel()` creates `Composer<Channel<E>, E>`

**Question:** Is there a `Composer.cell()` that creates `Composer<Cell<I,E>, E>`?

If so, that would resolve my implementation questions!

### Question 2: How should Cell.get() create child Cells?

My current implementation:
```java
public Cell<I, E> get(Name name) {
    return childCells.computeIfAbsent(name, n -> {
        Channel<E> channel = new ChannelImpl<>(name, scheduler, source, flowConfigurer);
        Pipe<I> pipe = composer.compose(channel);

        // This cast assumes composer returns something that IS-A Cell<I,E>
        @SuppressWarnings("unchecked")
        Cell<I, E> childCell = (Cell<I, E>) pipe;

        return childCell;
    });
}
```

**The architectural question:**

Since Cell<I,E> extends BOTH Pipe<I> AND Container<Cell<I,E>, E>, the Composer<Pipe<I>, E> cannot just create a simple Pipe - it must create something that implements both interfaces.

**Possible interpretations:**
1. The Composer is expected to return a **Cell<I,E>** (which IS-A Pipe<I>)
2. There's a Cell-specific composer (Composer.cell()?) that explicitly creates Cells
3. Cell.get() should create child Cells directly (like cell division) rather than via Composer

**Current uncertainty:** I'm casting `Pipe<I> → Cell<I, E>`, which works at runtime but suggests I don't understand the intended creation pattern.

### Question 3: What is Cell's intended use case?

I'm building a Kafka cluster observability framework. Based on the RxJava mapping, I see Cell as ideal for **stateful type transformation** scenarios.

**My use case - Kafka metrics monitoring:**

```java
// Transform raw Kafka metrics into alerts
Cell<KafkaMetric, Alert> monitoringCell = circuit.cell(
    composer,
    flow -> flow
        .filter(metric -> metric.value() > threshold)
        .map(metric -> Alert.create(metric))
);

// Child cells for different metric types
Cell<KafkaMetric, Alert> cpuMonitor = monitoringCell.get(name("cpu"));
Cell<KafkaMetric, Alert> lagMonitor = monitoringCell.get(name("lag"));

// Emit metrics (type I)
cpuMonitor.emit(new KafkaMetric("cpu.usage", 95.0));

// Subscribe to transformed alerts (type E)
monitoringCell.source().subscribe(subscriber(
    name("alert-handler"),
    (subject, registrar) -> registrar.register(alert -> handleAlert(alert))
));
```

**Pattern I see:**
- **Input (I)**: Raw metrics from Kafka JMX
- **Transformation**: Filter thresholds, enrich with context, categorize severity
- **Output (E)**: Structured alerts with metadata
- **Container role**: Each child Cell monitors a specific metric type

**Is this the intended pattern?** Type transformation with hierarchical organization?

### Question 4: How do Cells compose hierarchically?

Given:
```java
Cell<KafkaMetric, Alert> cell = circuit.cell(composer);
Cell<KafkaMetric, Alert> child = cell.get(name("cpu-monitor"));
```

When `cell.emit(metric)` is called:
1. Does it propagate to ALL children? (my current impl)
2. Should children be independent Cells or wrapped Pipes?
3. How does the I → E transformation actually occur?

## My Implementation

**File:** `fullerstack-substrates/src/main/java/io/fullerstack/substrates/cell/CellImpl.java`

**Current behavior:**
- Cell.emit(I) → broadcasts to all child Cells
- Each child Cell receives I
- Children use Composer to transform I → E
- Results emitted to shared Source<E>

**Uncertainty:** The Composer creates Pipe<I>, which I cast to Cell<I, E>. This works at runtime but I know is incorrect but I just did it to migrate the rest of the API.

## Request

Could you provide:
1. **Intended usage examples** for Cell
2. **Clarification** on Cell.get() semantics
3. **Guidance** on Composer's role with Cells
4. Any **test examples** from Substrates codebase showing Cell usage

## Migration Status

✅ **269 tests passing**
✅ All M15+ features implemented (Flow, Consumer<Flow>, Circuit.cell(), etc.)
✅ Code compiles successfully
⚠️  Cell implementation has architectural questions

---

**Repository:** https://github.com/fullerstack-io/fullerstack-java
**Branch:** main
**Commit:** 8ec048b - "feat: Migrate fullerstack-substrates to Substrates API M15+"
