# Container Implementation Alignment

**Date**: 2025-10-11
**Article**: https://humainary.io/blog/observability-x-containers/
**Status**: ⚠️ PARTIALLY ALIGNED (Implementation differs from article's intent)

## Summary

Our `Container` implementation is partially aligned with the Humainary Containers article. The interface structure and basic composition are correct, but our implementation wraps a **single Conduit** rather than managing a **collection of Conduits** as the article describes. This is a significant architectural difference in interpretation.

## Article's Key Requirements

### 1. ⚠️ Container Definition

**Article States:**
> "A Container is a collection of Conduits of the same emittance data type"
> "Provides runtime management of Conduit collections"

**API Definition:**
```java
@Provided
interface Container<P, E>
  extends Component<Source<E>>,
          Pool<Pool<P>> {
}
```

**Breaking Down the Interface:**
- `Container<P, E>` - P is percept type, E is emission type
- `extends Component<Source<E>>` - Container is a Component that provides `Source<E>` via `source()`
- `extends Pool<Pool<P>>` - Container is a Pool of Pools

**What This Means:**
- `Container.get(Name)` returns `Pool<P>` (a Pool representing one Conduit)
- `Container.source()` returns `Source<Source<E>>` (nested Source for Conduit sources)
- Container manages **multiple Conduits**, each identified by Name

**Our Implementation:**

```java
// ContainerImpl.java:31
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Pool<P> pool;              // ← Single Pool (one Conduit)
    private final Source<E> eventSource;      // ← Single Source
    private final SourceImpl<Source<E>> containerSource;
```

**Issue:** We wrap a **single Conduit** (Pool + Source), not a **collection of Conduits**.

**Verification**: ⚠️ **DIFFERENT INTERPRETATION** - We implement Container as a wrapper, article describes it as a collection manager

---

### 2. ✅ Interface Structure

**Article States:**
> `Container<P, E> extends Component<Source<E>>, Pool<Pool<P>>`

**Our Implementation:**

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    // implements Container<Pool<P>, Source<E>>
    // which extends Component<Source<E>> + Pool<Pool<P>>
}
```

**Verification**: ✅ Correct - Interface structure matches

---

### 3. ⚠️ get(Name) Returns Pool

**Article States:**
> "get() method retrieves Pool for a specific stock/Conduit"
> "Allows dynamic Conduit creation and access"

**Article's Expected Behavior:**
```java
var container = circuit.container(ORDERS, pipe(Order.class));

// Get Pool for APPL Conduit
Pool<Pipe<Order>> applConduit = container.get(cortex.name("APPL"));

// Get Pipe from that Conduit's Pool
Pipe<Order> buyPipe = applConduit.get(BUY);
buyPipe.emit(new Order(200, 1000_000));
```

**Our Implementation:**

```java
// ContainerImpl.java:75-77
@Override
public Pool<P> get(Name name) {
    return pool;  // ← Returns THE SAME pool for ANY name
}
```

**Issue:**
- Article expects: `get(name)` → returns different Pool for each Conduit
- Our implementation: `get(name)` → returns same Pool regardless of name

**Expected Behavior:**
```java
// Should create/retrieve different Conduits by name
@Override
public Pool<P> get(Name name) {
    return conduits.computeIfAbsent(name, n -> {
        Conduit<P, E> conduit = circuit.conduit(n, composer);
        return conduit;  // Conduit IS a Pool<P>
    });
}
```

**Verification**: ⚠️ **INCORRECT** - Doesn't create separate Conduits per name

---

### 4. ✅ source() Returns Source<Source<E>>

**Article States:**
> "source() returns Source<Source<E>>"
> "Provides access to create Sources for underlying Conduits"

**Our Implementation:**

```java
// ContainerImpl.java:34, 80-82
private final SourceImpl<Source<E>> containerSource;

@Override
public Source<Source<E>> source() {
    return containerSource;
}
```

**Verification**: ✅ Correct - Returns nested Source type

---

### 5. ⚠️ Nested Access Pattern

**Article's Example:**
```java
var container = circuit.container(ORDERS, pipe(Order.class));

container
  .get(CORTEX.name("APPL"))  // Get Conduit for APPL stock
  .get(BUY)                  // Get BUY Pipe from that Conduit
  .emit(new Order(200, 1000_000));
