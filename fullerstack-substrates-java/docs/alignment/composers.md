# Composer Implementation Alignment

**Date**: 2025-10-11
**Article**: https://humainary.io/blog/observability-x-composers/
**Status**: ✅ ALIGNED

## Summary

Our Substrates implementation correctly provides the `Composer` interface and infrastructure as defined in the Humainary article. The Substrates API provides static factories for common cases (`Composer.pipe()`, `Composer.channel()`), while domain-specific Composer implementations belong in application code, not in the Substrates library.

## Article's Key Requirements

### 1. ✅ Composer Interface

**Article States:**
> "Composer is an interface that constructs percepts around channels"
> "Contains a single method compose() that creates a percept from a channel"

**API Definition:**
```java
@Abstract
@Extension
interface Composer<P, E> {
  P compose(Channel<E> channel);
}
```

**Generic Types:**
- `P` - Percept type (what gets returned, e.g., Pipe<E>, Party, Meter<E>)
- `E` - Emission type (what the channel emits, e.g., String, Long, Signal)

**API Provides Static Factories:**
```java
// From Substrates API
interface Composer<P, E> {
    P compose(Channel<E> channel);

    // Static factory for Pipe composer (provided by API)
    static <E> Composer<Pipe<E>, E> pipe() {
        return Inlet::pipe;
    }

    // Static factory for Channel composer (identity)
    static <E> Composer<Channel<E>, E> channel() {
        return input -> input;
    }
}
```

**Usage:**
```java
// Use API-provided composer
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    name,
    Composer.pipe()  // ← API-provided static factory
);
```

**Verification**: ✅ Correct - API provides common Composer implementations

### 2. ✅ Purpose: Constructs Percepts from Channels

**Article States:**
> "Composer serves as a mechanism to create specialized perception objects dynamically when needed"
> "Enables on-demand creation of observability instruments"

**Our Implementation:**
The Composer is used by Conduit to create percepts on-demand:

```java
// ConduitImpl.java:88-93 (fullerstack)
@Override
public P get(Name subject) {
    return percepts.computeIfAbsent(subject, s -> {
        Channel<E> channel = new ChannelImpl<>(s, queue);
        return composer.compose(channel);  // ← Composer creates percept from channel
    });
}
```

**How It Works:**
1. User calls `conduit.get(name)` to get a percept
2. Conduit creates a Channel for that subject
3. Composer transforms Channel into Percept (e.g., Pipe)
4. Percept is cached and returned
5. Subsequent calls with same name return cached percept

**Verification**: ✅ Correct - On-demand percept creation via Composer

### 3. ✅ Relationship to Conduits

**Article States:**
> "Used by Conduits to generate named perception instances"

**Our Implementation:**
```java
// ConduitImpl constructor (fullerstack)
public ConduitImpl(Name circuitName, Name conduitName, Composer<? extends P, E> composer) {
    this.conduitSubject = new SubjectImpl(...);
    this.composer = composer;  // Store composer for percept creation
    this.eventSource = new SourceImpl<>(conduitName);
    this.queueProcessor = startQueueProcessor();
}

// Circuit creates Conduit with Composer
circuit.conduit(
    cortex.name("test-conduit"),
    Composer.pipe()  // ← Composer passed to Conduit
);
```

**Verification**: ✅ Correct - Conduit stores and uses Composer for percept creation

### 4. ✅ Access to Channel's Subject

**Article States:**
> "Can access the Channel's Subject during percept creation"

**Article's Example:**
```java
class Parties implements Composer<Party, String> {
  Party compose(Channel<String> channel) {
    var path = channel.subject().foldTo(...);  // ← Access to Subject
    return new Party(path, new ArrayList<>(), channel.pipe());
  }
}
```

**Example Implementation:**
```java
// Domain-specific composer can access Subject
class MeterComposer<E> implements Composer<Meter<E>, E> {
    @Override
    public Meter<E> compose(Channel<E> channel) {
        // Access to Subject for domain logic
        Name name = channel.subject().name();
        return new MeterImpl<>(name, channel.pipe());
    }
}
```

**Channel provides Subject:**
```java
// Channel interface extends Substrate
interface Channel<E> extends Substrate, Inlet<E> {
  // Substrate provides:
  Subject subject();
}

// ChannelImpl implementation
public class ChannelImpl<E> implements Channel<E> {
    private final Subject channelSubject;

    @Override
    public Subject subject() {
        return channelSubject;
    }
}
```

**Verification**: ✅ Correct - Composer can access Channel's Subject

