# Substrates 101 Architecture Alignment

**Date**: 2025-10-11
**Article**: https://humainary.io/blog/observability-x-substrates-101/
**Status**: ⚠️ MOSTLY ALIGNED (1 architectural issue)

## Summary

Our implementation is mostly aligned with the Substrates 101 architecture overview. The component hierarchy, relationships, and data flow patterns are correctly implemented. However, there is one known architectural issue where Conduits create their own queues instead of using the Circuit's single queue (documented separately).

## Article's Core Architecture

### 1. ✅ Component Hierarchy

**Article States:**
> "Cortex → Circuit → Conduit → Channel → Pipe"

**Our Implementation:**

```java
// 1. Cortex (Bootstrap)
Cortex cortex = new CortexRuntime();

// 2. Circuit (Factory for Conduits)
Circuit circuit = cortex.circuit(cortex.name("app"));

// 3. Conduit (Named set of Channels)
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("metrics"),
    Composer.pipe()
);

// 4. Channel (Managed by Conduit, one per Name)
// Created internally by Conduit.get(name)
Channel<Long> channel = new ChannelImpl<>(name, queue);

// 5. Pipe (Percept wrapping Channel)
Pipe<Long> pipe = conduit.get(cortex.name("cpu"));
pipe.emit(85L);
```

**Verification**: ✅ Correct - Complete hierarchy implemented

---

### 2. ✅ Cortex: Bootstrap Class

**Article States:**
> "Cortex - Bootstrap class for Substrates runtime"
> "Entry point for creating Circuits"

**Our Implementation:**

```java
// CortexRuntime.java - Implements Cortex interface
public class CortexRuntime implements Cortex {
    private final Map<Name, Circuit> circuits = new ConcurrentHashMap<>();
    private final Scope defaultScope;

    // Circuit factory methods
    @Override
    public Circuit circuit() {
        return circuit(NameImpl.of("default"));
    }

    @Override
    public Circuit circuit(Name name) {
        return circuits.computeIfAbsent(name, this::createCircuit);
    }

    private Circuit createCircuit(Name name) {
        return new CircuitImpl(name);
    }

    // Additional factory methods: name(), pool(), scope(), state(), sink(), slot(), subscriber(), capture()
}
```

**Key Features:**
- Manages Circuit instances by name
- Provides factory methods for all Substrates components
- Caches circuits to ensure single instance per name

**Verification**: ✅ Correct - Full Cortex implementation with 38 methods

---

### 3. ✅ Circuit: Resource Scaling Management

**Article States:**
> "Circuit - Factory for creating Conduits"
> "Manages work Queues"
> "Controls underlying queued pipeline"
> "Supports multiple Conduits and parallelism"

**Our Implementation:**

```java
// CircuitImpl.java
public class CircuitImpl implements Circuit {
    private final Subject circuitSubject;
    private final Source<State> stateSource;
    private final Queue queue;  // ← Single Queue per Circuit
    private final Map<Name, Clock> clocks = new ConcurrentHashMap<>();
    private final Map<Name, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();

    public CircuitImpl(Name name) {
        this.circuitSubject = new SubjectImpl(...);
        this.stateSource = new SourceImpl<>(name);
        this.queue = new QueueImpl();  // ← Circuit's Queue
    }

    @Override
    public Queue queue() {
        return queue;
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
        return conduits.computeIfAbsent(name, n ->
            new ConduitImpl<>(circuitSubject.name(), n, composer)
        );
    }
}
```

**Key Features:**
- Creates and manages Conduits
- Provides single Queue for coordination
- Caches Conduits by name
- Manages Clock instances

**Verification**: ✅ Correct - Circuit as factory and coordinator

---

### 4. ✅ Conduit: Named Set of Channels

**Article States:**
> "Conduit - Named set of Channels with same data type"
> "Creates and manages Channels"
> "Supports registering Subscribers"
> "Only one Channel per Name in a Conduit"

**Our Implementation:**

