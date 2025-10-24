# Async-First Architecture in Substrates

**Status**: Core Design Principle
**Applies To**: All emissions, subscriber callbacks, and event processing

## Executive Summary

Substrates uses an **async-first** design where ALL emissions flow through the Circuit Queue asynchronously. This differs fundamentally from reactive frameworks like RxJava which are **synchronous by default**.

**Key Insight**: When you call `pipe.emit(value)`, the emission does NOT execute immediately. Instead, it posts a Script to the Circuit Queue and returns immediately. The actual processing happens later when the Queue's single virtual thread executes the Script.

## Async vs Sync: RxJava Comparison

### RxJava (Synchronous by Default)

```java
// RxJava BehaviorSubject
BehaviorSubject<String> subject = BehaviorSubject.create();

AtomicReference<String> received = new AtomicReference<>();
subject.subscribe(value -> received.set(value));

subject.onNext("hello");  // ← BLOCKS until subscriber callback completes

// received.get() is IMMEDIATELY "hello" (synchronous)
assertEquals("hello", received.get());  // ✅ Works (synchronous)
```

**Flow**:
```
onNext("hello")
  ↓ (synchronous call stack)
subscriber callback executes
  ↓ (synchronous call stack)
received.set("hello")
  ↓
onNext() returns
```

### Substrates (Asynchronous by Default)

```java
// Substrates Pipe
Circuit circuit = cortex.circuit();
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("test"),
    Composer.pipe()
);

AtomicReference<String> received = new AtomicReference<>();
conduit.source().subscribe(cortex.subscriber(
    cortex.name("sub"),
    (subject, registrar) -> registrar.register(received::set)
));

Pipe<String> pipe = conduit.get(cortex.name("channel"));
pipe.emit("hello");  // ← Returns IMMEDIATELY (posts Script to Queue)

// ❌ WRONG: received.get() is still NULL (async hasn't executed yet)
assertNull(received.get());

// ✅ CORRECT: Wait for queue to process
circuit.await();  // Blocks until queue empty
assertEquals("hello", received.get());  // Now it's available
```

**Flow**:
```
pipe.emit("hello")
  ↓
PipeImpl.postScript() creates Script
  ↓
circuitQueue.post(script)  // ← Returns immediately (async boundary)
  ↓
[Time passes - queue processing happens on virtual thread]
  ↓
Queue virtual thread executes Script
  ↓
conduit.processEmission(capture)
  ↓
subscriber callback executes
  ↓
received.set("hello")
```

## Circuit Queue Architecture

### Virtual CPU Core Pattern

Each Circuit acts as a **"Virtual CPU Core"** with:
- **Single Queue** (LinkedBlockingQueue)
- **Single virtual thread** processor
- **FIFO ordering** (strict FIFO guarantee)
- **All Conduits share the Queue**

**Architecture**:
```
Circuit (Virtual CPU Core)
  └─ Single Queue (QueueImpl)
       ├─ Conduit 1: Pipes post Scripts → circuitQueue.post()
       ├─ Conduit 2: Pipes post Scripts → circuitQueue.post()
       └─ Conduit 3: Pipes post Scripts → circuitQueue.post()

Queue processes Scripts sequentially (single-threaded)
  → Script 1: Conduit1.processEmission(capture)
  → Script 2: Conduit2.processEmission(capture)
  → Script 3: Conduit1.processEmission(capture)
```

### Benefits of Async-First Design

1. **Ordered Event Delivery** - FIFO queue guarantees order within Circuit
2. **Backpressure Management** - Single queue prevents saturation
3. **No Blocking** - Emitters never block (post and return)
4. **Simplified Threading** - Single virtual thread per Circuit
5. **Lock-Free Concurrency** - Single-threaded execution eliminates locks

### Emission Flow (Complete Path)