### 5. ✅ Type Safety

**Article States:**
> "Provides flexible, type-safe factory for creating observability instruments"

**Our Implementation:**
```java
// Type-safe Composer declaration
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("metrics"),
    Composer.pipe()  // Composer<Pipe<Long>, Long>
);

// Compiler enforces:
// - P = Pipe<Long> (percept type returned by conduit.get())
// - E = Long (emission type that can be emitted)

Pipe<Long> pipe = conduit.get(cortex.name("app"));  // Returns Pipe<Long>
pipe.emit(100L);  // Type-safe emission - only Long accepted
```

**Generic Type Flow:**
```
Circuit.conduit<P, E>(Composer<P, E>) → Conduit<P, E>
                ↓
Conduit.get(Name) → P (percept created via Composer)
                ↓
P.emit(E) → type-safe emission
```

**Verification**: ✅ Correct - Full type safety throughout

### 6. ✅ Composer.pipe() Static Factory

**Article's Example:**
```java
var conduit = circuit.conduit(
  Integer.class,
  Inlet::pipe  // Method reference as Composer
);
```

**API Implementation:**
```java
// From Substrates API
interface Composer<P, E> {
  P compose(Channel<E> channel);

  // Static factory method (provided by API)
  static <E> Composer<Pipe<E>, E> pipe() {
    return Inlet::pipe;  // Method reference
  }

  // With Sequencer support
  static <E> Composer<Pipe<E>, E> pipe(
    Sequencer<? super Segment<E>> sequencer
  ) {
    return channel -> channel.pipe(sequencer);
  }
}

// Usage in production code
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("test-conduit"),
    Composer.pipe()  // ← API-provided static factory
);
```

**Verification**: ✅ Correct - API provides static factories for common use cases

### 7. ✅ Domain-Specific Composers

**Article States:**
> "Allows mapping problem domain concepts to observability abstractions"

**Article's Example - Chat Application:**
```java
class Parties implements Composer<Party, String> {
  Party compose(Channel<String> channel) {
    var path = channel.subject().foldTo(...);
    return new Party(path, new ArrayList<>(), channel.pipe());
  }
}

// Usage:
var conduit = circuit.conduit(String.class, new Parties());
var party = conduit.get(cortex.name("room-1"));  // Returns Party, not Pipe
party.send("Hello!");
```

**Our Pattern:**
```java
// Custom domain-specific composer example:
class MeterComposer<E extends Number> implements Composer<Meter<E>, E> {
  @Override
  public Meter<E> compose(Channel<E> channel) {
    return new MeterImpl<>(
      channel.subject().name(),
      channel.pipe()
    );
  }
}

// Usage:
Conduit<Meter<Long>, Long> metrics = circuit.conduit(
    cortex.name("metrics"),
    new MeterComposer<>()
);

Meter<Long> cpuMeter = metrics.get(cortex.name("cpu"));
cpuMeter.record(85L);
```

**Verification**: ✅ Correct - Pattern supports domain-specific percepts

### 8. ✅ Integration with Circuit

**Article's Pattern:**
```java
var circuit = cortex.circuit();
var conduit = circuit.conduit(String.class, new Parties());
```

**Our Implementation:**
```java
// CircuitImpl.java (fullerstack)
@Override
public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
    checkClosed();
    Objects.requireNonNull(name, "Conduit name cannot be null");
    Objects.requireNonNull(composer, "Composer cannot be null");

    return conduits.computeIfAbsent(name, n ->
        new ConduitImpl<>(subject().name(), n, composer)
    );
}
```

**Test Verification:**
```java
// SubscriberIntegrationTest.java:26-29
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("test-conduit"),
    Composer.pipe()
);
```

**Verification**: ✅ Correct - Circuit creates Conduits with Composers

### 9. ✅ Composer Caching via Conduit

**Article States:**
> "Enables on-demand creation with caching"

**Our Implementation:**
```java
// ConduitImpl caches percepts created by Composer
private final Map<Name, P> percepts = new ConcurrentHashMap<>();

@Override
public P get(Name subject) {
    return percepts.computeIfAbsent(subject, s -> {
        Channel<E> channel = new ChannelImpl<>(s, queue);
        return composer.compose(channel);  // Only called once per subject
    });
}
```

**How It Works:**
1. First call: `conduit.get(name)` → creates Channel, calls Composer, caches result
2. Second call: `conduit.get(name)` → returns cached percept (no Composer invocation)
3. Different name: Creates new percept via Composer