```java
// ConduitImpl.java (fullerstack)
public class ConduitImpl<P, E> implements Conduit<P, E> {
    private final Subject conduitSubject;
    private final Composer<? extends P, E> composer;
    private final Map<Name, P> percepts = new ConcurrentHashMap<>();  // One per Name
    private final Source<E> eventSource;
    private final BlockingQueue<Capture<E>> queue;

    @Override
    public P get(Name subject) {
        return percepts.computeIfAbsent(subject, s -> {
            Channel<E> channel = new ChannelImpl<>(s, queue);
            return composer.compose(channel);  // ← Composer creates Percept
        });
    }

    @Override
    public Source<E> source() {
        return eventSource;  // ← For Subscriber registration
    }
}
```

**Key Features:**
- One percept (via Channel) per unique Name
- Composer transforms Channel → Percept (e.g., Pipe)
- Source provides Subscriber registration
- Type-safe: All Channels in Conduit have same emission type `E`

**Verification**: ✅ Correct - Conduit manages named Channels

---

### 5. ✅ Channel: Communication Content Container

**Article States:**
> "Channel - Managed container for data"
> "One Channel per unique Name"
> "Can be decorated by a Composer"

**Our Implementation:**

```java
// ChannelImpl.java (fullerstack)
public class ChannelImpl<E> implements Channel<E> {
    private final Subject channelSubject;  // ← WHO is emitting
    private final BlockingQueue<Capture<E>> queue;

    public ChannelImpl(Name name, BlockingQueue<Capture<E>> queue) {
        this.channelSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.CHANNEL
        );
        this.queue = queue;
    }

    @Override
    public Subject subject() {
        return channelSubject;
    }

    @Override
    public Pipe<E> pipe() {
        return new PipeImpl<>(this);
    }

    public void emit(E value) {
        Capture<E> capture = new CaptureImpl<>(channelSubject, value);
        queue.offer(capture);  // ← Post to Queue
    }
}
```

**Key Features:**
- Has its own Subject identity (WHO emits)
- Posts Capture (Subject + Emission) to queue
- Provides Pipe for emission
- Managed by Conduit (one per Name)

**Verification**: ✅ Correct - Channel as managed container

---

### 6. ✅ Pipe: Communication Pathway

**Article States:**
> "Pipe - Communication pathway"
> "Can be wrapped by a Percept"
> "Emits data into Circuit's Queue"

**Our Implementation:**

```java
// PipeImpl.java (fullerstack)
public class PipeImpl<E> implements Pipe<E> {
    private final ChannelImpl<E> channel;

    public PipeImpl(Channel<E> channel) {
        this.channel = (ChannelImpl<E>) channel;
    }

    @Override
    public void emit(E value) {
        channel.emit(value);  // ← Delegates to Channel
    }
}

// Composer.pipe() - API-provided static factory
// From Substrates API:
interface Composer<P, E> {
    static <E> Composer<Pipe<E>, E> pipe() {
        return Inlet::pipe;  // ← Creates Pipe percept
    }
}
```

**Key Features:**
- Pipe is a Percept (return type of Composer)
- Wraps Channel for typed emission
- Simple interface: `void emit(E value)`

**Verification**: ✅ Correct - Pipe as communication pathway

---

### 7. ✅ Composer: Percept Factory

**Article States:**
> "Channel can be decorated by a Composer"
> "Composer creates Percepts that wrap Pipes"

**Our Implementation:**

```java
// Composer interface (from Substrates API)
interface Composer<P, E> {
  P compose(Channel<E> channel);
}

// Example: API-provided Composer.pipe()
// From Substrates API:
interface Composer<P, E> {
    P compose(Channel<E> channel);

    static <E> Composer<Pipe<E>, E> pipe() {
        return Inlet::pipe;
    }
}

// Used by Conduit to create percepts
Channel<E> channel = new ChannelImpl<>(name, queue);
P percept = composer.compose(channel);  // ← Transform Channel → Percept
```