```

**What This Should Do:**
1. `container.get("APPL")` → returns Pool (Conduit for APPL)
2. `.get(BUY)` → returns Pipe (BUY percept from APPL Conduit)
3. `.emit(...)` → emits Order to APPL's BUY Pipe

**Our Implementation:**

```java
Container<Pool<Pipe<Order>>, Source<Order>> container =
    circuit.container(cortex.name("orders"), Composer.pipe());

Pool<Pipe<Order>> pool = container.get(cortex.name("APPL"));
// pool is the Conduit itself, not a separate Pool per stock

Pipe<Order> buyPipe = pool.get(cortex.name("BUY"));
buyPipe.emit(new Order(200, 1000_000));
```

**Current Behavior:**
- `container.get("APPL")` → returns THE SAME Pool
- `container.get("MSFT")` → returns THE SAME Pool (not separate Conduits)

**Expected Behavior:**
- `container.get("APPL")` → returns Pool for APPL Conduit
- `container.get("MSFT")` → returns Pool for MSFT Conduit (different)

**Verification**: ⚠️ **INCORRECT** - Missing separate Conduit management

---

### 6. ✅ Created via Circuit.container()

**Article States:**
> `var container = circuit.container(ORDERS, pipe(Order.class));`

**Our Implementation:**

```java
// CircuitImpl.java:128-137
@Override
public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer) {
    checkClosed();
    Objects.requireNonNull(name, "Container name cannot be null");
    Objects.requireNonNull(composer, "Composer cannot be null");

    // Create a Conduit that will use the Composer to create percepts
    Conduit<P, E> conduit = conduit(name, composer);

    // Container wraps the Conduit, exposing it as Pool + Source
    return new ContainerImpl<>(conduit, conduit.source());
}
```

**Verification**: ✅ Correct - Circuit creates Container with Composer

---

### 7. ⚠️ Collection Management

**Article States:**
> "A Container is a collection of Conduits"
> "Provides runtime management of Conduit collections"
> "Allows delegation of Conduit tracking to the Substrates runtime"

**Expected Architecture:**

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Circuit circuit;
    private final Composer<P, E> composer;
    private final Map<Name, Conduit<P, E>> conduits = new ConcurrentHashMap<>();

    @Override
    public Pool<P> get(Name name) {
        // Get or create Conduit for this name
        return conduits.computeIfAbsent(name, n -> {
            Conduit<P, E> conduit = circuit.conduit(n, composer);
            return conduit;  // Conduit implements Pool<P>
        });
    }

    @Override
    public Source<Source<E>> source() {
        // Return Source that emits Conduit Sources
        // When new Conduit is created, emit its Source
    }
}
```

**Our Architecture:**

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Pool<P> pool;         // ← Single Pool
    private final Source<E> eventSource; // ← Single Source

    @Override
    public Pool<P> get(Name name) {
        return pool;  // ← Same Pool for all names
    }
}
```

**Verification**: ⚠️ **INCORRECT** - Doesn't manage Conduit collection

---

## What Our Implementation Does Correctly

### ✅ Wrapper Pattern

Our Container correctly wraps a Conduit to provide:
1. Pool access via `get(Name)` - returns the Conduit as Pool
2. Source access via `source()` - returns nested Source<Source<E>>

```java
// Circuit creates Container by wrapping Conduit
Conduit<P, E> conduit = conduit(name, composer);
return new ContainerImpl<>(conduit, conduit.source());

// Container exposes Conduit's Pool and Source
Pool<P> pool = container.get(anyName);  // Returns Conduit
Source<Source<E>> source = container.source();
```

This works for the use case: **Container as a named wrapper around a single Conduit**.

---

## What Our Implementation Misses

### ❌ Collection Management

Our Container doesn't manage multiple Conduits. It wraps one Conduit and returns it regardless of the name passed to `get()`.

**Missing Functionality:**
```java
// Article's expected behavior:
var container = circuit.container(STOCKS, pipe(Trade.class));

var applTrader = container.get(name("APPL"));  // Conduit for APPL
var msftTrader = container.get(name("MSFT"));  // Conduit for MSFT (different!)

applTrader.get(BUY).emit(trade1);  // Goes to APPL Conduit
msftTrader.get(SELL).emit(trade2); // Goes to MSFT Conduit (separate)
```

**Current behavior:**
```java
var container = circuit.container(name("stocks"), Composer.pipe());