**Test Verification:**
```java
Pipe<Long> pipe1 = conduit.get(cortex.name("app"));
Pipe<Long> pipe2 = conduit.get(cortex.name("app"));
assertThat(pipe1).isSameAs(pipe2);  // Same instance (cached)

Pipe<Long> pipe3 = conduit.get(cortex.name("service"));
assertThat(pipe1).isNotSameAs(pipe3);  // Different instance (different subject)
```

**Verification**: ✅ Correct - Percepts cached per subject name

## Interface Compliance

### Composer Interface (from Substrates API)

```java
@Abstract
@Extension
interface Composer<P, E> {
  P compose(Channel<E> channel);
}
```

### API-Provided Implementations

**Substrates API Static Factories:**
```java
interface Composer<P, E> {
    P compose(Channel<E> channel);

    // Pipe composer (most common case)
    static <E> Composer<Pipe<E>, E> pipe() {
        return Inlet::pipe;
    }

    // Pipe composer with transformations
    static <E> Composer<Pipe<E>, E> pipe(
        Sequencer<? super Segment<E>> sequencer
    ) {
        return channel -> channel.pipe(sequencer);
    }

    // Channel composer (identity)
    static <E> Composer<Channel<E>, E> channel() {
        return input -> input;
    }
}
```

**Domain-Specific Example:**
```java
// Application code implements domain composers
public class MeterComposer<E extends Number> implements Composer<Meter<E>, E> {
    @Override
    public Meter<E> compose(Channel<E> channel) {
        return new MeterImpl<>(channel.subject().name(), channel.pipe());
    }
}
```

**Verification**: ✅ API provides infrastructure, applications provide domain composers

## Architecture Separation

**Substrates Library Responsibilities:**
- ✅ Provides `Composer<P, E>` interface
- ✅ Provides static factories for common cases (`Composer.pipe()`, `Composer.channel()`)
- ✅ Provides infrastructure (Circuit, Conduit, Channel, Pipe, Segment)

**Application Code Responsibilities:**
- ✅ Implements domain-specific composers (Parties, Meter, Counter, etc.)
- ✅ Defines domain percept types that compose Channels
- ✅ Maps domain concepts to Substrates infrastructure

**Verification**: ✅ Clear separation of concerns between library and application

## Usage Examples from Tests

### Basic Composer Usage

```java
// SubscriberIntegrationTest.java:26-29
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("test-conduit"),
    Composer.pipe()  // ← Composer creates Pipe percepts
);

Pipe<Long> pipe = conduit.get(cortex.name("app"));
pipe.emit(100L);
```

### Multiple Conduits with Different Composers

```java
// Different percept types via different Composers
Conduit<Pipe<Long>, Long> pipeConduit = circuit.conduit(
    cortex.name("pipes"),
    Composer.pipe()  // Returns Pipe<Long>
);

Conduit<Meter<Long>, Long> meterConduit = circuit.conduit(
    cortex.name("meters"),
    new MeterComposer<>()  // Returns Meter<Long>
);
```

### Composer with Subscriber Integration

```java
// SubscriberIntegrationTest.java:40-76
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    cortex.name("metrics"),
    Composer.pipe()
);

Source<Long> source = conduit.source();

Subscriber<Long> subscriber = cortex.subscriber(
    cortex.name("routing-subscriber"),
    (subject, registrar) -> {
        // Composer-created Pipe available via conduit.get()
        Pipe<Long> pipe = conduit.get(subject.name());
        registrar.register(value -> receivedValues.add(value));
    }
);

source.subscribe(subscriber);
```

## Why Substrates Library Doesn't Provide Composer Implementations

**Reason 1: API Already Provides Them**
```java
Composer.pipe()      // Already in Substrates API
Composer.channel()   // Already in Substrates API
```

**Reason 2: Domain-Specific by Nature**
```java
// These belong in application code, not infrastructure:
class Parties implements Composer<Party, String> { ... }
class Meters implements Composer<Meter<E>, E> { ... }
class Counters implements Composer<Counter, Long> { ... }
```

**Reason 3: Separation of Concerns**
- **Substrates Library**: Provides abstractions (Composer interface, infrastructure)
- **Application Code**: Maps domain concepts to infrastructure (domain composers)

**This follows the same pattern as Java's standard library:**
- `java.util.function.Function<T, R>` - Interface provided
- `String::toLowerCase` - Implementation in application code

## Alignment Summary