**Verification**: ✅ Correct - Composer decorates Channel

---

### 8. ✅ Data Flow: Inlet → Queue → Outlet

**Article States:**
> "Inlet: Publishing side (Percept emitting data)"
> "Outlet: Subscribing side (Subscriber receiving data)"
> "Data flows from Percepts' Pipe to Subscribers' Pipe"

**Our Implementation:**

```
┌─────────────────────────────────────────────────────────────┐
│                        DATA FLOW                            │
└─────────────────────────────────────────────────────────────┘

INLET (Publishing Side):
  Percept (Pipe)
    → pipe.emit(value)
    → channel.emit(value)
    → Create Capture(Subject, Emission)
    → queue.offer(capture)

QUEUE:
  QueueProcessor Thread
    → capture = queue.take()
    → processEmission(capture)

OUTLET (Subscribing Side):
  For each Subscriber:
    → subscriber.accept(subject, registrar)
    → registrar.register(outputPipe)
    → outputPipe.emit(value)
```

**Code Flow:**

```java
// INLET: Publishing
Pipe<Long> inlet = conduit.get(cortex.name("cpu"));
inlet.emit(85L);  // → ChannelImpl creates Capture → queue

// QUEUE: Processing
// QueueProcessor takes Capture, calls processEmission()

// OUTLET: Subscribing
Source<Long> source = conduit.source();
source.subscribe(cortex.subscriber(
    cortex.name("logger"),
    (subject, registrar) -> {
        registrar.register(value -> {  // ← Outlet Pipe
            System.out.println("Received: " + value);
        });
    }
));
```

**Verification**: ✅ Correct - Complete Inlet → Queue → Outlet flow

---

### 9. ✅ Capture: Subject + Emission Pairing

**Article States (implicit):**
> "Preserves context of WHO emitted WHAT"

**Our Implementation:**

```java
// CaptureImpl.java (fullerstack)
public record CaptureImpl<E>(Subject subject, E emission) implements Capture<E> {
    CaptureImpl {
        Objects.requireNonNull(subject, "Capture subject cannot be null");
    }
}

// Created by Channel
Capture<E> capture = new CaptureImpl<>(channelSubject, value);
queue.offer(capture);

// Received by Subscriber
processEmission(Capture<E> capture) {
    Subject emittingSubject = capture.subject();  // WHO
    E value = capture.emission();                 // WHAT
    // Route to subscribers
}
```

**Verification**: ✅ Correct - Capture pairs Subject + Emission

---

### 10. ✅ Path/Sequencer: Optional Transformations

**Article States:**
> "Path provides optional pipeline transformations"
> "Can transform data before and after Queue"

**Our Implementation:**

```java
// Channel.pipe(Sequencer) creates transformed Pipe
@Override
public Pipe<E> pipe(Sequencer<? super Segment<E>> sequencer) {
    Objects.requireNonNull(sequencer, "Sequencer cannot be null");

    // Create Segment and apply Sequencer transformations
    SegmentImpl<E> segment = new SegmentImpl<>();
    sequencer.apply(segment);

    // Return Pipe with transformations
    return new PipeImpl<>(queue, channelSubject, segment);
}

// Usage example
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("filtered-pipe"),
    Composer.pipe(segment -> segment
        .guard(value -> value > 0)    // Filter
        .replace(value -> value * 2)  // Map
        .limit(10)                    // Limit
    )
);
```

**Verification**: ✅ Correct - Path/Sequencer transformations supported

---

### 11. ✅ Subscriber: Outlet Registration

**Article States:**
> "Conduit supports registering Subscribers"
> "Subscribers register Pipes at Subject level"
> "Registrar helps Subscribers connect to Subjects"

**Our Implementation:**

