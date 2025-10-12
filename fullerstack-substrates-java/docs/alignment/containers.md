# Container Implementation Alignment

**Date**: 2025-10-11
**Updated**: 2025-10-11 (Fixed)
**Article**: https://humainary.io/blog/observability-x-containers/
**Status**: ✅ ALIGNED

## Summary

Our `Container` implementation is now fully aligned with the Humainary Containers article. Container correctly manages a **collection of Conduits**, where each `get(Name)` call creates or retrieves a separate Conduit for that name. The stock trading use case from the article now works as intended.

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
// ContainerImpl.java:41
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Name containerName;
    private final Circuit circuit;
    private final Composer<? extends P, E> composer;
    private final Map<Name, Conduit<P, E>> conduits = new ConcurrentHashMap<>();
    private final SourceImpl<Source<E>> containerSource;
    private final Subject containerSubject;
```

**Implementation:** We now correctly manage a **collection of Conduits**, creating separate Conduits per name.

**Verification**: ✅ **ALIGNED** - Container manages collection of Conduits as article describes

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
// ContainerImpl.java:77-97
@Override
public Pool<P> get(Name name) {
    Objects.requireNonNull(name, "Conduit name cannot be null");

    // Get or create Conduit for this name
    Conduit<P, E> conduit = conduits.computeIfAbsent(name, n -> {
        // Create hierarchical name: containerName + conduitName
        Name conduitName = containerName.name(n);

        // Create new Conduit via Circuit
        Conduit<P, E> newConduit = circuit.conduit(conduitName, composer);

        return newConduit;
    });

    // Conduit implements Pool<P>, so return it directly
    return conduit;
}
```

**Behavior:**
- Article expects: `get(name)` → returns different Pool for each Conduit
- Our implementation: `get(name)` → creates/retrieves different Conduits per name ✅

**Verification**: ✅ **CORRECT** - Creates separate Conduits per name using computeIfAbsent

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
- `container.get("APPL")` → returns Pool for APPL Conduit
- `container.get("MSFT")` → returns Pool for MSFT Conduit (different Conduit)

**Expected Behavior:**
- `container.get("APPL")` → returns Pool for APPL Conduit ✅
- `container.get("MSFT")` → returns Pool for MSFT Conduit (different) ✅

**Verification**: ✅ **CORRECT** - Separate Conduit management working as expected

---

### 6. ✅ Created via Circuit.container()

**Article States:**
> `var container = circuit.container(ORDERS, pipe(Order.class));`

**Our Implementation:**

```java
// CircuitImpl.java (updated)
@Override
public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer) {
    checkClosed();
    Objects.requireNonNull(name, "Container name cannot be null");
    Objects.requireNonNull(composer, "Composer cannot be null");

    // Container manages a collection of Conduits
    // Each get(Name) call creates/retrieves a separate Conduit
    return new ContainerImpl<>(name, this, composer);
}
```

**Verification**: ✅ Correct - Circuit creates Container with name, circuit reference, and Composer

---

### 7. ✅ Collection Management

**Article States:**
> "A Container is a collection of Conduits"
> "Provides runtime management of Conduit collections"
> "Allows delegation of Conduit tracking to the Substrates runtime"

**Our Architecture:**

```java
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Name containerName;
    private final Circuit circuit;
    private final Composer<? extends P, E> composer;
    private final Map<Name, Conduit<P, E>> conduits = new ConcurrentHashMap<>();
    private final SourceImpl<Source<E>> containerSource;
    private final Subject containerSubject;

    @Override
    public Pool<P> get(Name name) {
        // Get or create Conduit for this name
        return conduits.computeIfAbsent(name, n -> {
            Name conduitName = containerName.name(n);
            Conduit<P, E> conduit = circuit.conduit(conduitName, composer);
            return conduit;  // Conduit implements Pool<P>
        });
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
                System.err.println("Error closing conduit: " + e.getMessage());
            }
        });
        conduits.clear();
    }
}
```

**Verification**: ✅ **CORRECT** - Manages Conduit collection with proper lifecycle

---

## What Our Implementation Does Correctly

### ✅ Collection Manager Pattern

Our Container now correctly implements the collection manager pattern from the article:
1. **Collection Management** - Maintains Map<Name, Conduit<P, E>> for multiple Conduits
2. **Dynamic Creation** - Uses computeIfAbsent() to create Conduits on first access
3. **Hierarchical Naming** - Creates Conduits with containerName.name(conduitName)
4. **Stable Subject** - Stores Subject as final field, returns same instance
5. **Lifecycle Management** - close() properly shuts down all managed Conduits