var applTrader = container.get(name("APPL"));  // Returns Conduit
var msftTrader = container.get(name("MSFT"));  // Returns SAME Conduit!

assertThat(applTrader).isSameAs(msftTrader);  // TRUE (same object)
```

---

## Correct Implementation (Article's Intent)

Based on the article, Container should manage a collection of Conduits:

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Name containerName;
    private final Circuit circuit;
    private final Composer<P, E> composer;
    private final Map<Name, Conduit<P, E>> conduits = new ConcurrentHashMap<>();
    private final SourceImpl<Source<E>> containerSource;

    public ContainerImpl(Name name, Circuit circuit, Composer<P, E> composer) {
        this.containerName = name;
        this.circuit = circuit;
        this.composer = composer;
        this.containerSource = new SourceImpl<>(name);
    }

    @Override
    public Subject subject() {
        return new SubjectImpl(
            IdImpl.generate(),
            containerName,
            StateImpl.empty(),
            Subject.Type.CONTAINER
        );
    }

    @Override
    public Pool<P> get(Name name) {
        // Get or create Conduit for this name
        Conduit<P, E> conduit = conduits.computeIfAbsent(name, n -> {
            Conduit<P, E> newConduit = circuit.conduit(
                containerName.name(n),  // Hierarchical name
                composer
            );

            // Emit new Conduit's Source to Container's Source
            containerSource.emit(newConduit.source());

            return newConduit;
        });

        // Conduit implements Pool<P>, so return it directly
        return conduit;
    }

    @Override
    public Source<Source<E>> source() {
        return containerSource;
    }

    @Override
    public void close() {
        // Close all managed Conduits
        conduits.values().forEach(conduit -> {
            try {
                if (conduit instanceof AutoCloseable) {
                    ((AutoCloseable) conduit).close();
                }
            } catch (Exception e) {
                // Log but continue
            }
        });
        conduits.clear();
    }
}
```

**Usage with Correct Implementation:**
```java
Container<Pool<Pipe<Order>>, Source<Order>> container =
    circuit.container(cortex.name("orders"), Composer.pipe());

// Each get() creates/retrieves different Conduit
Pool<Pipe<Order>> applOrders = container.get(cortex.name("APPL"));
Pool<Pipe<Order>> msftOrders = container.get(cortex.name("MSFT"));

assertThat(applOrders).isNotSameAs(msftOrders);  // Different Conduits

// Nested access pattern works correctly
container
    .get(cortex.name("APPL"))  // APPL Conduit
    .get(cortex.name("BUY"))   // BUY Pipe
    .emit(new Order(200, 1000_000));

container
    .get(cortex.name("MSFT"))  // MSFT Conduit (different!)
    .get(cortex.name("SELL"))  // SELL Pipe
    .emit(new Order(100, 500_000));
```

---

## Interface Compliance

### Container Interface (from Substrates API)

```java
@Provided
interface Container<P, E>
  extends Component<Source<E>>,
          Pool<Pool<P>> {
}
```

**Expanded:**
```java
interface Container<P, E> {
    // From Substrate
    Subject subject();

    // From Component<Source<E>>
    Source<Source<E>> source();

    // From Pool<Pool<P>>
    Pool<P> get(Name name);

    // From Resource (via Component)
    void close();
}
```

### Our Implementation

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    @Override
    public Subject subject() { ... }           // ✓ From Substrate

    @Override
    public Source<Source<E>> source() { ... }  // ✓ From Component

    @Override
    public Pool<P> get(Name name) { ... }      // ⚠️ Returns same Pool

    @Override
    public void close() { ... }                // ✓ From Resource
}
```

**Verification**: ⚠️ Partial compliance - Methods present but `get()` semantics differ

---

## Test Coverage

**Current Tests (kafka-obs):**

```java
// ✓ Container creation
@Test
void shouldCreateContainerWithPoolAndSource() {
    Container<Pool<String>, Source<String>> container =
        new ContainerImpl<>(pool, source);
    assertThat(container).isNotNull();
}

// ✓ get() delegation (but returns same Pool)
@Test
void shouldDelegateGetToPool() {
    Pool<String> poolResult = container.get(NameImpl.of("test"));
    assertThat(poolResult).isSameAs(pool);  // Same pool returned
}

