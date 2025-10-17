# Core Concepts

This document explains the fundamental concepts in Substrates and how they work together.

## Table of Contents

- [What is Substrates?](#what-is-substrates)
- [Core Abstractions](#core-abstractions)
- [Producer-Consumer Model](#producer-consumer-model)
- [Transformation Pipelines](#transformation-pipelines)
- [Resource Management](#resource-management)
- [Observability Model](#observability-model)

## What is Substrates?

Substrates is a framework for building **event-driven observability systems**. It provides:

1. **Type-safe event routing** - from producers to consumers
2. **Transformation pipelines** - filter, map, reduce, sample emissions
3. **Dynamic subscription** - observers can subscribe/unsubscribe at runtime
4. **Resource lifecycle** - automatic cleanup and hierarchical scoping
5. **Precise ordering** - guaranteed event ordering per circuit

### Semiotic Observability

Substrates supports William Louth's vision of **semiotic observability**:

```
Metrics (traditional)
  ↓
Signs (observations)
  ↓
Symptoms (patterns)
  ↓
Syndromes (correlated symptoms)
  ↓
Situations (system states)
  ↓
Steering (actions)
```

Substrates provides the **runtime substrate** for this observability stack.

## Core Abstractions

### Subject

**What:** Hierarchical reference system for identifying entities.

**Components:**
- **Id** - Unique instance identifier (UUID-based, used internally)
- **Name** - Hierarchical semantic identity (like a namespace)
- **State** - Immutable key-value data
- **Type** - Category (CHANNEL, CIRCUIT, CLOCK, etc.)

**Example:**
```java
Subject subject = channel.subject();
System.out.println(subject.name());  // e.g., "circuit.conduit.channel1"
System.out.println(subject.type());  // CHANNEL
System.out.println(subject.id());    // Unique UUID (rarely accessed directly)
```

**Note:** While every Subject has an Id for unique instance identification, you'll primarily work with **Name** for component lookup and caching. The Id is managed internally by the framework.

### Name

**What:** Hierarchical semantic identity used for both naming and caching.

**Key Design Philosophy:** Name is **separate from the instrument/component itself**. The instrument doesn't need to maintain its own name - the Name serves as an **external key** in the registry that maps to the instrument.

**Features:**
- Dot-separated parts: `"app.service.endpoint"`
- Implements `Extent<Name>` for hierarchical navigation
- Immutable and comparable
- **Used as cache key** in Circuit/Cortex component pools
- **External to the instrument** - Name is the key, not part of the value
- **Interned by default** for identity map fast path

**Dual Purpose:**
1. **Semantic Identity** - Human-readable hierarchical names
2. **Cache Key** - Enables singleton pattern for named components

**Separation of Concerns:**
```java
// Name is the KEY, Pipe is the VALUE
Map<Name, Pipe<String>> registry = ...;

// Pipe doesn't store its name - Name is external
Pipe<String> pipe = registry.get(name);
// pipe.name() doesn't exist - the NAME identifies the pipe in the namespace

// This separation allows:
// - Multiple names can reference same instrument (aliasing)
// - Instrument is lightweight (no name storage overhead)
// - Name can be used for routing without touching the instrument
```

**Usage:**
```java
Name root = cortex.name("app");
Name service = root.name("service");
Name endpoint = service.name("endpoint");

System.out.println(endpoint.path());  // "app.service.endpoint"
System.out.println(endpoint.depth()); // 3
```

**Name as Cache Key:**
```java
Cortex cortex = new CortexRuntime();

// First call creates circuit with name "kafka-cluster"
Circuit c1 = cortex.circuit(cortex.name("kafka-cluster"));

// Second call returns THE SAME circuit (cached by name)
Circuit c2 = cortex.circuit(cortex.name("kafka-cluster"));

assert c1 == c2;  // ✅ Singleton pattern via Name caching
```

**Name Implementations:**

| Implementation | Use Case | Key Characteristics |
|---------------|----------|---------------------|
| **InternedName** (default) | Production | Weak reference interning, pointer equality, identity map fast path |
| LinkedName | Legacy | Linked parent chain, no interning |
| SegmentArrayName | Specific cases | Array-based segments |
| LRUCachedName | High churn | LRU cache for name strings |

**Default Recommendation:** Use InternedName (default) - optimized for identity map fast path performance.

**Type-Based Default Names Create Singletons:**
```java
// No-arg methods use type-based default names for singleton behavior
Circuit c1 = cortex.circuit();  // Uses name "circuit"
Circuit c2 = cortex.circuit();  // Returns same circuit

Clock clk1 = circuit.clock();   // Uses name "clock"
Clock clk2 = circuit.clock();   // Returns same clock

assert c1 == c2;   // ✅ Same circuit
assert clk1 == clk2;  // ✅ Same clock
```

**Correct vs Incorrect Usage Pattern:**

⚠️ **CRITICAL:** Always use Cortex/Circuit factory methods, never direct construction.

```java
// ✅ CORRECT - Uses cache via Circuit.clock()
Clock clock1 = circuit.clock(cortex.name("timer"));
Clock clock2 = circuit.clock(cortex.name("timer"));
assert clock1 == clock2;  // Same instance (cached)

// ❌ WRONG - Bypasses cache via direct construction
Clock clock3 = new ClockImpl(cortex.name("timer"));
Clock clock4 = new ClockImpl(cortex.name("timer"));
assert clock3 != clock4;  // Different instances! (breaks singleton)
```

**Why constructors generate new Ids:**

Component constructors (ClockImpl, ConduitImpl, CircuitImpl) always generate a new Id because they're only called once per Name by the cache:

```java
// ClockImpl constructor - called ONLY by cache miss
public ClockImpl(Name name) {
    Id id = IdImpl.generate();  // New ID each time
    this.clockSubject = new SubjectImpl(id, name, ...);
}

// CircuitImpl.clock() - ensures constructor called once per Name
public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);
    //                             ^^^^ Only called if Name absent
}
```

**The computeIfAbsent() guarantee:**
- First call with Name → constructor called → Id generated → stored in cache
- Second call with same Name → cached instance returned → no new Id generated
- Direct construction → bypasses cache → breaks singleton pattern

See [Architecture Guide - Factory Method + Flyweight Pattern](ARCHITECTURE.md#1-name-based-component-caching) for detailed explanation.

### Factory Patterns

**What:** Pluggable factory interfaces for creating Name, Queue, and Registry instances throughout the framework.

**Three Core Factories:**

1. **NameFactory** - Creates Name instances
   - Default: `InternedNameFactory` (weak reference interning)
   - Ensures same name string → same Name instance
   - Enables identity map fast path via pointer equality

2. **QueueFactory** - Creates Queue instances
   - Default: `LinkedBlockingQueueFactory`
   - One queue per Circuit for ordered execution
   - Virtual thread per Circuit processes queue

3. **RegistryFactory** - Creates Registry instances
   - Default: `LazyTrieRegistryFactory`
   - Returns Map-compatible registries for Name-keyed collections
   - Used in: CortexRuntime (circuits, scopes), CircuitImpl (clocks), ConduitImpl (percepts), ScopeImpl (childScopes)

**Usage:**
```java
// ✅ Use defaults (optimized for production)
Cortex cortex = new CortexRuntime();

// Custom factories (advanced use cases)
Cortex cortex = new CortexRuntime(
    InternedNameFactory.getInstance(),
    LinkedBlockingQueueFactory.getInstance(),
    LazyTrieRegistryFactory.getInstance()
);
```

**Why Factories:**
- **Pluggability** - Swap implementations without changing code
- **Consistency** - Same factory used throughout component hierarchy
- **Performance** - Default implementations are optimized for production
- **Testing** - Easy to inject mock implementations

**Propagation Pattern:**
```java
// CortexRuntime injects factories down the hierarchy
Circuit circuit = new CircuitImpl(name, nameFactory, queueFactory, registryFactory);

// Circuit propagates to Conduit
Conduit conduit = new ConduitImpl(name, composer, queue, registryFactory, sequencer);

// Same factory used everywhere = consistent behavior
```

### Identity Map Fast Path

**What:** Performance optimization in LazyTrieRegistry using pointer equality (==) for InternedName instances instead of hash-based lookup.

**How It Works:**
```java
// LazyTrieRegistry with dual indexes
public class LazyTrieRegistry<T> implements Map<Name, T> {
    private final Map<Name, T> identityMap = new IdentityHashMap<>();  // Pointer equality
    private final Map<Name, T> registry = new ConcurrentHashMap<>();   // Hash equality

    @Override
    public T get(Object key) {
        if (!(key instanceof Name)) return null;
        Name name = (Name) key;

        // Fast path: identity map using pointer equality (==)
        T value = identityMap.get(name);  // ~2ns
        if (value != null) return value;   // ✅ Hit!

        // Fallback: hash-based lookup
        return registry.get(name);         // ~15-20ns
    }
}
```

**Why It's Fast:**
- **Identity check:** 2ns (simple pointer comparison: `this == that`)
- **Hash lookup:** 15-20ns (compute hashCode(), equals(), bucket search)
- **Speedup:** 5-10× for cached Name instances

**InternedName Enables This:**
```java
// InternedNameFactory ensures same string → same instance
Name name1 = nameFactory.createRoot("kafka.broker.1");
Name name2 = nameFactory.createRoot("kafka.broker.1");

// Pointer equality works!
assert name1 == name2;  // ✅ Same instance (interned)

// Identity map can use pointer equality
identityMap.get(name1);  // 2ns lookup via ==
```

**Performance Results:**
- **Cached pipe lookups:** 23ns → 4ns (5× faster)
- **Cached circuit lookups:** 28ns → 5ns (5× faster)
- **Hot-path emission:** 6.6ns → 3.3ns (2× faster)

**Design Philosophy:**
- Most lookups hit identity map (common case)
- Hash lookup provides fallback safety
- Zero behavioral change, pure performance win

### LazyTrieRegistry

**What:** Hybrid dual-index registry with identity map fast path AND hierarchical trie for subtree queries.

**Dual-Index Design:**
```java
public class LazyTrieRegistry<T> implements Map<Name, T> {
    // Fast path: O(1) identity-based lookup
    private final Map<Name, T> identityMap = new IdentityHashMap<>();

    // Primary storage: O(1) hash-based lookup
    private final Map<Name, T> registry = new ConcurrentHashMap<>();

    // Lazy trie: built on-demand for hierarchical queries
    private volatile TrieNode<T> trieRoot = null;
}
```

**Three Access Patterns:**

1. **Fast Path (identity map)** - 2-5ns
   ```java
   Pipe pipe = conduit.get(cachedName);  // Pointer equality
   ```

2. **Standard Lookup (hash map)** - 15-20ns
   ```java
   Circuit circuit = cortex.circuit(name);  // Hash equality
   ```

3. **Hierarchical Query (lazy trie)** - Built on first subtree query
   ```java
   // Cast to access trie-specific methods
   LazyTrieRegistry<Circuit> registry = (LazyTrieRegistry<Circuit>) cortex.circuits;
   Map<Name, Circuit> brokers = registry.getSubtree(cortex.name("kafka.brokers"));
   ```

**Lazy Trie Construction:**
- Trie is NOT built during normal operations
- Built only when `getSubtree()` or trie-specific methods called
- One-time construction cost: ~100-200μs for 1000 entries
- Future subtree queries are fast: ~50-100ns

**Map Interface Implementation:**
```java
// ✅ Works with standard Map operations
Map<Name, Circuit> circuits = (Map<Name, Circuit>) registryFactory.create();
circuits.computeIfAbsent(name, this::createCircuit);  // Standard Map API
circuits.values().forEach(Circuit::close);            // Standard iteration
circuits.containsKey(name);                           // Standard lookup

// ✅ PLUS trie-specific operations when needed
LazyTrieRegistry<Circuit> registry = (LazyTrieRegistry<Circuit>) circuits;
registry.getSubtree(prefix);  // Hierarchical query
```

**Where It's Used:**
- **CortexRuntime:** circuits, scopes maps
- **CircuitImpl:** clocks map
- **ConduitImpl:** percepts map (pipes/channels)
- **ScopeImpl:** childScopes map

**Performance Characteristics:**
- **Identity lookups:** 2-5ns (most common)
- **Hash lookups:** 15-20ns (fallback)
- **Subtree queries:** 50-100ns after trie built
- **Memory:** ~2× overhead (identity map + hash map)
- **Thread-safe:** All operations safe for concurrent access

**Why Lazy Construction:**
```java
// Most code never needs hierarchical queries
for (int i = 0; i < 1000000; i++) {
    pipe.emit(value);  // Identity map only, no trie needed
}

// Trie built ONLY if you need it
if (monitoring) {
    registry.getSubtree(prefix).values().forEach(this::collectMetrics);
}
```

**Trade-offs:**
- ✅ Fast identity lookups for common case
- ✅ Hierarchical queries when needed
- ✅ Map-compatible API
- ❌ 2× memory for dual indexes
- ❌ One-time trie construction cost (if used)

### State

**What:** Immutable collection of named slots (key-value pairs).

**Immutability:** Each `state()` call returns a **NEW State** instance. The original State is never modified.

**Types Supported:**
- Primitives: `int`, `long`, `float`, `double`, `boolean`
- Objects: `String`, `Name`, `State` (nested)

**Basic Usage:**
```java
State s1 = cortex.state();
State s2 = s1.state(cortex.name("count"), 42);
State s3 = s2.state(cortex.name("name"), "sensor1");

// Each state() returns a NEW State
assert s1 != s2;  // ✅ Different objects
assert s2 != s3;  // ✅ Different objects

// Fluent API (method chaining)
State state = cortex.state()
    .state(cortex.name("count"), 42)
    .state(cortex.name("name"), "sensor1")
    .state(cortex.name("active"), true);
```

**Duplicate Handling:**

State uses a **List internally**, allowing duplicate names (override pattern):

```java
State config = cortex.state()
    .state(cortex.name("timeout"), 30)   // Default value
    .state(cortex.name("retries"), 3)
    .state(cortex.name("timeout"), 60);  // Override (both exist!)

// State has 3 slots (2 have name "timeout")
assert config.stream().count() == 3;

// compact() removes duplicates, keeping LAST occurrence
State compacted = config.compact();
assert compacted.stream().count() == 2;  // Only 1 "timeout" remains (value: 60)
```

**Why Allow Duplicates?**

This supports the **configuration override pattern**:
- Start with defaults
- Apply environment-specific overrides
- Call `compact()` when ready to finalize

**Retrieving Values:**

```java
State state = cortex.state()
    .state(cortex.name("timeout"), 30)
    .state(cortex.name("timeout"), 60);

// value() returns LAST occurrence
Integer timeout = state.value(cortex.slot(cortex.name("timeout"), 0));
assert timeout == 60;  // Last value wins

// values() returns ALL occurrences
var allTimeouts = state.values(cortex.slot(cortex.name("timeout"), 0)).toList();
assert allTimeouts.equals(List.of(30, 60));  // Both values
```

**Type Matching:**

From William Louth's article: **"A State stores the type with the name, only matching when both are exact matches."**

State matches slots by BOTH name AND type:

```java
Name XYZ = cortex.name("XYZ");

// Create slots with different types
Slot<Integer> intSlot = cortex.slot(XYZ, 0);
Slot<String> stringSlot = cortex.slot(XYZ, "");

// Build state with same name, different types
State state = cortex.state()
    .state(XYZ, 3)      // Integer
    .state(XYZ, "4");   // String - does NOT override Integer!

// Query by (name, type) pair
Integer intValue = state.value(intSlot);
assert intValue == 3;  // Integer value unchanged

String stringValue = state.value(stringSlot);
assert stringValue.equals("4");  // String value found

// Both coexist because types differ
assert state.stream().count() == 2;
```

**Why Type Matching Matters:**

This prevents errors in heterogeneous configurations:

```java
// Different components use same name, different types
State config = cortex.state()
    .state(cortex.name("port"), 8080)        // Integer port number
    .state(cortex.name("port"), "HTTP/1.1"); // String protocol version

// Each component gets the right type
Integer portNumber = config.value(cortex.slot(cortex.name("port"), 0));
String protocol = config.value(cortex.slot(cortex.name("port"), ""));

assert portNumber == 8080;           // ✅ Type-safe lookup
assert protocol.equals("HTTP/1.1");  // ✅ No conflicts
```

### Slot

**What:** Immutable query/lookup object for type-safe State access with fallback support.

**Immutability:** Slots are immutable - created once, reused for multiple State queries.

**Purpose:** Slots serve THREE roles:
1. **Lookup key** - `slot.name()` identifies what to find
2. **Type safety** - `slot.type()` ensures compile-time type checking
3. **Fallback value** - `slot.value()` returned when name not found in State

**Pattern:** Create once, reuse for querying:

```java
// Create Slot with fallback value (100)
Slot<Integer> maxConnSlot = cortex.slot(name("max-connections"), 100);

// Use same Slot to query different States
State state1 = cortex.state().state(name("max-connections"), 200);
State state2 = cortex.state();  // Empty

int conn1 = state1.value(maxConnSlot);  // 200 (found in state)
int conn2 = state2.value(maxConnSlot);  // 100 (fallback from slot)
```

**Design Philosophy:**

From William Louth's [States and Slots article](https://humainary.io/blog/observability-x-states-and-slots/):

> **"Instead of relying on 'stringly-typed' key-value pairs that can lead to runtime errors and type mismatches, this design enforces type safety at compile-time."**

Slots replace error-prone patterns:

```java
// ❌ OLD: Stringly-typed (runtime errors)
int timeout = (Integer) map.get("timeout");  // ClassCastException risk!

// ✅ NEW: Type-safe Slot (compile-time safety)
Slot<Integer> timeoutSlot = cortex.slot(name("timeout"), 30);
int timeout = state.value(timeoutSlot);  // Type-safe + fallback
```

**Hierarchical State with Slot<State>:**

```java
// Inner state
State dbConfig = cortex.state()
    .state(name("host"), "localhost")
    .state(name("port"), 3306);

// Outer state containing inner state
State appConfig = cortex.state()
    .state(name("database"), dbConfig)  // Slot<State>
    .state(name("timeout"), 30);

// Query with fallback
Slot<State> dbSlot = cortex.slot(name("database"), cortex.state());
State config = appConfig.value(dbSlot);  // Returns dbConfig
```

**Key Insight:** Slots are **NOT mutable configuration holders**. They are immutable query objects that enable type-safe, fallback-aware State lookups.

### Substrate

**What:** Base interface for all components with an associated Subject.

**Purpose:** Everything in Substrates has a Subject for identification.

```java
interface Substrate {
    Subject subject();
}
```

**Implementations:**
- Channel, Circuit, Clock, Conduit, Container
- Scope, Sink, Source, Subscription, Subscriber

## Producer-Consumer Model

### Context Pattern

**What is Context?**

A **Context** is an abstraction that provides access to a Source for observation. In Substrates:
- **Conduit IS-A Context** - provides Source via `source()` method
- **Context provides Source** - Source enables dynamic subscription to observe Subjects/Channels
- Enables "live, adaptable connections" between information sources and observers

**Key Insight:** Rather than passive data collection, Context/Source enables active observation where:
- Subscribers conditionally register Pipes based on Subject characteristics
- Observers dynamically adapt to what they observe
- Connections are established on-demand as Subjects emit

**Example:**
```java
// Conduit is a Context
Conduit<Pipe<String>, String> conduit = circuit.conduit(name, composer);

// Context provides Source for observation
Source<String> source = conduit.source();  // Get Source from Context

// Subscribe to Source for dynamic observation
source.subscribe(
    cortex.subscriber(
        cortex.name("observer"),
        (subject, registrar) -> {
            // Conditionally register based on Subject
            if (subject.name().value().contains("important")) {
                registrar.register(msg -> processImportant(msg));
            }
        }
    )
);
```

### Terminology

| Term | Role | Description |
|------|------|-------------|
| **Channel** | Connector | Named conduit linking producers and consumers; provides access to Pipe |
| **Pipe** | Transport | Dual-purpose mechanism with `emit()` - used by producers to emit AND consumers to receive |
| **Source** | Observable context | Provides `subscribe()` for dynamic observation of Subjects/Channels |
| **Subscriber** | Observer factory | Registers consumer Pipes with Source when Subjects emit |
| **Conduit** | Context | Creates Channels and provides Source for subscription (Conduit IS-A Context) |
| **Container** | Pool manager | Manages collection of Conduits (Pool of Pools) |
| **Queue** | Coordinator | Coordinates Script execution within Circuit |
| **Script** | Executable unit | Work unit with `exec(Current)` method |

### The Flow

```
Producer code calls emit
  ↓
Conduit.get(name) → returns Channel-backed Pipe
  ↓
Pipe.emit(value)  [Producer uses Pipe to emit]
  ↓
Conduit's Source notified
  ↓
Source dispatches to Subscribers
  ↓
Subscriber callback invoked with Subject
  ↓
Registrar registers consumer Pipes  [Consumers use Pipes to receive]
  ↓
Consumer Pipes receive emissions
  ↓
Consumer processes data
```

### Example: Simple Producer-Consumer

```java
// Create circuit and conduit
Circuit circuit = cortex.circuit();
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("messages"),
    Composer.pipe()
);

// CONSUMER SIDE: Subscribe to observe
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) -> {
            // Register consumer Pipe
            registrar.register(msg -> {
                System.out.println("Consumer received: " + msg);
            });
        }
    )
);

// PRODUCER SIDE: Get pipe and emit
Pipe<String> pipe = conduit.get(cortex.name("producer1"));
pipe.emit("Hello!");  // Consumer will receive this

circuit.close();
```

### Multiple Consumers

```java
Source<String> source = conduit.source();

// Consumer 1: Logs to console
source.subscribe(
    cortex.subscriber(
        cortex.name("console"),
        (subject, registrar) ->
            registrar.register(msg -> System.out.println(msg))
    )
);

// Consumer 2: Writes to file
source.subscribe(
    cortex.subscriber(
        cortex.name("file-writer"),
        (subject, registrar) ->
            registrar.register(msg -> writeToFile(msg))
    )
);

// One emission reaches both consumers
pipe.emit("Broadcast message");
```

### Conditional Subscription

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("filter"),
        (subject, registrar) -> {
            // Only subscribe to channels named "important"
            if (subject.name().value().contains("important")) {
                registrar.register(msg -> processImportant(msg));
            }
        }
    )
);
```

### Container - Pool of Pools

**Container** is a higher-level abstraction that manages a **collection of Conduits** of the same emission type.

**Key Concept:** Container is a **"Pool of Pools"** - it implements `Pool<Pool<P>>`:
- `container.get(name)` returns a `Pool<P>` (which is a Conduit)
- Each unique name gets its own Conduit, created on-demand
- All Conduits in a Container share the same Composer and optional transformation pipeline

**Type Signature:**
```java
interface Container<P, E> extends Pool<P>, Component<E>

// Circuit creates Container with nested Pool/Source types:
Container<Pool<Pipe<String>>, Source<String>> container =
    circuit.container(name, Composer.pipe());
```

**Basic Usage:**
```java
// Create container for stock market data
Container<Pool<Pipe<Order>>, Source<Order>> stockOrders = circuit.container(
    cortex.name("stock-market"),
    Composer.pipe()
);

// Get Pool for specific stock (creates Conduit on-demand)
Pool<Pipe<Order>> appleOrders = stockOrders.get(cortex.name("AAPL"));
Pool<Pipe<Order>> msftOrders = stockOrders.get(cortex.name("MSFT"));

// From each Pool, get Pipes for different order types
Pipe<Order> buyOrders = appleOrders.get(cortex.name("BUY"));
Pipe<Order> sellOrders = appleOrders.get(cortex.name("SELL"));

// Emit orders
buyOrders.emit(new Order("AAPL", "BUY", 100));
sellOrders.emit(new Order("AAPL", "SELL", 50));
```

**When to Use Container:**
- Managing dynamic collections of similar entities (stocks, devices, users)
- Want automatic Conduit creation for new entities
- Need to observe when new entity types are created
- All entities share same processing logic (Composer + transformations)

**When to Use Multiple Conduits Instead:**
- Fixed set of known entities
- Each entity needs different processing logic
- No need to observe entity creation

### Container Hierarchical Subscription

Container provides a powerful **hierarchical subscription pattern** - it emits **Conduit Sources** when new Conduits are created.

**Container's Source Type:**
```java
Container<Pool<P>, Source<E>> container = ...;

// container.source() returns Source<Source<E>> - nested!
Source<Source<E>> containerSource = container.source();
```

**Hierarchical Subscription Flow:**
```
1. Subscribe to Container.source()
     ↓
2. Container emits Conduit.source() when NEW Conduit created
     ↓
3. Subscriber receives Conduit's Source
     ↓
4. Subscriber can then subscribe to that Conduit's emissions
```

**Example: Observing All Stocks**
```java
// Create container
Container<Pool<Pipe<Order>>, Source<Order>> stockOrders = circuit.container(
    cortex.name("stock-market"),
    Composer.pipe()
);

// Subscribe to Container - receives Conduit Sources
stockOrders.source().subscribe(
    cortex.subscriber(
        cortex.name("stock-observer"),
        (conduitSubject, registrar) -> {
            // conduitSubject.name() = "stock-market.AAPL" (for example)
            System.out.println("New stock Conduit created: " + conduitSubject.name());

            // Register to receive the Conduit's Source
            registrar.register(conduitSource -> {
                // conduitSource is Source<Order> for this stock
                // Subscribe to all orders for this stock
                conduitSource.subscribe(
                    cortex.subscriber(
                        cortex.name("order-logger"),
                        (channelSubject, innerRegistrar) -> {
                            // channelSubject.name() = "stock-market.AAPL.BUY" (for example)
                            innerRegistrar.register(order -> {
                                System.out.println("Order: " + order);
                            });
                        }
                    )
                );
            });
        }
    )
);

// First access to "AAPL" creates Conduit AND emits its Source
Pool<Pipe<Order>> applePool = stockOrders.get(cortex.name("AAPL"));
// ← stock-observer receives AAPL Conduit's Source and subscribes

// Second access returns cached Conduit (no emission)
Pool<Pipe<Order>> applePool2 = stockOrders.get(cortex.name("AAPL"));
// ← No emission (applePool == applePool2, cached)

// Different stock creates NEW Conduit (emits again)
Pool<Pipe<Order>> msftPool = stockOrders.get(cortex.name("MSFT"));
// ← stock-observer receives MSFT Conduit's Source and subscribes
```

**Key Points:**
- Container emits **only on FIRST** `get(name)` call for a new name
- Subsequent calls return the cached Conduit (no emission)
- Emission contains `Capture<Source<E>>` pairing Conduit's Subject with its Source
- Enables dynamic subscription to entities as they're created
- Each Conduit gets hierarchical name: `container-name.conduit-name`

**Reference:** [Observability X - Containers](https://humainary.io/blog/observability-x-containers/)

### Queue, Script, and Current

The Queue subsystem provides **coordination and scheduling** within a Circuit's processing pipeline.

**Quick Overview:**
- **Queue** - Coordinates execution of Scripts within Circuit
- **Script** - Executable unit of work with `exec(Current)` method
- **Current** - @Temporal execution context providing access to Circuit's queue

**Key Methods:**
```java
Queue queue = circuit.queue();
queue.post(Script script);           // Post script for execution
queue.await();                       // Block until queue empty

interface Script {
    void exec(Current current);      // Execute with context
}

interface Current extends Substrate {
    void post(Runnable runnable);    // Post async work from script
}
```

**Usage Example:**
```java
Queue queue = circuit.queue();

queue.post(current -> {
    System.out.println("Script executing in circuit context");

    // Post follow-up work
    current.post(() -> {
        System.out.println("Async follow-up work");
    });
});

queue.await();  // Wait for all scripts to complete
```

**QoS Architecture:** Queue uses simple **FIFO ordering** (LinkedBlockingQueue). Quality of Service is handled at the **Circuit/Conduit level**, not at the Script level within a Queue:
- Need different priorities? → Create separate Circuits (each has its own Queue)
- Need different processing characteristics? → Use separate Conduits
- Keep Queue semantics simple: strict FIFO processing

**Reference:** [Observability X - Queues, Scripts, and Currents](https://humainary.io/blog/observability-x-queues-scripts-and-currents/)

**Details:** See [ADVANCED.md - Queue & Script Subsystem](ADVANCED.md#queue--script-subsystem) for complete documentation.

## Transformation Pipelines

### Sequencer & Segment

**Sequencer** configures a **Segment** (transformation pipeline).

**Available Transformations:**

| Transformation | Purpose | Example |
|---------------|---------|---------|
| `guard(Predicate)` | Filter emissions | `guard(n -> n > 0)` |
| `limit(long)` | Max emission count | `limit(100)` |
| `reduce(init, BinaryOp)` | Stateful aggregation | `reduce(0, Integer::sum)` |
| `replace(UnaryOp)` | Transform values | `replace(n -> n * 2)` |
| `diff()` | Emit only changes | `diff()` |
| `sample(int)` | Every Nth emission | `sample(10)` |
| `sift(Comparator, Seq)` | Complex filtering | `sift(Integer::compareTo, s -> s.above(0))` |

### Example: Filter and Limit

```java
// Transformation configured at CONDUIT level
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),            // ← Conduit name
    Composer.pipe(segment -> segment   // ← Transformation applies to ALL channels
        .guard(n -> n > 0)      // Only positive
        .guard(n -> n % 2 == 0) // Only even
        .limit(50)              // Max 50 emissions
    )
);

// Get a Channel (Pipe) from the Conduit
Pipe<Integer> pipe = conduit.get(cortex.name("counter"));  // ← Channel name

// Emit 200 numbers
for (int i = -100; i < 100; i++) {
    pipe.emit(i);
}
// Only 50 positive even numbers will be emitted (2, 4, 6, ..., 100)
```

**Key Insight:** The transformation pipeline is configured once at the **Conduit level**. All Channels created from `conduit.get()` share the same transformation, regardless of their individual names.

**Example showing shared transformations:**
```java
// Same conduit with transformation
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(segment -> segment.guard(n -> n > 0))  // Only positive
);

// Two different channels from the SAME conduit
Pipe<Integer> channel1 = conduit.get(cortex.name("producer1"));
Pipe<Integer> channel2 = conduit.get(cortex.name("producer2"));

channel1.emit(-5);  // ❌ Filtered out (negative)
channel1.emit(10);  // ✅ Passes through (positive)

channel2.emit(-3);  // ❌ Filtered out (negative)
channel2.emit(7);   // ✅ Passes through (positive)

// BOTH channels apply the same guard(n -> n > 0) transformation
```

### Example: Sampling

```java
segment -> segment.sample(10)  // Every 10th emission

// Emit 100 values → only 10 reach consumers (10, 20, 30, ..., 100)
```

### Example: Reduction (Running Total)

```java
segment -> segment.reduce(
    0,                    // Initial value
    Integer::sum          // Accumulator
)

// Input:  1, 2, 3, 4, 5
// Output: 1, 3, 6, 10, 15 (running total)
```

### Example: Change Detection

```java
segment -> segment.diff()

// Input:  1, 1, 2, 2, 2, 3, 1
// Output: 1, 2, 3, 1 (only when value changes)
```

### Example: Sift (Comparator-based Filtering)

```java
segment -> segment.sift(
    Integer::compareTo,
    sift -> sift
        .above(0)      // Greater than 0
        .max(100)      // Less than or equal to 100
        .high()        // Only new highs
)

// Input:  -5, 10, 5, 20, 15, 30, 25, 110
// Output: 10, 20, 30 (positives, ≤100, new highs only)
```

### Chaining Transformations

```java
segment -> segment
    .guard(n -> n > 0)                    // 1. Filter positives
    .replace(n -> n * 2)                  // 2. Double values
    .sift(Integer::compareTo, s -> s.high()) // 3. Only new highs
    .limit(10)                            // 4. Max 10 emissions

// Input:  -1, 5, 3, 10, 8, 20, 15
// After guard:   5, 3, 10, 8, 20, 15
// After replace: 10, 6, 20, 16, 40, 30
// After sift:    10, 20, 40
// After limit:   10, 20, 40 (under limit)
```

## Resource Management

### Resource Interface

All major components implement `Resource`:

```java
interface Resource {
    @Idempotent
    void close();  // Cleanup and release resources
}
```

**Implementations:**
- **Component** (Circuit, Clock, Container)
- **Subscription**
- **Sink**

**@Idempotent Annotation:**

The `@Idempotent` annotation on `close()` indicates it's safe to call multiple times with the same effect:

```java
Circuit circuit = cortex.circuit();
circuit.close();
circuit.close();  // ✅ Safe - idempotent (no-op on second call)
```

This is a method-level annotation (unrelated to `Id` or `@Identity`) that documents safe repeated execution.

### Manual Lifecycle

```java
Circuit circuit = cortex.circuit();
Clock clock = circuit.clock();

// Use resources...

// Manual cleanup
clock.close();
circuit.close();
```

### Scope-based Lifecycle

**Scope** manages resource lifecycle hierarchically:

```java
Scope scope = cortex.scope(cortex.name("transaction"));

// Register resources with scope
Circuit circuit = scope.register(cortex.circuit());
Clock clock = scope.register(circuit.clock());

// All registered resources closed when scope closes
scope.close();
```

### Closure Pattern (ARM)

**Closure** provides Automatic Resource Management:

```java
Scope scope = cortex.scope();
Circuit circuit = cortex.circuit();

scope.closure(circuit).consume(c -> {
    // Use circuit
    Pipe<String> pipe = c.conduit(name, Composer.pipe())
        .get(cortex.name("producer"));
    pipe.emit("message");

    // Circuit automatically closed when block exits
});
```

### Hierarchical Scopes

```java
Scope parent = cortex.scope(cortex.name("parent"));
Scope child1 = parent.scope(cortex.name("child1"));
Scope child2 = parent.scope(cortex.name("child2"));

Circuit c1 = child1.register(cortex.circuit());
Circuit c2 = child2.register(cortex.circuit());

// Close parent → closes child1, child2, c1, c2 in order
parent.close();
```

### Try-with-Resources

```java
try (Scope scope = cortex.scope()) {
    Circuit circuit = scope.register(cortex.circuit());
    // Use circuit
} // Scope auto-closes, cleaning up circuit
```

## Observability Model

### Subject-based Observation

Every component has a **Subject** that identifies it:

```java
Pipe<String> pipe = conduit.get(cortex.name("sensor1"));
Subject subject = pipe.subject();

System.out.println("Name: " + subject.name());
System.out.println("Type: " + subject.type());
System.out.println("ID: " + subject.id());
```

### Dynamic Subscription

Subscribers are notified when Subjects emit:

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("observer"),
        (subject, registrar) -> {
            System.out.println("Subject emitting: " + subject.name());
            registrar.register(value -> {
                System.out.println("Value: " + value);
            });
        }
    )
);
```

### Hierarchical Names for Routing

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("router"),
        (subject, registrar) -> {
            // Route based on name hierarchy
            subject.name().enclosure().ifPresent(parent -> {
                Pipe<?> targetPipe = getTargetPipe(parent);
                registrar.register(targetPipe);
            });
        }
    )
);
```

### Clock-based Observation

```java
Clock clock = circuit.clock(cortex.name("metrics-collector"));