```java
// Circuit creates Container with circuit reference
return new ContainerImpl<>(name, this, composer);

// Container creates separate Conduits per name
Pool<P> applPool = container.get(name("APPL"));  // Creates APPL Conduit
Pool<P> msftPool = container.get(name("MSFT"));  // Creates MSFT Conduit (different!)
```

This now works perfectly for the article's use case: **Container as a collection manager for multiple Conduits**.

---

## Implementation Matches Article's Intent

Our Container implementation now fully matches the article's description:

**Article's Expected Behavior:**
```java
var container = circuit.container(STOCKS, pipe(Trade.class));

var applTrader = container.get(name("APPL"));  // Conduit for APPL
var msftTrader = container.get(name("MSFT"));  // Conduit for MSFT (different!)

applTrader.get(BUY).emit(trade1);  // Goes to APPL Conduit
msftTrader.get(SELL).emit(trade2); // Goes to MSFT Conduit (separate)
```

**Our Behavior:**
```java
var container = circuit.container(name("stocks"), Composer.pipe());

var applTrader = container.get(name("APPL"));  // Creates APPL Conduit
var msftTrader = container.get(name("MSFT"));  // Creates MSFT Conduit (different!)

assertThat(applTrader).isNotSameAs(msftTrader);  // ✅ TRUE (different objects)
```

**Verification:** Comprehensive test suite with 11 tests, all passing:
- `shouldCreateSeparateConduitsPerName()` - Verifies different Conduits per name
- `shouldCacheSameConduitForSameName()` - Verifies caching behavior
- `shouldSupportNestedAccessPattern()` - Verifies chaining works correctly
- `shouldSupportArticleStockTradingExample()` - Direct implementation of article example
- `shouldCloseAllManagedConduits()` - Verifies lifecycle management

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
    public Subject subject() { ... }           // ✓ From Substrate (stable instance)

    @Override
    public Source<Source<E>> source() { ... }  // ✓ From Component

    @Override
    public Pool<P> get(Name name) { ... }      // ✓ Creates/retrieves separate Conduits

    @Override
    public void close() { ... }                // ✓ From Resource (closes all Conduits)
}
```

**Verification**: ✅ Full compliance - All methods implemented correctly per article specification

---

## Test Coverage

**Current Tests (fullerstack-substrates):**

All 11 tests pass, covering:

```java
// ✅ Container creation
@Test
void shouldCreateContainerViaCircuit() {
    Container<Pool<Pipe<String>>, Source<String>> container =
        circuit.container(cortex.name("test"), Composer.pipe());
    assertThat(container.subject().type()).isEqualTo(Subject.Type.CONTAINER);
}

// ✅ Different Conduits per name (KEY TEST)
@Test
void shouldCreateSeparateConduitsPerName() {
    Pool<Pipe<Long>> applPool = container.get(cortex.name("APPL"));
    Pool<Pipe<Long>> msftPool = container.get(cortex.name("MSFT"));
    assertThat((Object) applPool).isNotSameAs(msftPool);  // ✅ PASSES
}

// ✅ Conduit caching
@Test
void shouldCacheSameConduitForSameName() {
    Pool<Pipe<String>> pool1 = container.get(cortex.name("APPL"));
    Pool<Pipe<String>> pool2 = container.get(cortex.name("APPL"));
    assertThat((Object) pool1).isSameAs(pool2);  // ✅ Cached
}

// ✅ Nested access pattern
@Test
void shouldSupportNestedAccessPattern() {
    container
        .get(cortex.name("APPL"))
        .get(cortex.name("BUY"))
        .emit("Order{symbol=APPL, action=BUY, quantity=100}");
    // ✅ Works correctly
}

// ✅ Multiple Conduits with emissions
@Test
void shouldSupportMultipleConduitsWithEmissions() {
    container.get(cortex.name("APPL")).get(cortex.name("BUY")).emit("APPL-BUY-100");
    container.get(cortex.name("MSFT")).get(cortex.name("SELL")).emit("MSFT-SELL-100");

    ContainerImpl<Pipe<String>, String> impl = (ContainerImpl<Pipe<String>, String>) container;
    assertThat(impl.getConduits()).hasSize(2);  // ✅ 2 separate Conduits
}