```java
// Subscriber interface
interface Subscriber<E> {
  void accept(Subject subject, Registrar<E> registrar);
}

// Subscriber registration
Source<E> source = conduit.source();
source.subscribe(cortex.subscriber(
    cortex.name("consumer"),
    (subject, registrar) -> {
        // Called when Subject emits first time
        Pipe<E> outputPipe = ... // Create outlet Pipe
        registrar.register(outputPipe);  // ← Register for emissions
    }
));

// Conduit processes emissions
private void processEmission(Capture<E> capture) {
    Subject emittingSubject = capture.subject();

    for (Subscriber<E> subscriber : source.getSubscribers()) {
        // Get or create registered pipes for this Subject
        List<Pipe<E>> pipes = pipeCache.computeIfAbsent(subscriber, sub -> {
            List<Pipe<E>> registeredPipes = new CopyOnWriteArrayList<>();
            sub.accept(emittingSubject, pipe -> registeredPipes.add(pipe));
            return registeredPipes;
        });

        // Emit to all registered outlet pipes
        for (Pipe<E> pipe : pipes) {
            pipe.emit(capture.emission());
        }
    }
}
```

**Verification**: ✅ Correct - Full Subscriber pattern

---

### 12. ⚠️ **Circuit Queue Architecture Issue**

**Article States:**
> "Circuit manages work Queues"
> "Conduits share Circuit's Queue"
> "Single queued pipeline per Circuit"

**Current Implementation:**

```java
// ConduitImpl.java - ISSUE: Creates own queue
public class ConduitImpl<P, E> implements Conduit<P, E> {
    private final BlockingQueue<Capture<E>> queue = new LinkedBlockingQueue<>(10000);
    private final Thread queueProcessor;

    public ConduitImpl(...) {
        this.queueProcessor = startQueueProcessor();  // Own thread!
    }
}
```

**Problem:**
- Each Conduit creates its own queue and processor thread
- Article states Circuit manages "work Queues" (singular intent)
- Should be: All Conduits share Circuit's single Queue
- Violates "single queued pipeline" principle

**Expected Architecture:**

```java
// Circuit should pass Queue to Conduit
public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
    return conduits.computeIfAbsent(name, n ->
        new ConduitImpl<>(circuitSubject.name(), n, composer, this.queue)
        //                                                      ^^^^^^^^^^
        //                                                Circuit's Queue
    );
}

// Conduit should use Circuit's Queue
public class ConduitImpl<P, E> implements Conduit<P, E> {
    private final Queue circuitQueue;  // Shared Queue

    public ConduitImpl(..., Queue circuitQueue) {
        this.circuitQueue = circuitQueue;
        // No queue processor - Circuit's Queue processes Scripts
    }

    // Channels post Scripts to Circuit's Queue
    Channel<E> channel = new ChannelImpl<>(name, circuitQueue);
}
```

**Impact:**
- Violates single-threaded Circuit model
- No cross-Conduit ordering guarantees
- Cannot control QoS/priority across Circuit
- See `CIRCUIT_QUEUE_ARCHITECTURE_ISSUE.md` for full details

**Verification**: ❌ **INCORRECT** - Documented architectural issue

---

### 13. ✅ Type Safety

**Article States (implicit):**
> "Single communication content type per Conduit"

**Our Implementation:**

```java
// Type parameter E ensures single type per Conduit
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("metrics"),
    Composer.pipe()
);

// All Channels in this Conduit emit Long
Pipe<Long> cpu = conduit.get(cortex.name("cpu"));
Pipe<Long> memory = conduit.get(cortex.name("memory"));

cpu.emit(85L);     // ✓ Type-safe
memory.emit(90L);  // ✓ Type-safe
// cpu.emit("text"); // ✗ Compile error
```

**Verification**: ✅ Correct - Full type safety

---

### 14. ✅ Clock: Special Conduit

**Article States:**
> "Clock is a special Conduit with restricted Channel access"

**Our Implementation:**

