# Documentation Audit - Substrates Implementation

Date: 2025-10-12

## Purpose
Systematic review of documentation accuracy after implementation evolution:
- Container hierarchical subscription pattern implementation
- Queue refactoring (BlockingDeque → BlockingQueue)
- QoS architecture clarification
- CaptureImpl consolidation

## Files Reviewed
1. `CONCEPTS.md` (970 lines)
2. `ARCHITECTURE.md` (789 lines)
3. `ADVANCED.md` (690 lines)

---

## CONCEPTS.md - Core Concepts

### Status: **NEEDS UPDATES**

### Missing Content

#### 1. Container Concept (HIGH PRIORITY)
**Issue:** Container is not documented at all in CONCEPTS.md
**Impact:** Users don't understand Pool<Pool<P>> pattern or Container's role
**What to Add:**
- Container as "Pool of Pools" (manages collection of Conduits)
- How Container differs from Conduit
- When to use Container vs multiple Conduits
- Basic Container usage example

**Suggested Location:** After "Channel" section (around line 146)

#### 2. Container Hierarchical Subscription Pattern (HIGH PRIORITY)
**Issue:** Container's emission behavior is not explained
**Impact:** Users don't know that Container emits Conduit Sources when Conduits are created
**What to Add:**
```java
// Container emits Conduit Sources when new Conduits are created
Container<Pool<Pipe<String>>, Source<String>> container =
    circuit.container(name, Composer.pipe());

// Subscribe to Container - receives Conduit Sources
container.source().subscribe(
    cortex.subscriber(name, (conduitSubject, registrar) -> {
        // emission is the Conduit's Source
        registrar.register(conduitSource -> {
            // Now subscribe to the Conduit
            conduitSource.subscribe(innerSubscriber);
        });
    })
);

// When you call get() for NEW name, Container emits that Conduit's Source
Pool<Pipe<String>> conduit = container.get(cortex.name("stocks.AAPL"));
// ← Subscribers receive conduit's source()
```

**Suggested Location:** New section "Container and Hierarchical Subscription" after "Conduit"

#### 3. Queue/Script/Current Explained (MEDIUM PRIORITY)
**Issue:** Lines 424-444 mention Queue in "The Flow" but don't explain what Queue/Script/Current are
**Impact:** Users see Queue in diagrams but don't understand its purpose
**What to Add:**
- Brief explanation of Queue as coordination mechanism
- Script as executable unit
- Current as @Temporal execution context
- Link to ADVANCED.md for details

**Suggested Location:** Add brief note in "The Flow" section, full details in ADVANCED.md (already exists)

### Accurate Content (No Changes Needed)
- ✅ Subject, Id, Name, State, Slot, Substrate - all accurate
- ✅ Producer-Consumer Model - accurate
- ✅ Transformation Pipelines - accurate
- ✅ Resource Management - accurate
- ✅ All code examples verified

---

## ARCHITECTURE.md - Architecture Guide

### Status: **NEEDS MINOR UPDATES**

### Missing Content

#### 1. Container Architecture Detail (HIGH PRIORITY)
**Issue:** Container mentioned (lines 36, 48, 56) but no detailed architecture explanation
**Impact:** Implementers don't understand Container's internal structure
**What to Add:**
- Container's role as Pool<Pool<P>> manager
- How Container creates Conduits on-demand
- Container's emission pattern (emits Conduit Sources)
- Caching behavior for Conduits within Container
- Hierarchical naming (container.conduit names)

**Suggested Location:** New section "Container" in "Core Components" (after "Conduit")

#### 2. QoS Architecture (MEDIUM PRIORITY)
**Issue:** No mention of where/how QoS is handled
**Impact:** Users might try to implement priority at wrong level (Script level vs Circuit/Conduit level)
**What to Add:**
- QoS is handled at Circuit/Conduit level, not Script level
- Separate Circuits for different QoS requirements
- Separate Conduits for different processing characteristics
- Queue uses simple FIFO ordering (BlockingQueue)
- Reference to Humainary blog: https://humainary.io/blog/observability-x-circuits/

**Suggested Location:** New subsection under "Queue Processing" (around line 638)

#### 3. Queue Implementation Details (LOW PRIORITY)
**Issue:** Generic "BlockingQueue" mentioned but no context about single-threaded FIFO processing
**Impact:** Minor - users might misunderstand Queue's role
**What to Add:**
- Queue uses LinkedBlockingQueue for pure FIFO
- Single-threaded processing for ordering guarantees
- Scripts executed sequentially in order posted
- No priority queue or reordering

**Suggested Location:** Update "Queue Processing" section (lines 638-653)

### Accurate Content (No Changes Needed)
- ✅ Circuit architecture - accurate
- ✅ Conduit data flow - accurate
- ✅ Name-based caching - accurate
- ✅ Threading model - accurate
- ✅ Resource lifecycle - accurate

---

## ADVANCED.md - Advanced Concepts

### Status: **NEEDS MINOR UPDATES**

### Missing Content

#### 1. Container Emission Pattern (HIGH PRIORITY)
**Issue:** Lines 629-651 explain nested subscription but don't explain WHEN Container emits
**Impact:** Users don't know Container emits Conduit Source when container.get() creates NEW Conduit
**What to Update:**
- Update "Nested Subscription Pattern" section
- Clarify Container emits on FIRST call to get(name) for new name
- Show that cached Conduits (subsequent get() calls) don't re-emit
- Explain the emission contains Capture<Source<E>> pairing Conduit Subject with its Source