clock.consume(
    cortex.name("collector"),
    Clock.Cycle.SECOND,
    instant -> {
        // Collect metrics every second
        collectMetrics(instant);
    }
);
```

### Sink for Capture

**Sink** captures emissions for later analysis:

```java
Sink<String> sink = cortex.sink(conduit.source());

// Emit some data
pipe.emit("event1");
pipe.emit("event2");

// Drain captured events
sink.drain().forEach(capture -> {
    System.out.println("Subject: " + capture.subject());
    System.out.println("Emission: " + capture.emission());
});

sink.close();
```

## Advanced Patterns

### Fan-out (One Producer, Multiple Consumers)

```java
Source<String> source = conduit.source();

// Consumer 1
source.subscribe(subscriber1);

// Consumer 2
source.subscribe(subscriber2);

// Consumer 3
source.subscribe(subscriber3);

// One emit reaches all three
pipe.emit("broadcast");
```

### Fan-in (Multiple Producers, One Consumer)

```java
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("aggregator"),
    Composer.pipe()
);

// One consumer
conduit.source().subscribe(subscriber);

// Multiple producers
Pipe<String> producer1 = conduit.get(cortex.name("p1"));
Pipe<String> producer2 = conduit.get(cortex.name("p2"));
Pipe<String> producer3 = conduit.get(cortex.name("p3"));