```java
// CircuitImpl.java
@Override
public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);
}

// ClockImpl.java
public class ClockImpl implements Clock {
    private final Name name;
    private final Map<Name, Map<Clock.Cycle, List<Consumer<Instant>>>> consumers;

    @Override
    public void consume(Name name, Clock.Cycle cycle, Consumer<Instant> consumer) {
        // Register consumer for time events
        consumers.computeIfAbsent(name, n -> new ConcurrentHashMap<>())
                 .computeIfAbsent(cycle, c -> new CopyOnWriteArrayList<>())
                 .add(consumer);

        // Start scheduler if not already running
        startScheduler(name, cycle);
    }
}
```

**Key Difference:**
- Clock doesn't expose Channels directly
- Provides time-based event generation
- Restricted API: `consume(name, cycle, consumer)`

**Verification**: ✅ Correct - Clock as special Conduit

---

## Architecture Diagram Compliance

### Article's Component Relationships

```
Cortex (Bootstrap)
  └─ Circuit (Factory, Queue Manager)
      ├─ Queue (Coordination)
      ├─ Clock (Time Events)
      └─ Conduit (Named Channel Set)
          ├─ Channel (per Name)
          │   └─ Pipe (Percept)
          └─ Source (Subscriber Management)
              └─ Subscriber (Outlet Registration)
```

### Our Implementation

```
CortexRuntime
  └─ CircuitImpl
      ├─ QueueImpl ✓
      ├─ ClockImpl ✓
      └─ ConduitImpl
          ├─ ChannelImpl (per Name) ✓
          │   └─ PipeImpl ✓
          └─ SourceImpl ✓
              └─ Subscriber ✓
```

**Verification**: ✅ Correct - Complete component tree

---

### Article's Data Flow

```
┌────────────┐
│  Percept   │ (Inlet - Publishing)
│  (Pipe)    │
└─────┬──────┘
      │ emit()
      ▼
┌────────────┐
│  Channel   │
└─────┬──────┘
      │ create Capture(Subject, Emission)
      ▼
┌────────────┐
│   Queue    │ (Coordination)
└─────┬──────┘
      │ take()
      ▼
┌────────────┐
│ Subscriber │ (Outlet - Subscribing)
│    Pipe    │
└────────────┘
```

### Our Implementation Flow

```
PipeImpl.emit(value)
  → ChannelImpl.emit(value)
      → new CaptureImpl(subject, value)
      → queue.offer(capture)
          → QueueProcessor.take()
              → processEmission(capture)
                  → For each Subscriber:
                      → subscriber.accept(subject, registrar)
                      → registrar.register(outputPipe)
                      → outputPipe.emit(value)
```

**Verification**: ✅ Correct - Complete data flow

---

## Test Coverage

**Tests that verify Substrates 101 concepts:**

```java
// ✅ Cortex → Circuit hierarchy
@Test
void shouldCreateCircuitFromCortex() {
    Cortex cortex = new CortexRuntime();
    Circuit circuit = cortex.circuit(cortex.name("app"));
    assertThat(circuit).isNotNull();
}

// ✅ Circuit → Conduit hierarchy
@Test
void shouldCreateConduitFromCircuit() {
    Circuit circuit = cortex.circuit();
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("metrics"),
        Composer.pipe()
    );
    assertThat(conduit).isNotNull();
}

// ✅ Conduit → Channel → Pipe hierarchy
@Test
void shouldCreatePipeFromConduit() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("test"),
        Composer.pipe()
    );
    Pipe<Long> pipe = conduit.get(cortex.name("cpu"));
    assertThat(pipe).isNotNull();
}

// ✅ Inlet → Outlet data flow
@Test
void shouldForwardEmissionsToSubscribers() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("metrics"),
        Composer.pipe()
    );

    List<Long> received = new CopyOnWriteArrayList<>();
    conduit.source().subscribe(cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) -> registrar.register(received::add)
    ));

    Pipe<Long> inlet = conduit.get(cortex.name("cpu"));
    inlet.emit(85L);

    // Wait for async processing
    await().until(() -> received.contains(85L));
}

// ✅ One Channel per Name
@Test
void shouldReuseChannelForSameName() {
    Pipe<Long> pipe1 = conduit.get(cortex.name("cpu"));
    Pipe<Long> pipe2 = conduit.get(cortex.name("cpu"));
    assertThat(pipe1).isSameAs(pipe2);
}

// ✅ Capture pairing
@Test
void shouldPreserveSubjectInCapture() {
    Capture<Long> capture = cortex.capture(subject, 100L);
    assertThat(capture.subject()).isSameAs(subject);
    assertThat(capture.emission()).isEqualTo(100L);
}
```