**Current (line 648-650):**
```java
// Later, accessing a new pool name triggers subscription
Pool<Pipe<String>> newPool = container.get(cortex.name("new-group"));
// pool-observer is notified and subscribes to events
```

**Should Add:**
```java
// FIRST call to get(name) creates Conduit AND emits its Source
Pool<Pipe<String>> pool1 = container.get(cortex.name("stocks.AAPL"));
// ← Container emits Capture(conduit.subject(), conduit.source())
// ← Subscribers receive conduit.source() and can subscribe

// SECOND call to get(SAME name) returns cached Conduit (no emission)
Pool<Pipe<String>> pool2 = container.get(cortex.name("stocks.AAPL"));
// ← No emission, pool1 == pool2 (cached)

// Different name creates NEW Conduit (emits again)
Pool<Pipe<String>> pool3 = container.get(cortex.name("stocks.MSFT"));
// ← Emits again for new Conduit
```

**Suggested Location:** Update lines 629-651

#### 2. QoS Architecture Clarification (LOW PRIORITY)
**Issue:** Queue section (lines 17-109) doesn't mention QoS architecture
**Impact:** Minor - users might wonder about priority queuing
**What to Add:**
- Brief note that Queue uses FIFO, no priority within Queue
- QoS handled at Circuit/Conduit level (separate resources)
- Reference to Circuit documentation

**Suggested Location:** Add to "Key Points" (line 103)

### Accurate Content (No Changes Needed)
- ✅ Queue & Script Subsystem - well explained
- ✅ Container Type System - accurate
- ✅ Segment operations - accurate
- ✅ Extent operations - accurate
- ✅ Digital Twin pattern - accurate

---

## Alignment Docs

### Status: **NEEDS REVIEW** (Not yet examined)

Files to review:
- `docs/alignment/containers.md` - Check Container documentation
- `docs/alignment/queues-scripts-currents.md` - Check Queue documentation
- `docs/alignment/circuits.md` - Check Circuit QoS documentation

---

## Priority Summary

### High Priority (Must Fix)
1. **CONCEPTS.md - Add Container concept and hierarchical subscription pattern**
2. **ARCHITECTURE.md - Add Container architecture section**
3. **ADVANCED.md - Update Container emission pattern explanation**

### Medium Priority (Should Fix)
1. **ARCHITECTURE.md - Add QoS architecture explanation**
2. **CONCEPTS.md - Add Queue/Script/Current brief explanation**

### Low Priority (Nice to Have)
1. **ARCHITECTURE.md - Clarify Queue implementation details**
2. **ADVANCED.md - Add QoS note to Queue section**

---

## Implementation Notes

### Container Hierarchical Subscription - Current Implementation

**File:** `fullerstack-substrates-java/src/main/java/io/fullerstack/substrates/container/ContainerImpl.java:104-136`

```java
@Override
public Pool<P> get(Name name) {
    // Track if this is a new Conduit creation
    final boolean[] isNewConduit = {false};

    // Get or create Conduit for this name
    Conduit<P, E> conduit = conduits.computeIfAbsent(name, n -> {
        Name conduitName = containerName.name(n);
        Conduit<P, E> newConduit = sequencer != null
            ? circuit.conduit(conduitName, composer, sequencer)
            : circuit.conduit(conduitName, composer);

        isNewConduit[0] = true;
        return newConduit;
    });

    // Emit the Conduit's Source to Container subscribers (only for new Conduits)
    if (isNewConduit[0]) {
        Capture<Source<E>> capture = new CaptureImpl<>(conduit.subject(), conduit.source());
        containerSource.emissionHandler().accept(capture);
    }

    return conduit;
}
```

**Key Points:**
- Container emits `Capture<Source<E>>` pairing Conduit Subject with its Source
- Emission happens synchronously on FIRST get(name) call
- Subsequent calls return cached Conduit without emitting
- Enables hierarchical subscription: Container → Conduit → Channel

### Queue Implementation - Current State

**File:** `fullerstack-substrates-java/src/main/java/io/fullerstack/substrates/queue/QueueImpl.java:41-80`

```java
public class QueueImpl implements Queue {
    private final BlockingQueue<Script> scripts = new LinkedBlockingQueue<>();
    private final Thread processor;

    // FIFO processing - no priority
    @Override
    public void post(Script script) {
        if (script != null && running) {
            scripts.offer(script);  // Add to queue (FIFO)
        }
    }

    @Override
    public void post(Name name, Script script) {
        // Named script execution - allows tagging/tracking Scripts
        // QoS/Priority is handled at Circuit/Conduit level, not Script level
        if (script != null && running) {
            scripts.offer(script);  // Add to queue (FIFO)
        }
    }
}
```

**Key Points:**
- Uses `LinkedBlockingQueue` (not Deque)
- Simple FIFO ordering
- No priority queuing within Queue
- QoS handled at Circuit/Conduit level (separate resources)
- Reference: https://humainary.io/blog/observability-x-circuits/

---

## Next Steps

1. Update CONCEPTS.md with Container concept and hierarchical subscription
2. Update ARCHITECTURE.md with Container architecture section
3. Update ADVANCED.md with Container emission timing clarification
4. Add QoS architecture notes to relevant sections
5. Review alignment docs for consistency
6. Commit updated documentation

---

## References

- Humainary Blog: https://humainary.io/blog/
- Substrates 101: https://humainary.io/blog/substrates-101/
- Containers: https://humainary.io/blog/observability-x-containers/
- Circuits & QoS: https://humainary.io/blog/observability-x-circuits/
- Queues, Scripts, Currents: https://humainary.io/blog/observability-x-queues-scripts-and-currents/
