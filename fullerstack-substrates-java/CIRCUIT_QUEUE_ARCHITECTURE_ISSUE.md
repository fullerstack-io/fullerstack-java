# Circuit Queue Architecture Issue

**Status**: CRITICAL - Violates Core Substrates Design Principle
**Discovered**: 2025-10-11
**Reference**: https://humainary.io/blog/observability-x-circuits/

## Summary

Our implementation violates the fundamental "Virtual CPU Core" / "single-threaded execution model" principle described in William Louth's Circuit architecture article. Each Conduit currently creates its own queue and processor thread, when all Conduits within a Circuit should share the Circuit's single queue.

## The Problem

### What the Article States

From https://humainary.io/blog/observability-x-circuits/:

> **"Single-threaded execution model"**
> **"Functions as a 'Virtual CPU Core'"**
> **"Processes data through a single event queue"**
> **"Ensures ordered data delivery within its domain"**

### Current Implementation (INCORRECT)

**ConduitImpl.java:56**
```java
private final BlockingQueue<Capture<E>> queue = new LinkedBlockingQueue<>(10000);
private final Thread queueProcessor;
```

**Architecture**:
```
Circuit
  ├─ Queue (exists but UNUSED by Conduits!)
  ├─ Conduit 1 → Own Queue + Own Thread
  ├─ Conduit 2 → Own Queue + Own Thread
  └─ Conduit 3 → Own Queue + Own Thread
```

**Problems**:
1. ❌ Each Conduit has its own thread (not single-threaded)
2. ❌ Each Conduit has its own queue (not single event queue)
3. ❌ No ordering guarantees across Conduits
4. ❌ Cannot control QoS/priority across Circuit
5. ❌ Violates "Virtual CPU Core" metaphor

### Correct Architecture (SHOULD BE)

**Architecture**:
```
Circuit (Virtual CPU Core)
  └─ Single Queue (QueueImpl)
       ├─ Conduit 1 posts Scripts to Circuit Queue
       ├─ Conduit 2 posts Scripts to Circuit Queue
       └─ Conduit 3 posts Scripts to Circuit Queue

Queue processes Scripts sequentially (single-threaded)
  → Script 1: Conduit1.processEmission(capture)
  → Script 2: Conduit2.processEmission(capture)
  → Script 3: Conduit1.processEmission(capture)
```

**Benefits**:
1. ✅ Single-threaded execution per Circuit
2. ✅ Ordered delivery within Circuit domain
3. ✅ QoS control (can prioritize certain Conduits)
4. ✅ Prevents queue saturation
5. ✅ Matches "Virtual CPU Core" design

## Required Changes

### 1. Update ConduitImpl Constructor

**Current**:
```java
public ConduitImpl(Name circuitName, Name conduitName, Composer<? extends P, E> composer) {
    // Creates own queue
    this.queue = new LinkedBlockingQueue<>(10000);
    this.queueProcessor = startQueueProcessor();
}
```

**Should Be**:
```java
public ConduitImpl(
    Name circuitName,
    Name conduitName,
    Composer<? extends P, E> composer,
    Queue circuitQueue  // ← Pass in Circuit's Queue
) {
    this.circuitQueue = circuitQueue;
    // No queue processor thread!
}
```

### 2. Update CircuitImpl.conduit()

**Current**:
```java
Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
    name,
    n -> new ConduitImpl<>(circuitSubject.name(), n, composer)
);
```

**Should Be**:
```java
Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
    name,
    n -> new ConduitImpl<>(circuitSubject.name(), n, composer, this.queue)
    //                                                           ^^^^^^^^^^
    //                                                    Pass Circuit's Queue
);
```

### 3. Update ChannelImpl to Post Scripts

**Current**:
```java
// ChannelImpl creates Pipes that directly post Capture<E> to Conduit's queue
public Pipe<E> pipe() {
    return new PipeImpl<>(queue, channelSubject);  // Posts Capture to Conduit queue
}
```