---

## Alignment Summary

| Concept | Article Requirement | Our Implementation | Status |
|---------|-------------------|-------------------|--------|
| Cortex bootstrap | ✓ | CortexRuntime with 38 methods | ✅ |
| Circuit factory | ✓ | CircuitImpl creates Conduits | ✅ |
| Circuit Queue | ✓ | **Each Conduit has own queue** | ❌ |
| Conduit manages Channels | ✓ | ConduitImpl.get(Name) | ✅ |
| One Channel per Name | ✓ | percepts map caching | ✅ |
| Channel has Subject | ✓ | ChannelImpl.channelSubject | ✅ |
| Pipe as Percept | ✓ | PipeImpl wraps Channel | ✅ |
| Composer creates Percept | ✓ | Composer.pipe() (API-provided) | ✅ |
| Inlet → Queue → Outlet | ✓ | emit → Capture → queue → Subscriber | ✅ |
| Capture pairing | ✓ | CaptureImpl(Subject, Emission) | ✅ |
| Subscriber registration | ✓ | Source.subscribe() + Registrar | ✅ |
| Path transformations | ✓ | Sequencer + Segment | ✅ |
| Type safety | ✓ | Generic types enforced | ✅ |
| Clock special Conduit | ✓ | ClockImpl with restricted API | ✅ |

---

## Conclusion

**Status: ⚠️ MOSTLY ALIGNED (1 architectural issue)**

Our implementation correctly implements **13 out of 14** key concepts from the Substrates 101 article:

### ✅ **Correct:**
1. Cortex as bootstrap entry point
2. Circuit as factory for Conduits
3. Conduit manages named Channels
4. One Channel per unique Name
5. Channel has Subject identity
6. Pipe as communication pathway
7. Composer creates Percepts from Channels
8. Complete Inlet → Queue → Outlet data flow
9. Capture pairs Subject + Emission
10. Subscriber registration and Registrar pattern
11. Path/Sequencer transformations
12. Type-safe single content type per Conduit
13. Clock as special Conduit

### ❌ **Incorrect:**
1. **Circuit Queue Architecture** - Each Conduit creates its own queue and processor thread instead of sharing Circuit's single queue (see `CIRCUIT_QUEUE_ARCHITECTURE_ISSUE.md`)

**Recommendation:** Fix the Circuit Queue issue to achieve full alignment with Substrates 101 architecture. All Conduits should share the Circuit's single Queue for proper coordination and single-threaded execution model.

---

## References

- **Article**: https://humainary.io/blog/observability-x-substrates-101/
- **Key Quote**: "Circuit manages work Queues and controls underlying queued pipeline"
- **Architecture**: Cortex → Circuit → Conduit → Channel → Pipe
- **Data Flow**: Inlet (Percept) → Queue → Outlet (Subscriber)
- **Related Documents**:
  - `CIRCUIT_QUEUE_ARCHITECTURE_ISSUE.md` - Details on queue sharing issue
  - `CHANNEL_IMPLEMENTATION_ALIGNMENT.md` - Channel specifics
  - `COMPOSER_IMPLEMENTATION_ALIGNMENT.md` - Composer pattern
  - `QUEUES_SCRIPTS_CURRENTS_ALIGNMENT.md` - Queue mechanics
  - `RESOURCES_SCOPES_CLOSURES_ALIGNMENT.md` - Resource management