// ✓ Source functionality
@Test
void shouldProvideSourceForSubscriptions() {
    container.eventSource().subscribe(...);
    source.emit("test-event");
    // Subscribers notified
}
```

**Missing Tests (for article's intent):**

```java
// ✗ Different Conduits per name
@Test
void shouldCreateSeparateConduitsPerName() {
    Pool<Pipe<Order>> appl = container.get(name("APPL"));
    Pool<Pipe<Order>> msft = container.get(name("MSFT"));

    assertThat(appl).isNotSameAs(msft);  // FAILS with current impl
}

// ✗ Nested access pattern
@Test
void shouldSupportNestedAccessPattern() {
    container
        .get(name("APPL"))
        .get(name("BUY"))
        .emit(order);

    // Different Conduit
    container
        .get(name("MSFT"))
        .get(name("SELL"))
        .emit(order);

    // Verify separate routing
}

// ✗ Container Source emits Conduit Sources
@Test
void shouldEmitConduitSourcesWhenConduitsCreated() {
    List<Source<Order>> sources = new ArrayList<>();

    container.source().subscribe((subject, registrar) -> {
        registrar.register(conduitSource -> sources.add(conduitSource));
    });

    container.get(name("APPL"));  // Should emit APPL Conduit's Source
    container.get(name("MSFT"));  // Should emit MSFT Conduit's Source

    assertThat(sources).hasSize(2);  // FAILS with current impl
}
```

---

## Alignment Summary

| Concept | Article Requirement | Our Implementation | Status |
|---------|-------------------|-------------------|--------|
| Container interface | ✓ | Container<Pool<P>, Source<E>> | ✅ |
| Extends Component<Source<E>> | ✓ | Implements correctly | ✅ |
| Extends Pool<Pool<P>> | ✓ | Implements correctly | ✅ |
| get(Name) returns Pool | ✓ | Returns Pool (but same one) | ⚠️ |
| source() returns Source<Source<E>> | ✓ | Returns nested Source | ✅ |
| **Collection of Conduits** | ✓ | **Wraps single Conduit** | ❌ |
| **Separate Conduit per name** | ✓ | **Same Conduit for all names** | ❌ |
| **Dynamic Conduit creation** | ✓ | **No dynamic creation** | ❌ |
| Created via Circuit.container() | ✓ | CircuitImpl.container() | ✅ |
| Uses Composer | ✓ | Passes to Conduit creation | ✅ |
| Nested access pattern | ✓ | **Doesn't work as intended** | ❌ |

---

## Conclusion

**Status: ⚠️ PARTIALLY ALIGNED**

Our `Container` implementation has **mixed alignment** with the Humainary Containers article:

### ✅ **Correct:**
1. Interface structure: `Container<Pool<P>, Source<E>>`
2. Extends `Component<Source<E>>` and `Pool<Pool<P>>`
3. Returns `Pool<P>` from `get(Name)`
4. Returns `Source<Source<E>>` from `source()`
5. Created via `Circuit.container(Name, Composer)`
6. Proper Subject and Resource management

### ❌ **Incorrect (Major):**
1. **Container as Collection** - Article describes Container as managing **multiple Conduits**, we wrap **single Conduit**
2. **Dynamic Conduit Creation** - Article expects `get(name)` to create/retrieve different Conduits per name, we return same Conduit
3. **Separate Routing** - Article's nested pattern routes to different Conduits, ours routes to same Conduit

### Interpretation Difference

There are **two valid interpretations** of Container:

1. **Article's Intent (Collection Manager):**
   - Container manages **multiple Conduits**
   - Each `get(name)` returns different Conduit
   - Use case: Stock trading (one Conduit per stock symbol)

2. **Our Implementation (Single Conduit Wrapper):**
   - Container wraps **one Conduit**
   - `get(name)` returns same Conduit
   - Use case: Exposing Conduit as Container for API consistency

**Recommendation:** Implement the collection manager pattern to match article's intent and support the stock trading use case shown in the article.

---

## References

- **Article**: https://humainary.io/blog/observability-x-containers/
- **Key Quote**: "A Container is a collection of Conduits of the same emittance data type"
- **Pattern**: `container.get(name("APPL")).get(BUY).emit(order)`
- **Interface**: `Container<P, E> extends Component<Source<E>>, Pool<Pool<P>>`