**Should Be**:
```java
// ChannelImpl creates Pipes that post Scripts to Circuit's Queue
public Pipe<E> pipe() {
    // Create a Pipe that wraps emissions as Scripts
    return emission -> {
        Capture<E> capture = new CaptureImpl<>(channelSubject, emission);

        // Post a Script to Circuit's Queue that will invoke Conduit.processEmission()
        circuitQueue.post(current -> {
            conduit.processEmission(capture);
        });
    };
}
```

### 4. Make processEmission() Package-Private or Public

**Current**:
```java
private void processEmission(Capture<E> capture) { ... }
```

**Should Be**:
```java
// Package-private so ChannelImpl can call it via Script
void processEmission(Capture<E> capture) { ... }
```

Or expose it through a different mechanism (e.g., ChannelImpl holds reference to Conduit).

### 5. Remove Conduit's Queue Processor

**Remove**:
```java
// DELETE these from ConduitImpl
private final BlockingQueue<Capture<E>> queue = new LinkedBlockingQueue<>(10000);
private final Thread queueProcessor;

private Thread startQueueProcessor() { ... }  // DELETE
```

## Design Considerations

### How Channels Know Which Conduit to Call

**Option A: ChannelImpl holds Conduit reference**
```java
public class ChannelImpl<E> implements Channel<E> {
    private final Queue circuitQueue;
    private final ConduitImpl<?, E> conduit;  // Reference to parent Conduit

    public Pipe<E> pipe() {
        return emission -> {
            Capture<E> capture = new CaptureImpl<>(channelSubject, emission);
            circuitQueue.post(current -> conduit.processEmission(capture));
        };
    }
}
```

**Option B: ChannelImpl posts with Conduit name, Circuit routes**
```java
// Circuit maintains Conduit registry
// Script contains both Conduit name and Capture
// Circuit looks up Conduit and calls processEmission()
```

**Recommendation**: Option A is simpler and more direct.

### Performance Impact

**Before** (Current):
- Each Conduit has dedicated thread
- No cross-Conduit contention
- But: violates single-threaded model

**After** (Correct):
- Single thread processes all Conduits
- Matches Humainary's benchmark: "121 million internal events per second"
- QoS control possible
- Prevents queue saturation

## Testing Impact

After refactoring:
1. All existing tests should still pass (behavior unchanged)
2. May need to update tests that verify async behavior timing
3. Add tests to verify single-threaded execution order
4. Add tests to verify Circuit.queue().await() works correctly

## Documentation Updates

After refactoring, update:
1. `ConduitImpl` javadoc - remove mention of "queue processor thread"
2. `CircuitImpl` javadoc - emphasize "single event queue" design
3. `ChannelImpl` javadoc - explain how it posts to Circuit Queue
4. Add architecture diagram showing Circuit → Queue → Conduit flow

## References

- **Article**: https://humainary.io/blog/observability-x-circuits/
- **Key Quote**: "Functions as a 'Virtual CPU Core' - Processes data through a single event queue"
- **Performance**: "Up to 121 million internal events per second" (reference implementation)

## Related Files

- `/src/main/java/io/fullerstack/substrates/circuit/CircuitImpl.java`
- `/src/main/java/io/fullerstack/substrates/conduit/ConduitImpl.java`
- `/src/main/java/io/fullerstack/substrates/channel/ChannelImpl.java`
- `/src/main/java/io/fullerstack/substrates/queue/QueueImpl.java`
- `/src/main/java/io/fullerstack/substrates/pipe/PipeImpl.java`

## Priority

**CRITICAL** - This is a fundamental architectural violation that affects:
- Scalability (multiple threads per Circuit instead of single-threaded)
- Ordering guarantees
- QoS control
- Alignment with Substrates API design principles

Should be addressed before considering the implementation "complete" or production-ready.