producer1.emit("from p1");
producer2.emit("from p2");
producer3.emit("from p3");
// All reach same consumer
```

### Relay Pattern

```java
// Source conduit
Conduit<Pipe<String>, String> source = circuit.conduit(
    cortex.name("source"),
    Composer.pipe()
);

// Target conduit
Conduit<Pipe<String>, String> target = circuit.conduit(
    cortex.name("target"),
    Composer.pipe()
);

// Relay: subscribe to source, emit to target
source.source().subscribe(
    cortex.subscriber(
        cortex.name("relay"),
        (subject, registrar) -> {
            Pipe<String> targetPipe = target.get(subject.name());
            registrar.register(targetPipe);
        }
    )
);
```

### Transform Pattern

```java
Conduit<Pipe<Integer>, Integer> input = circuit.conduit(
    cortex.name("input"),
    Composer.pipe()
);

Conduit<Pipe<String>, String> output = circuit.conduit(
    cortex.name("output"),
    Composer.pipe()
);

// Transform integers to strings
input.source().subscribe(
    cortex.subscriber(
        cortex.name("transformer"),
        (subject, registrar) -> {
            Pipe<String> outputPipe = output.get(subject.name());
            registrar.register(n ->
                outputPipe.emit("Number: " + n)
            );
        }
    )
);
```

## Key Insights

### 1. Everything Has a Subject

All components implement `Substrate`, providing a `Subject` for identification and observation.

### 2. Pipes Are Dual-Purpose

Pipes have `emit()` method and are used:
- **By producers:** Conduit.get() returns a Pipe (backed by Channel) for emitting
- **By consumers:** Subscriber registers consumer Pipes to receive emissions
- **Internally:** Source implements Pipe for dispatching to registered consumer Pipes

### 3. Source = Observable Context

**Source** is not a producer - it's an **observable context** for dynamic subscription:
- Obtained from a Context (Conduit provides Source via `source()`)
- Enables subscription to observe Subjects/Channels
- Subscribers register Pipes conditionally based on Subject characteristics
- Source dispatches emissions to registered consumer Pipes
- Context pattern allows "live, adaptable connections" between sources and observers

### 4. @Temporal = Don't Retain

Types marked `@Temporal` are transient:
- **Registrar** - Used only during subscriber callback
- **Sift** - Used only during sequencer configuration
- **Closure** - Used only for ARM pattern execution

### 5. Segment Is Mutable

Unlike most Substrates types, **Segment is mutable**:
- Returns `this` for fluent chaining
- Required because `Sequencer.apply()` is void
- Transformations accumulate on same object

### 6. Virtual Threads = Daemon

All background threads are virtual and daemon:
- Lightweight (millions possible)
- Auto-cleanup on JVM exit
- No explicit shutdown needed (but Circuit.close() is cleaner)

## Next Steps

- Read [Architecture Guide](ARCHITECTURE.md) for implementation details
- See [Examples](examples/README.md) for complete working examples
- Review [Substrates API JavaDoc](https://github.com/humainary-io/substrates-api-java)
- Explore [William Louth's Blog](https://humainary.io/blog)
