# Humainary Substrates API Analysis

## What We Know from the API ONLY

This document contains ONLY facts provable from the Humainary Substrates API interface definitions.
No implementation assumptions.

---

## Core Interfaces

### Pipe\<E>
```java
public interface Pipe<E> {
    void emit(E);
}
```

**Facts:**
- Has one method: `emit(E)`
- Takes type parameter E

**Questions:**
- Is emit() synchronous or asynchronous?
- Does emit() notify subscribers?
- Where do emissions go?

---

### Source\<E>
```java
public interface Source<E> {
    Subscription subscribe(Subscriber<E>);
}
```

**Facts:**
- Has one method: `subscribe(Subscriber<E>)`
- Returns a Subscription
- Takes type parameter E

**Questions:**
- Who notifies subscribers?
- When are subscribers notified?
- Does Source store subscribers?

---

### Pool\<P>
```java
public interface Pool<P> {
    P get(Name);
    default P get(Substrate<?>);
    default P get(Subject<?>);
}
```

**Facts:**
- Has `get(Name)` returning P
- Has default methods taking Substrate and Subject

**Questions:**
- Does get() create new instances or retrieve existing?
- Who is responsible for creating P?
- Is P cached/memoized?

---

### Component\<E, S>
```java
public interface Component<E, S extends Component<E, S>>
    extends Context<E, S> {
}
```

**Facts:**
- Extends Context<E, S>
- No methods of its own
- Takes emission type E and self-type S

---

### Context\<E, S>
```java
public interface Context<E, S extends Context<E, S>>
    extends Source<E>, Substrate<S> {
}
```

**Facts:**
- Extends Source<E>
- Extends Substrate<S>
- No methods of its own

**Implications:**
- Context IS-A Source<E>

---

### Container\<P, E, S>
```java
public interface Container<P, E, S extends Container<P, E, S>>
    extends Pool<P>, Component<E, S> {
}
```

**Facts:**
- Extends Pool<P>
- Extends Component<E, S>
- No methods of its own
- Takes percept type P, emission type E, self-type S

**Implications:**
- Container IS-A Pool<P> (has get(Name) → P)
- Container IS-A Component<E, S> → Context<E, S> → Source<E>
- Container can be subscribed to for E
- Container can retrieve P by Name

---

### Conduit\<P, E>
```java
public interface Conduit<P, E>
    extends Container<P, E, Conduit<P, E>>,
            Tap<Conduit<P, E>> {
}
```

**Facts:**
- Extends Container<P, E, Conduit<P, E>>
- Extends Tap<Conduit<P, E>>
- No methods of its own

**Implications:**
- Conduit IS-A Container, so:
  - IS-A Pool<P> (has get(Name) → P)
  - IS-A Source<E> (has subscribe(Subscriber<E>))
- Conduit IS-A Tap (has tap(Consumer))

**Questions:**
- What is the relationship between P and E?
- How does P relate to emissions of type E?
- Who creates P when get(Name) is called?

---

### Cell\<I, E>
```java
public interface Cell<I, E>
    extends Pipe<I>,
            Container<Cell<I, E>, E, Cell<I, E>>,
            Extent<Cell<I, E>, Cell<I, E>> {
}
```

**Facts:**
- Extends Pipe<I>
- Extends Container<Cell<I, E>, E, Cell<I, E>>
- Extends Extent<Cell<I, E>, Cell<I, E>>
- No methods of its own
- Takes TWO type parameters: I and E

**Implications:**
- Cell IS-A Pipe<I> (has emit(I))
- Cell IS-A Container, so:
  - IS-A Pool<Cell<I, E>> (has get(Name) → Cell<I, E>)
  - IS-A Source<E> (has subscribe(Subscriber<E>))
- Cell IS-A Extent (has hierarchy methods)

**Key Observations:**
- Cell receives type I (via emit(I))
- Cell emits type E (via Source<E>)
- Cell.get(Name) returns Cell<I, E> (same types)
- **I and E can be different types!**