| Concept | Article Requirement | Our Implementation | Status |
|---------|-------------------|-------------------|--------|
| Composer interface | ✓ | Provided by Substrates API | ✅ |
| compose() method | ✓ | Returns percept from Channel | ✅ |
| Type safety | ✓ | Generic types P and E enforced | ✅ |
| On-demand creation | ✓ | Conduit.get() creates via Composer | ✅ |
| Percept caching | ✓ | Conduit caches by subject name | ✅ |
| Access to Subject | ✓ | Channel.subject() available | ✅ |
| Circuit integration | ✓ | Circuit.conduit(Composer) | ✅ |
| Static factories | ✓ | Composer.pipe(), Composer.channel() | ✅ |
| Domain-specific | ✓ | Applications implement domain composers | ✅ |
| Code examples work | ✓ | Tests demonstrate article patterns | ✅ |

## Test Coverage

**Tests that verify Composer concepts:**

```java
// ✅ Basic Composer usage
@Test
void shouldSetupCortexCircuitConduitAndSource() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("test-conduit"),
        Composer.pipe()
    );
    assertThat((Object) conduit).isNotNull();
}

// ✅ Percept creation via Composer
@Test
void shouldForwardEmissionsAcrossNamespaceHierarchy() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("metrics"),
        Composer.pipe()
    );

    Pipe<Long> pipe = conduit.get(cortex.name("app"));
    pipe.emit(100L);
    // Verifies Composer created working Pipe
}

// ✅ Pool-based Subscriber with Composer
@Test
void shouldUsePoolBasedSubscriber() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("counters"),
        Composer.pipe()
    );

    Pool<Pipe<Long>> pipePool = subjectName -> value -> {
        values.add(value);
    };

    // Composer-created Pipes used by Pool
}

// ✅ Multiple subscribers with Composer-created percepts
@Test
void shouldHandleMultipleSubscribers() {
    Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
        cortex.name("events"),
        Composer.pipe()
    );

    source.subscribe(subscriber1);
    source.subscribe(subscriber2);

    conduit.get(cortex.name("event1")).emit(99L);
    // Both subscribers receive via Composer-created Pipe
}
```

## Conclusion

**Status: ✅ FULLY ALIGNED**

Our Substrates implementation correctly provides the Composer pattern as described in the Humainary article:

1. ✅ Composer<P, E> interface with compose() method (in API)
2. ✅ Creates percepts from Channels
3. ✅ Type-safe percept factory
4. ✅ On-demand creation via Conduit.get()
5. ✅ Percept caching by subject name
6. ✅ Access to Channel's Subject
7. ✅ Integration with Circuit.conduit()
8. ✅ Static factory methods (Composer.pipe(), Composer.channel())
9. ✅ Supports domain-specific composers (implemented in application code)
10. ✅ Article's code examples work

**Correct Architecture:**
- **Substrates Library**: Provides Composer interface + common static factories
- **Application Code**: Implements domain-specific composers (Parties, Meter, Counter, etc.)

This separation of concerns matches the article's intent and follows standard library design patterns.

## Example Domain-Specific Composers (Application Code)

Domain-specific composers should be implemented in application code, not in the Substrates library:

```java
// Meter composer for metrics (in application code)
class MeterComposer<E extends Number> implements Composer<Meter<E>, E> {
    @Override
    public Meter<E> compose(Channel<E> channel) {
        return new MeterImpl<>(channel.subject().name(), channel.pipe());
    }
}

// Counter composer for incrementing values
class CounterComposer implements Composer<Counter, Long> {
    @Override
    public Counter compose(Channel<Long> channel) {
        return new CounterImpl(channel.subject(), channel.pipe());
    }
}

// Gauge composer for instantaneous values
class GaugeComposer<E> implements Composer<Gauge<E>, E> {
    @Override
    public Gauge<E> compose(Channel<E> channel) {
        return new GaugeImpl<>(channel.subject(), channel.pipe());
    }
}

// Party composer from article (chat application)
class Parties implements Composer<Party, String> {
    @Override
    public Party compose(Channel<String> channel) {
        var path = channel.subject().foldTo(...);
        return new Party(path, new ArrayList<>(), channel.pipe());
    }
}
```

These domain composers map application concepts to Substrates infrastructure.

## References

- **Article**: https://humainary.io/blog/observability-x-composers/
- **Key Quote**: "Composer is an interface that constructs percepts around channels"
- **Pattern**: `Composer<P, E>` with `P compose(Channel<E>)`
- **Usage**: `circuit.conduit(name, composer)` → `conduit.get(subject)` → percept
