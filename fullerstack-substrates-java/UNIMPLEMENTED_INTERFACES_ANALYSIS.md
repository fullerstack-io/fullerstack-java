# Unimplemented Interfaces Analysis

**API Version:** 1.0.0-M13
**Date:** 2025-10-11

## Summary

Out of 38 total interfaces in the Substrates API, we have:
- ✅ **19 implemented**
- ❌ **19 unimplemented**

Of the 19 unimplemented:
- **2 are @FunctionalInterface** (don't need implementations)
- **4 are sealed/abstract** (may be satisfied by existing implementations)
- **5 are @Extension/@Utility** (client-side interfaces, may not need implementations)
- **8 need actual implementations**

---

## Interfaces That DON'T Need Implementations

### 1. Functional Interfaces (2)
**No implementation needed** - These are used as lambda/function references

| Interface | Type | Single Method | Usage |
|-----------|------|---------------|-------|
| **Fn** | `@FunctionalInterface` | `R eval() throws T` | Throwable function |
| **Op** | `@FunctionalInterface` | `void exec() throws T` | Throwable operation |

### 2. Extension/Utility Interfaces (5)
**Client-side interfaces** - Typically implemented by user code, not the runtime

| Interface | Type | Purpose | Need Implementation? |
|-----------|------|---------|---------------------|
| **Composer** | `@Extension` | Composes channels into percepts | **NO** - Has static factory methods (channel(), pipe()) |
| **Extent** | `@Extension` | Hierarchical nested structure | **NO** - Name already implements this |
| **Registrar** | `@Provided` `@Temporal` | Links Subject to Pipe | **NO** - Created inline in Conduit/tests |
| **Script** | `@Extension` | Executable unit for Queue | **NO** - User implements (lambda) |
| **Sequencer** | `@Extension` | Configures assembly pipelines | **NO** - User implements (lambda) |

### 3. Sealed/Abstract Interfaces (4)
**May already be satisfied** by existing implementations

| Interface | Sealed/Permits | Status | Need Implementation? |
|-----------|----------------|--------|---------------------|
| **Component** | `sealed permits Circuit, Clock, Container` | ✅ All 3 implemented | **NO** - Satisfied by children |
| **Context** | `sealed permits Component` | ✅ Component implemented | **NO** - Satisfied by Component |
| **Inlet** | `sealed permits Channel` | ✅ Channel implemented | **NO** - Satisfied by Channel |
| **Resource** | `sealed permits Component, Sink, Subscription` | ⚠️ Sink/Subscription not implemented | **MAYBE** - Depends on Sink/Subscription |
| **Substrate** | `@Abstract` | ✅ All implementors have subject() | **NO** - Base interface only |
| **Tap** | Has default impl | ✅ ConduitImpl/CircuitImpl use it | **NO** - Default method sufficient |

### 4. Marker Interface (1)

| Interface | Purpose | Need Implementation? |
|-----------|---------|---------------------|
| **Assembly** | `@Abstract` marker for pipeline components | **NO** - Segment/Sift extend this |

---

## Interfaces That NEED Implementations (8)

### Priority 1: Critical Missing Implementations

#### 1. **Sink** - HIGH PRIORITY
```java
@Provided
non-sealed interface Sink<E> extends Substrate, Resource {
    Stream<Capture<E>> drain();
}
```

**Status:** ⚠️ **STUBBED** in CortexRuntime
**Why needed:** Core functionality for buffering and draining emissions
**Usage:** `Cortex.sink(Source<E>)` creates sinks
**Implementation needed:** Yes - real buffering logic

---

#### 2. **Current** - MEDIUM PRIORITY
```java
@Provided
@Temporal
interface Current extends Substrate {
    void post(Runnable runnable);
}
```

**Status:** ❌ **NOT IMPLEMENTED**
**Why needed:** Provides access to circuit's work queue from within scripts
**Usage:** `Script.exec(Current current)` receives this
**Implementation needed:** Yes - wraps Queue for async posting

---

#### 3. **Subscription** - LOW PRIORITY
```java
@Provided
non-sealed interface Subscription extends Substrate, Resource {
    // Only subject() from Substrate and close() from Resource
}
```

**Status:** ⚠️ **ANONYMOUS** in SourceImpl
**Why needed:** Returned from Source.subscribe()
**Current state:** Created as anonymous inner class in SourceImpl
**Implementation needed:** Maybe - extract to SubscriptionImpl for consistency

---

### Priority 2: Utility/Helper Implementations

#### 4. **Closure** - LOW PRIORITY
```java
@Utility
@Temporal
interface Closure<R extends Resource> {
    void consume(Consumer<? super R> consumer);
}
```

**Status:** ❌ **NOT IMPLEMENTED**
**Why needed:** ARM (Automatic Resource Management) pattern
**Usage:** `Scope.closure(Resource)` creates closures
**Implementation needed:** Yes - wraps try-with-resources pattern

---

## Implementation Priority Ranking

### Must Implement
1. ✅ **Sink** - Core functionality, already stubbed, high usage
2. ✅ **Current** - Needed for Script execution

### Should Implement
3. **Closure** - ARM pattern is useful utility
4. **Subscription** - Extract anonymous class for API compliance

### Don't Need to Implement
- ❌ Fn, Op (functional interfaces)
- ❌ Composer, Script, Sequencer (user-side extensions)
- ❌ Component, Context, Inlet, Resource (sealed, satisfied by children)
- ❌ Substrate, Tap, Assembly (abstract/marker interfaces)
- ❌ Extent (Name already implements)
- ❌ Registrar (created inline)

---

## Next Steps

1. **Implement SinkImpl**
   - Replace stub in CortexRuntime
   - Real buffering with CopyOnWriteArrayList or similar
   - drain() returns accumulated Captures

2. **Implement CurrentImpl**
   - Wraps Circuit's Queue
   - Provides post(Runnable) -> converts to Script

3. **Consider Subscription**
   - Extract anonymous class from SourceImpl
   - Create SubscriptionImpl for consistency

4. **Consider Closure**
   - Implement ARM pattern helper
   - Update ScopeImpl to create Closures

---

## Summary Table

| Interface | Type | Need Impl? | Priority | Notes |
|-----------|------|------------|----------|-------|
| Assembly | Marker | ❌ No | - | Extended by Segment/Sift |
| Closure | Utility | ✅ Yes | Low | ARM pattern helper |
| Component | Sealed | ❌ No | - | Satisfied by Circuit/Clock/Container |
| Composer | Extension | ❌ No | - | Has static factories |
| Context | Sealed | ❌ No | - | Satisfied by Component |
| Current | Provided | ✅ Yes | **HIGH** | Needed for Script.exec() |
| Extent | Extension | ❌ No | - | Name implements this |
| Fn | Functional | ❌ No | - | Lambda interface |
| Inlet | Sealed | ❌ No | - | Satisfied by Channel |
| Op | Functional | ❌ No | - | Lambda interface |
| Registrar | Temporal | ❌ No | - | Created inline |
| Resource | Sealed | ❌ No | - | Mostly satisfied (pending Sink/Subscription) |
| Script | Extension | ❌ No | - | User implements |
| Sequencer | Extension | ❌ No | - | User implements |
| Sink | Provided | ✅ Yes | **HIGH** | Currently stubbed |
| Subscription | Provided | ⚠️ Maybe | Low | Currently anonymous |
| Substrate | Abstract | ❌ No | - | Base interface |
| Tap | Default | ❌ No | - | Default impl sufficient |

**Total to implement: 2-4 interfaces** (Sink, Current, optionally Closure & Subscription)