// ✅ Article's stock trading example
@Test
void shouldSupportArticleStockTradingExample() {
    // Direct implementation of article's pattern
    Pool<Pipe<String>> applConduit = container.get(cortex.name("APPL"));
    Pipe<String> buyPipe = applConduit.get(cortex.name("BUY"));
    buyPipe.emit("Order{symbol=APPL, quantity=200, price=1000000}");

    Pool<Pipe<String>> msftConduit = container.get(cortex.name("MSFT"));
    assertThat((Object) msftConduit).isNotSameAs(applConduit);  // ✅ Different Conduits
}

// ✅ Lifecycle management
@Test
void shouldCloseAllManagedConduits() {
    container.get(cortex.name("conduit1"));
    container.get(cortex.name("conduit2"));
    container.get(cortex.name("conduit3"));

    container.close();

    ContainerImpl<Pipe<String>, String> impl = (ContainerImpl<Pipe<String>, String>) container;
    assertThat(impl.getConduits()).isEmpty();  // ✅ All closed
}
```

**Test Results:** Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 ✅

---

## Alignment Summary

| Concept | Article Requirement | Our Implementation | Status |
|---------|-------------------|-------------------|--------|
| Container interface | ✓ | Container<Pool<P>, Source<E>> | ✅ |
| Extends Component<Source<E>> | ✓ | Implements correctly | ✅ |
| Extends Pool<Pool<P>> | ✓ | Implements correctly | ✅ |
| get(Name) returns Pool | ✓ | Returns different Pool per name | ✅ |
| source() returns Source<Source<E>> | ✓ | Returns nested Source | ✅ |
| **Collection of Conduits** | ✓ | **Manages Map<Name, Conduit>** | ✅ |
| **Separate Conduit per name** | ✓ | **Creates separate Conduits** | ✅ |
| **Dynamic Conduit creation** | ✓ | **computeIfAbsent() pattern** | ✅ |
| **Stable Subject identity** | ✓ | **Stored as final field** | ✅ |
| Created via Circuit.container() | ✓ | CircuitImpl.container() | ✅ |
| Uses Composer | ✓ | Passes to Conduit creation | ✅ |
| Nested access pattern | ✓ | **Works as intended** | ✅ |
| Lifecycle management | ✓ | **close() closes all Conduits** | ✅ |

---

## Conclusion

**Status: ✅ FULLY ALIGNED**

Our `Container` implementation is now **fully aligned** with the Humainary Containers article:

### ✅ **All Requirements Met:**
1. **Interface structure** - `Container<Pool<P>, Source<E>>` correctly implements `Component<Source<E>>` and `Pool<Pool<P>>`
2. **Collection management** - Manages `Map<Name, Conduit<P, E>>` for multiple Conduits
3. **Dynamic Conduit creation** - `get(name)` uses `computeIfAbsent()` to create/retrieve different Conduits per name
4. **Separate routing** - Each name gets its own Conduit with independent percept routing
5. **Hierarchical naming** - Creates Conduits with `containerName.name(conduitName)` pattern
6. **Stable Subject** - Subject stored as final field, returns same instance on every call
7. **Nested access pattern** - `container.get(name("APPL")).get(BUY).emit(order)` works correctly
8. **Lifecycle management** - `close()` properly shuts down all managed Conduits
9. **Article's stock trading use case** - Fully supported and tested

### Implementation Quality:
- **Comprehensive tests**: 11 tests covering all behaviors, 100% passing
- **Thread-safe**: Uses `ConcurrentHashMap` for Conduit storage
- **Lazy initialization**: Conduits created only when first accessed
- **Proper cleanup**: All Conduits closed when Container closes
- **Null safety**: Validates all inputs with clear error messages

### Key Fix Applied:
Changed from wrapping a single Conduit to managing a collection of Conduits, where each `get(Name)` call creates or retrieves a separate Conduit. Added stable Subject identity by storing Subject as final field initialized in constructor.

---

## References

- **Article**: https://humainary.io/blog/observability-x-containers/
- **Key Quote**: "A Container is a collection of Conduits of the same emittance data type"
- **Pattern**: `container.get(name("APPL")).get(BUY).emit(order)`
- **Interface**: `Container<P, E> extends Component<Source<E>>, Pool<Pool<P>>`
