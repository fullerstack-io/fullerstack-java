# Fullerstack Substrates Implementation Gaps

**Date**: 2025-10-09
**Status**: CRITICAL - Core abstractions incomplete

---

## Summary

The fullerstack-substrates implementation has good foundations (Queue, Source, basic Circuit) but is missing **critical abstractions** that make Substrates work as a contextual observability runtime.

---

## Critical Gaps

### 1. **Conduit.source() - NOT IMPLEMENTED** ❌

**Location**: `ConduitImpl.java:52-53`

```java
@Override
public Source<E> source() {
    // TODO Story 4.3: Implement source for pull-based consumption
    throw new UnsupportedOperationException("Source pattern not yet implemented");
}
```

**Impact**:
- Cannot subscribe to percept creation events
- Cannot use Container/Source subscription pattern from gist
- The entire **dynamic observation model** doesn't work

**Fix Required**:
```java
public class ConduitImpl<P, E> implements Conduit<P, E> {
    private final SourceImpl<E> eventSource;  // Emits when percepts emit

    @Override
    public Source<E> source() {
        return eventSource;
    }

    // When Channel emits, forward to Source
    private void handleChannelEmission(E emission) {
        eventSource.emit(emission);
    }
}
```

### 2. **Circuit.conduit() - STUB IMPLEMENTATION** ❌

**Location**: `CircuitImpl.java:89-116`

```java
@Override
public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
    throw new UnsupportedOperationException(
        "Conduit implementation pending - see Story 4.12 (Conduit + Composer)"
    );
}
```

**Impact**:
- Cannot create Conduits from Circuit
- The entire Circuit → Conduit → Channel hierarchy is broken
- Composer pattern doesn't work

**Fix Required**:
```java
@Override
public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
    checkClosed();
    Objects.requireNonNull(name, "Conduit name cannot be null");
    Objects.requireNonNull(composer, "Composer cannot be null");

    @SuppressWarnings("unchecked")
    Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
        name,
        n -> new ConduitImpl<>(this.name, n, composer)
    );
    return conduit;
}
```

### 3. **Circuit.container() - COMPOSER NOT USED** ❌

**Location**: `CircuitImpl.java:119-142`

```java
Pool<P> pool = new PoolImpl<>(poolName -> {
    throw new UnsupportedOperationException(
        "Composer implementation pending - see Story 4.16"
    );
});
```

**Impact**:
- Container doesn't actually compose percepts
- Just creates empty Pool + Source
- The **core Container pattern** from the gist doesn't work

**Fix Required**:
```java
@Override
public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer) {
    checkClosed();
    Objects.requireNonNull(name, "Container name cannot be null");
    Objects.requireNonNull(composer, "Composer cannot be null");

    // Create a Conduit using the Composer
    Conduit<P, E> conduit = conduit(name, composer);

    // Container wraps the Conduit's Pool and Source
    return new ContainerImpl<>(
        conduit,  // Pool<P> - provides get(Name) for percepts
        conduit.source()  // Source<E> - emits events from percepts
    );
}
```

### 4. **Subject Implementation - STUB IN CONDUIT** ⚠️

**Location**: `ConduitImpl.java:39-48`

```java
@Override
public Subject subject() {
    return new Subject() {
        @Override public Id id() { return new Id() {}; }  // Empty!
        @Override public Name name() { return conduitName; }
        @Override public State state() { return null; }  // TODO
        @Override public Type type() { return Type.CONDUIT; }
        @Override public CharSequence part() { return conduitName.part(); }
    };
}
```

**Impact**:
- Subject doesn't have proper ID or State
- Cannot track Conduit identity properly

**Fix Required**: Use `SubjectImpl` like other components do

### 5. **Channel Subject - SAME ISSUE** ⚠️

**Location**: `ChannelImpl.java:27-35`

Same stub Subject implementation as Conduit.

---

## What Actually Works ✅

### 1. **SourceImpl** - GOOD
- Thread-safe subscriber management
- Proper subscription lifecycle
- `emit()` method invokes subscribers correctly
- This is the **foundation** for the whole system

### 2. **QueueImpl** - GOOD
- Virtual threads (Java 24)
- Proper backpressure with `await()`
- Scripts and Currents support

### 3. **ClockImpl** - GOOD
- Periodic event scheduling
- Proper lifecycle management

### 4. **PoolImpl** - GOOD
- Lazy initialization
- Thread-safe caching

### 5. **StateImpl, SlotImpl** - GOOD
- Immutable state management
- Proper value types

---

## The Core Problem

We have **SourceImpl** (working) but we're not using it properly:

**Current (Wrong)**:
```
Circuit → conduit() throws exception
Container → creates Pool that throws exception
Conduit.source() → throws exception
```

**Should Be (Correct)**:
```
Circuit → conduit(Composer) → creates ConduitImpl
                            → ConduitImpl has internal SourceImpl
                            → When Channel emits, forward to Source
                            → Subscribers receive events

Container → wraps Conduit → exposes Conduit.get() as Pool
                         → exposes Conduit.source() as Source
```

---

## Implementation Plan

### Phase 1: Make Conduit Work
1. ✅ SourceImpl already works
2. ❌ Add `SourceImpl<E> eventSource` to ConduitImpl
3. ❌ Implement `Conduit.source()` to return eventSource
4. ❌ When Channel emits, call `eventSource.emit(emission)`
5. ❌ Use proper SubjectImpl instead of stub

### Phase 2: Make Circuit.conduit() Work
1. ❌ Remove UnsupportedOperationException
2. ❌ Create ConduitImpl with Composer
3. ❌ Cache in conduits map

### Phase 3: Make Container Work
1. ❌ Container wraps a Conduit
2. ❌ Pool<P> delegates to Conduit.get(Name)
3. ❌ Source<E> delegates to Conduit.source()

### Phase 4: Verify with Example
1. ❌ Recreate Actors.java pattern
2. ❌ Verify Container + Composer + Source.subscribe() works
3. ❌ Run tests

---

## Files to Modify

1. **ConduitImpl.java** - Add Source, fix Subject
2. **ChannelImpl.java** - Fix Subject, emit to Conduit's Source
3. **CircuitImpl.java** - Implement conduit() and container()
4. **ContainerImpl.java** - Wrap Conduit properly
5. **NEW: Example.java** - Demonstrate correct usage

---

## Tests to Validate

After fixes, these patterns should work:

### Pattern 1: Direct Conduit Usage
```java
Cortex cortex = new CortexRuntime();
Circuit circuit = cortex.circuit();

Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("messages"),
    channel -> channel.pipe()  // Simple composer
);

// Subscribe to events
conduit.source().subscribe((subject, registrar) -> {
    registrar.register(msg -> System.out.println("Received: " + msg));
});

// Get percept and emit
Pipe<String> pipe = conduit.get(cortex.name("user1"));
pipe.emit("Hello!");  // Should trigger subscriber
```

### Pattern 2: Container with Composer
```java
// Like Actors.java from gist
Container<Pool<Pipe<Order>>, Source<Order>> orders = circuit.container(
    cortex.name("orders"),
    channel -> channel.pipe()
);

// Subscribe to all order events
orders.source().subscribe((subject, registrar) -> {
    registrar.register(order -> processOrder(order));
});

// Get percept for specific stock
Pipe<Order> appleOrders = orders.get(cortex.name("AAPL"));
appleOrders.emit(new Order("AAPL", 100, "BUY"));  // Triggers subscriber
```

---

**Next Step**: Start implementing Phase 1 - Make Conduit Work