```
[External Code]
     │
     ├─→ pipe.emit(value)                    // User emits to Pipe
     │        │
     │        ↓
     │   [PipeImpl]
     │        │
     │        ├─→ flow.apply(value)?          // Optional: Apply transformations
     │        │
     │        ↓
     │   postScript(transformedValue)
     │        │
     │        ├─→ Create Capture<E>
     │        │
     │        ├─→ circuitQueue.post(current -> conduit.processEmission(capture))
     │        │
     │        └─→ RETURNS IMMEDIATELY (async boundary)
     │
     │   [Time passes - Script queued, emitter continues]
     │
     │   [LinkedBlockingQueue - FIFO]         // Circuit's single queue
     │        │
     │        ↓
     │   [Virtual Thread Processor]           // Single-threaded, sequential
     │        │
     │        ├─→ Runnable runnable = queue.take()  // Blocking take (FIFO)
     │        │
     │        ├─→ script.exec(current)
     │        │
     │        ↓
     │   [TransformingConduit.processEmission]
     │        │
     │        ├─→ Resolve Subscriber Pipes (cached or register new)
     │        │
     │        ↓
     │   [SourceImpl.notifySubscribers]
     │        │
     │        └─→ pipe.emit(value)            // Deliver to all registered outlet Pipes
     │                 │
     │                 ↓
     │            [Subscriber Logic]          // User's consumption logic
```

## circuit.await() - The Synchronization Primitive

### Purpose

`circuit.await()` blocks the calling thread until:
1. The Circuit Queue is empty (`!runnables.isEmpty()`)
2. No Runnable is currently executing (`!executing`)

### Implementation

```java
// LinkedBlockingQueueImpl.java:48-58
@Override
public void await() {
    // Block until queue is empty and nothing is currently executing
    while (running && (executing || !runnables.isEmpty())) {
        try {
            Thread.sleep(10);  // Poll every 10ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Queue await interrupted", e);
        }
    }
}
```

### When to Use circuit.await()

**Use Case 1: Testing**

This is the PRIMARY use case for `circuit.await()` in tests:

```java
@Test
void testEmission() throws Exception {
    // Setup
    Circuit circuit = cortex.circuit();
    Conduit<Pipe<String>, String> conduit = circuit.conduit(
        cortex.name("test"),
        Composer.pipe()
    );

    AtomicReference<String> received = new AtomicReference<>();
    conduit.source().subscribe(cortex.subscriber(
        cortex.name("sub"),
        (subject, registrar) -> registrar.register(received::set)
    ));

    // Act
    Pipe<String> pipe = conduit.get(cortex.name("channel"));
    pipe.emit("hello");

    // Assert - MUST wait for async processing
    circuit.await();  // ← CRITICAL for testing async emissions
    assertEquals("hello", received.get());
}
```

**Use Case 2: Graceful Shutdown**

```java
Circuit circuit = cortex.circuit();
// ... use circuit ...

// Ensure all pending emissions are processed before closing
circuit.await();
circuit.close();
```

**Use Case 3: Synchronization Points**

```java
// Emit batch of events
for (int i = 0; i < 100; i++) {
    pipe.emit(i);
}

// Wait for batch to complete before next phase
circuit.await();

// All 100 events have been processed - safe to proceed
startNextPhase();
```

### ❌ DON'T Use circuit.await() for Every Emission

**Anti-pattern** (defeats async design):

```java
// ❌ WRONG - Defeats async benefits
for (int i = 0; i < 1000; i++) {
    pipe.emit(i);
    circuit.await();  // BAD: Blocks after every emission
}
```

**Correct pattern**:

```java
// ✅ CORRECT - Leverages async queuing
for (int i = 0; i < 1000; i++) {
    pipe.emit(i);  // Posts to queue, returns immediately
}

// Only wait at the end (or not at all if you don't need to)
circuit.await();
```

## Testing Patterns

### ❌ Wrong Pattern: Using Latches

**This pattern is INCORRECT** because it assumes subscriber callbacks execute synchronously:

```java
// ❌ WRONG - Assumes synchronous execution
@Test
void testEmission_WRONG() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> received = new AtomicReference<>();

    conduit.source().subscribe(cortex.subscriber(
        cortex.name("sub"),
        (subject, registrar) -> registrar.register(value -> {
            received.set(value);
            latch.countDown();  // Trying to signal completion
        })
    ));

    pipe.emit("hello");

    // This may timeout because:
    // 1. emit() returns immediately (doesn't block)
    // 2. Subscriber callback runs asynchronously on Queue thread
    // 3. Race condition: latch.await() may start before callback executes
    assertTrue(latch.await(2, TimeUnit.SECONDS));  // ❌ May fail
}
```

