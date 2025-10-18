# Cell Architecture Questions for Substrates M15+

## Context
I've successfully migrated fullerstack-substrates from API M13 to M15+ (all 269 tests passing). During this migration, I encountered architectural questions about the **Cell** component that I'd appreciate clarification on.

## Cell Understanding So Far

From the M15+ API, I understand:

```java
interface Cell<I, E> extends Pipe<I>, Container<Cell<I, E>, E>
```

**Key characteristics:**
- Implements `Pipe<I>` - can receive input of type I via `emit(I)`
- Extends `Container<Cell<I, E>, E>` - manages a pool of child Cells
- Performs **type transformation**: receives I, children emit E
- Differs from `Conduit<Pipe<E>, E>` which has no type transformation

**Critical insight about Composer:**
The `Composer<Pipe<I>, E>` passed to `Circuit.cell()` cannot create just a simple Pipe - since Cell implements BOTH Pipe<I> AND Container<Cell<I,E>, E>, the Composer must somehow create or compose Cells, not just Pipes. This is the source of my architectural questions.

## Implementation Questions

### Question 1: Is there a Composer.cell() method?

I noticed the pattern:
- `Composer.pipe()` creates `Composer<Pipe<E>, E>`
- `Composer.channel()` creates `Composer<Channel<E>, E>`

**Question:** Is there a `Composer.cell()` that creates `Composer<Cell<I,E>, E>`?

If so, that would resolve my implementation questions!

### Question 2: How should Cell.get() create child Cells?

My current implementation:
```java
public Cell<I, E> get(Name name) {
    return childCells.computeIfAbsent(name, n -> {
        Channel<E> channel = new ChannelImpl<>(name, scheduler, source, flowConfigurer);
        Pipe<I> pipe = composer.compose(channel);

        // This cast assumes composer returns something that IS-A Cell<I,E>
        @SuppressWarnings("unchecked")
        Cell<I, E> childCell = (Cell<I, E>) pipe;

        return childCell;
    });
}
```

**The architectural question:**

Since Cell<I,E> extends BOTH Pipe<I> AND Container<Cell<I,E>, E>, the Composer<Pipe<I>, E> cannot just create a simple Pipe - it must create something that implements both interfaces.

**Possible interpretations:**
1. The Composer is expected to return a **Cell<I,E>** (which IS-A Pipe<I>)
2. There's a Cell-specific composer (Composer.cell()?) that explicitly creates Cells
3. Cell.get() should create child Cells directly (like cell division) rather than via Composer

**Current uncertainty:** I'm casting `Pipe<I> → Cell<I, E>`, which works at runtime but suggests I don't understand the intended creation pattern.

### Question 3: What is Cell's intended use case?

I initially thought of a "neural network" pattern where Cells form connections, but the API doesn't seem to support this. What are the intended scenarios for using Cell?

**Possible patterns I considered:**
- Hierarchical type transformation (raw sensor data → processed events)
- Fan-out with transformation (one input type, multiple output types)
- Adaptive signal processing networks
- Something else entirely?

### Question 4: How do Cells compose hierarchically?

Given:
```java
Cell<KafkaMetric, Alert> cell = circuit.cell(composer);
Cell<KafkaMetric, Alert> child = cell.get(name("cpu-monitor"));
```

When `cell.emit(metric)` is called:
1. Does it propagate to ALL children? (my current impl)
2. Should children be independent Cells or wrapped Pipes?
3. How does the I → E transformation actually occur?

## My Implementation

**File:** `fullerstack-substrates/src/main/java/io/fullerstack/substrates/cell/CellImpl.java`

**Current behavior:**
- Cell.emit(I) → broadcasts to all child Cells
- Each child Cell receives I
- Children use Composer to transform I → E
- Results emitted to shared Source<E>

**Uncertainty:** The Composer creates Pipe<I>, which I cast to Cell<I, E>. This works at runtime but I know is incorrect but I just did it to migrate the rest of the API.

## Request

Could you provide:
1. **Intended usage examples** for Cell
2. **Clarification** on Cell.get() semantics
3. **Guidance** on Composer's role with Cells
4. Any **test examples** from Substrates codebase showing Cell usage

## Migration Status

✅ **269 tests passing**
✅ All M15+ features implemented (Flow, Consumer<Flow>, Circuit.cell(), etc.)
✅ Code compiles successfully
⚠️  Cell implementation has architectural questions

---

**Repository:** https://github.com/fullerstack-io/fullerstack-java
**Branch:** main
**Commit:** 8ec048b - "feat: Migrate fullerstack-substrates to Substrates API M15+"