**Questions:**
- How does I transform to E?
- When does transformation happen?
- Do child Cells created via get() share transformation logic?
- Does emit(I) trigger subscribers of Source<E>?

---

## Creation APIs

### Circuit
```java
public interface Circuit extends Component<State, Circuit> {
    <P, E> Conduit<P, E> conduit(Composer<? extends P, E>);
    <P, E> Conduit<P, E> conduit(Name, Composer<? extends P, E>);
    <P, E> Conduit<P, E> conduit(Name, Composer<? extends P, E>, Consumer<Flow<E>>);

    <I, E> Cell<I, E> cell(Composer<Pipe<I>, E>);
    <I, E> Cell<I, E> cell(Composer<Pipe<I>, E>, Consumer<Flow<E>>);

    // ... other methods
}
```

**Facts:**
- Circuit can create Conduit<P, E> given a Composer<? extends P, E>
- Circuit can create Cell<I, E> given a Composer<Pipe<I>, E>
- Both accept optional Consumer<Flow<E>> for transformations

**Key Observations:**
- Conduit creation: Composer produces P
- Cell creation: Composer produces Pipe<I> (which must be Cell<I, E>)
- Cell Composer signature: `Composer<Pipe<I>, E>` not `Composer<Cell<I, E>, E>`

**Questions:**
- What does the Composer do?
- What input does Composer receive?
- Who creates Source for Conduit/Cell?

---

### Composer\<P, E>
```java
@FunctionalInterface
public interface Composer<P, E> {
    P compose(Channel<E> channel);
}
```

**Facts:**
- Receives a Channel<E>
- Returns P
- Functional interface (single method)

**Implications:**
- For Conduit<P, E>: Composer receives Channel<E>, returns P
- For Cell<I, E>: Composer receives Channel<E>, returns Pipe<I>

**Critical Observation:**
- Cell's Composer receives Channel<E> (emission type)
- Cell's Composer returns Pipe<I> (input type)
- **Channel type matches emission type, not input type!**

**Questions:**
- What is the Channel used for?
- Who creates the Channel?
- Does Composer create the Cell or just configure it?

---

### Channel\<E>
```java
public interface Channel<E> extends Context<E, Channel<E>> {
    Pipe<E> pipe();
    Pipe<E> pipe(Consumer<? super Flow<E>>);
}
```

**Facts:**
- Extends Context<E, Channel<E>>
- Has `pipe()` returning Pipe<E>
- Has `pipe(Consumer<Flow<E>>)` for transformations
- Takes type parameter E

**Implications:**
- Channel IS-A Context → Source<E>
- Channel can create Pipes
- Channel can apply Flow transformations

**Questions:**
- What does the Pipe returned by pipe() do?
- Where do emissions to that Pipe go?
- Is Channel the connection to Source?

---

## Type Relationships Matrix

| Entity | Input Type | Output/Emission Type | Container Type | Notes |
|--------|-----------|---------------------|----------------|-------|
| **Pipe\<E>** | E (via emit) | ? | N/A | Where do emissions go? |
| **Source\<E>** | ? | E (to subscribers) | N/A | Who triggers emissions? |
| **Conduit\<P, E>** | ? | E | P | What's relationship between P and E? |
| **Cell\<I, E>** | I (via emit) | E (to subscribers) | Cell<I, E> | How does I → E happen? |
| **Composer\<P, E>** | Channel\<E> | P | N/A | Creates P from Channel<E> |
| **Channel\<E>** | ? | E | N/A | Creates Pipe<E> |

---

## Critical Unknowns

### 1. Cell Type Transformation
- **Known:** Cell<I, E> receives I, emits E
- **Unknown:** WHERE and WHEN does I → E transformation happen?
- **Unknown:** WHO provides the transformation logic?

### 2. Pool.get() Semantics
- **Known:** get(Name) returns P
- **Unknown:** Does get() create new P or retrieve existing?
- **Unknown:** WHO creates P?
- **Unknown:** Is P cached?