### ✅ Correct Pattern: Using circuit.await()

```java
// ✅ CORRECT - Waits for Queue to process
@Test
void testEmission_CORRECT() throws Exception {
    AtomicReference<String> received = new AtomicReference<>();

    conduit.source().subscribe(cortex.subscriber(
        cortex.name("sub"),
        (subject, registrar) -> registrar.register(received::set)
    ));

    pipe.emit("hello");

    // Wait for Circuit Queue to process all pending Scripts
    circuit.await();

    // Now safe to assert
    assertEquals("hello", received.get());  // ✅ Always works
}
```

### When Latches ARE Appropriate

**CountDownLatch is appropriate for thread coordination**, not async queue synchronization:

```java
// ✅ CORRECT - Using latch for multi-threaded coordination
@Test
void testConcurrentAccess() throws Exception {
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            try {
                startLatch.await();  // Wait for start signal
                pipe.emit("value");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }).start();
    }

    startLatch.countDown();  // Start all threads
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS));  // Wait for threads

    circuit.await();  // THEN wait for queue processing
}
```

## Performance Characteristics

### Async Queue Overhead

**Cost of async boundary**:
- Script creation: ~5-10ns
- Queue.post(): ~15-20ns (offer to LinkedBlockingQueue)
- Context switch: ~50-100ns (virtual thread)
- **Total overhead**: ~70-130ns per emission

**Benefits**:
- No blocking on emit() - emitter continues immediately
- Ordered execution (FIFO guarantee)
- Backpressure control (bounded queue capacity if needed)
- Lock-free concurrency (single-threaded execution)

### Benchmark Results

From `SubstratesLoadBenchmark.java`:

| Operation | Latency | Notes |
|-----------|---------|-------|
| Pipe.emit() (hot path) | ~500ns | Includes Script creation + post |
| Full path (lookup + emit) | ~1μs | circuit → conduit → pipe → emit |
| Subscriber callback | ~1μs | End-to-end: emit → queue → callback |
| Multi-threaded emission (4 threads) | Linear scaling | No lock contention (virtual threads) |

## Cell Hierarchical Architecture and Async

### Cell Emission Flow

Cells also use async emission through the Circuit Queue:

```java
// Cell hierarchy: cluster → broker → partition
Cell<KafkaMetric, Alert> cluster = circuit.cell(composer);
Cell<KafkaMetric, Alert> broker1 = cluster.get(name("broker-1"));
Cell<KafkaMetric, Alert> partition0 = broker1.get(name("partition-0"));

// Emit at leaf level
partition0.emit(metric);  // ← Returns immediately (async)

// Transformation and distribution happen asynchronously:
// 1. partition0 transforms metric → alert
// 2. Emits to broker1's Source
// 3. Emits to cluster's Source
// All via Circuit Queue Scripts

circuit.await();  // Wait for async processing
```

### Parent Broadcast

```java
// Emit to parent broadcasts to ALL children (asynchronously)
broker1.emit(metric);  // ← Returns immediately

// Async flow:
// 1. Script posted to Circuit Queue
// 2. Queue processes: broadcast to all child Cells
// 3. Each child transforms metric → alert (async)
// 4. Each child emits to broker1's Source (async)

circuit.await();  // Wait for all children to process
```

## Async-First Design Rationale

### Why Async by Default?

1. **Simplifies Concurrency** - Single virtual thread per Circuit eliminates locks
2. **Natural Backpressure** - Queue provides bounded execution context
3. **Ordering Guarantees** - FIFO queue ensures event order
4. **Non-Blocking Producers** - Emitters never wait for consumers
5. **Predictable Performance** - Consistent FIFO processing, no priority inversions

### When Synchronous Would Be Wrong

**Scenario: Multiple slow consumers**

```java
// If synchronous (like RxJava):
source.subscribe(subscriber1);  // Slow - processes 100ms
source.subscribe(subscriber2);  // Slow - processes 100ms
source.subscribe(subscriber3);  // Slow - processes 100ms

pipe.emit("value");  // Would block for 300ms! (serial execution)

// Async (Substrates):
pipe.emit("value");  // Returns immediately
// Queue processes subscribers asynchronously
// Total time: ~100ms (parallel execution on virtual threads)
```

## Debugging Async Issues

### Common Mistake: Asserting Too Early

```java
// ❌ WRONG
pipe.emit("hello");
assertEquals("hello", received.get());  // NULL - async hasn't executed

// ✅ CORRECT
pipe.emit("hello");
circuit.await();
assertEquals("hello", received.get());  // Works - async completed
```

### Tracing Async Execution

**Add logging to see async flow**:

```java
// PipeImpl
pipe.emit(value);  // Log: "Emitting: value"

// Queue processor
script.exec(current);  // Log: "Executing script"

// Subscriber callback
registrar.register(value -> {
    System.out.println("Received: " + value);  // Log: "Received: value"
});
```

**Timeline**:
```
T+0ms:   emit("hello") - posts Script
T+0ms:   emit() returns (immediate)
T+10ms:  Queue picks up Script
T+10ms:  conduit.processEmission()
T+10ms:  Subscriber receives "hello"
```

### Verifying Queue Processing

```java
// Check if queue is processing
Circuit circuit = cortex.circuit();
Queue queue = circuit.queue();

pipe.emit("value");

// Queue should NOT be empty immediately
// (Script is in queue, not yet executed)

circuit.await();  // Block until empty

// Now queue IS empty
// (All Scripts executed)
```

## Summary Table

| Aspect | RxJava (Sync) | Substrates (Async) |
|--------|---------------|-------------------|
| **emit() behavior** | Blocks until subscribers complete | Returns immediately |
| **Subscriber callbacks** | Execute on calling thread | Execute on Queue virtual thread |
| **Ordering** | Not guaranteed (multi-threaded) | FIFO guarantee (single queue) |
| **Backpressure** | Manual (Flowable) | Built-in (queue monitoring) |
| **Testing pattern** | No special handling | Must use circuit.await() |
| **Threading model** | Configurable schedulers | Single virtual thread per Circuit |
| **Concurrency** | Locks needed for safety | Lock-free (single-threaded) |

## Best Practices

### DO

✅ Use `circuit.await()` in tests to wait for async processing
✅ Emit in batches, then wait once at the end
✅ Trust the Queue to process events in order
✅ Use async design for non-blocking producers
✅ Use Circuit.close() to gracefully shut down

### DON'T

❌ Use latches to wait for subscriber callbacks
❌ Call circuit.await() after every emit() in production
❌ Assume emissions are processed immediately
❌ Bypass the Queue with direct method calls
❌ Create multiple Circuits for same domain (defeats ordering)

## References

- **Queue Architecture**: [queues-scripts-currents.md](archive/alignment/queues-scripts-currents.md)
- **Circuit Architecture**: [CIRCUIT_QUEUE_ARCHITECTURE_ISSUE.md](archive/CIRCUIT_QUEUE_ARCHITECTURE_ISSUE.md)
- **Cell Implementation**: [CELL_HIERARCHICAL_ARCHITECTURE.md](/workspaces/fullerstack-java/CELL_HIERARCHICAL_ARCHITECTURE.md)
- **Performance Analysis**: [PERFORMANCE.md](PERFORMANCE.md)
- **Humainary Circuits Article**: https://humainary.io/blog/observability-x-circuits/
- **Humainary Queues Article**: https://humainary.io/blog/observability-x-queues-scripts-and-currents/

## Key Takeaway

**Substrates is async-first by design**. Every emission posts a Script to the Circuit Queue and returns immediately. Subscriber callbacks execute asynchronously on the Queue's single virtual thread. Use `circuit.await()` in tests to synchronize with async processing, but don't use it after every emit() in production code - leverage the async queue for performance.