### 3. Composer Role
- **Known:** Composer receives Channel<E>, returns P
- **Known (from docs):** "Each Channel has a Pipe that can be wrapped by a percept, created by a Composer"
- **Known:** Composer creates a "percept" (P) that wraps Channel's Pipe
- **Unknown:** HOW does P wrap the Pipe?
- **Unknown:** Does P delegate to the Pipe or extend it?
- **Unknown:** Who provides the Channel to Composer? (Circuit)

### 4. Source vs Pipe Connection
- **Known:** Pipe<E> has emit(E)
- **Known:** Source<E> has subscribe()
- **Unknown:** How are they connected?
- **Unknown:** Does emit() trigger Source subscribers?

### 5. Child Cell Creation
- **Known:** Cell.get(Name) returns Cell<I, E>
- **Known:** Composer is in charge of creation (from docs)
- **Inference:** Cell.get() should use its Composer to create children
- **Pattern:** Cell has a Composer, calls composer.compose(channel) in get()
- **Unknown:** Do children share resources with parent?
- **Unknown:** What Channel does Cell.get() create for the child?

---

## Pattern Analysis

### Conduit Pattern
```
Circuit.conduit(composer) → Conduit<P, E>
  ↓
Conduit.get(name) → P
  ↓
Conduit.subscribe(subscriber) → receives E
```

**Questions:**
- How does P relate to E emissions?
- Does P emit E somehow?

### Cell Pattern
```
Circuit.cell(composer) → Cell<I, E>
  ↓
Cell.emit(I) → ???
Cell.get(name) → Cell<I, E>
Cell.subscribe(subscriber) → receives E
```

**Questions:**
- How does emit(I) produce E for subscribers?
- How are child Cells created?
- Do child emissions flow to parent subscribers?

---

## Key Insights from Documentation

From Humainary documentation:
> "Each Channel has a Pipe that can be wrapped by a percept, created by a Composer"

### What This Tells Us:

1. **Composer creates percepts** - The Composer is responsible for creating P
2. **Percepts wrap Channel's Pipe** - P wraps the Pipe that Channel provides
3. **Channel provides the Pipe** - Channel.pipe() gives us the Pipe to wrap

### For Cell<I, E>:

```
Circuit creates:
  Source<E>
  Channel<E> (connected to Source)

Circuit calls:
  composer.compose(channel) → returns Pipe<I> (which is Cell<I, E>)

Cell (percept) wraps:
  Channel's Pipe<E>

Cell adds:
  I → E transformation
  emit(I) method
  Container behavior (get(Name))
```

### The Pattern:

```java
class CellImpl<I, E> implements Cell<I, E> {
    private final Pipe<E> channelPipe;  // From channel.pipe()
    private final Function<I, E> transformer;
    private final Composer<Pipe<I>, E> composer;  // For creating children

    // Composer creates Cell, wrapping channel's pipe
    public CellImpl(Channel<E> channel, Function<I, E> transformer, Composer<Pipe<I>, E> composer) {
        this.channelPipe = channel.pipe();
        this.transformer = transformer;
        this.composer = composer;
    }

    // Pipe<I> implementation - transforms and delegates
    public void emit(I input) {
        E output = transformer.apply(input);
        channelPipe.emit(output);  // Delegate to wrapped pipe
    }

    // Container implementation - uses composer to create children
    public Cell<I, E> get(Name name) {
        Channel<E> childChannel = ...; // Create child channel
        return (Cell<I, E>) composer.compose(childChannel);
    }
}
```

---

## Next Steps

To resolve remaining unknowns, we need to:

1. **Study the Composer contract** - What is it supposed to do with Channel?
2. **Understand Channel's role** - Why does Cell Composer receive Channel<E>?
3. **Determine transformation location** - Where does I → E happen in Cell?
4. **Clarify Pool semantics** - Does get() create or retrieve?
5. **Map emission flow** - How do emissions flow from Pipe to Source to Subscribers?

**Most Critical Question:**
> For Cell<I, E>, when you call emit(I), how does that become an emission of E to subscribers?
> Where does the transformation logic live?
