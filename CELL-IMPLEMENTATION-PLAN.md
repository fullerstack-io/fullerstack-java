# Cell Implementation Plan

Based on API facts and documentation only.

## What We Know for CERTAIN

### From the API:
1. `Cell<I, E>` IS-A `Pipe<I>` (has `emit(I)`)
2. `Cell<I, E>` IS-A `Source<E>` (has `subscribe(Subscriber<E>)`)
3. `Cell<I, E>` IS-A `Pool<Cell<I, E>>` (has `get(Name) → Cell<I, E>`)
4. `Composer<P, E>` receives `Channel<E>`, returns `P`
5. `Channel<E>` has `pipe()` returning `Pipe<E>`

### From Documentation:
> "Each Channel has a Pipe that can be wrapped by a percept, created by a Composer"

**This tells us:**
- Composer creates the percept
- Percept wraps Channel's Pipe
- Composer is in charge of creation

## The Minimal Cell Implementation

### What Cell MUST Have:

```java
class CellImpl<I, E> implements Cell<I, E> {
    // From Channel.pipe() - the wrapped pipe
    private final Pipe<E> channelPipe;

    // Transformation logic I → E
    private final Function<I, E> transformer;

    // For creating children
    private final Composer<Pipe<I>, E> composer;

    // Cache of children
    private final Map<Name, Cell<I, E>> children;

    // Our identity
    private final Subject cellSubject;
}
```

### What Cell MUST Do:

#### 1. Pipe<I> Implementation
```java
@Override
public void emit(I input) {
    E output = transformer.apply(input);
    channelPipe.emit(output);  // Delegate to wrapped pipe
}
```

**Why:**
- Cell receives I
- Transforms to E
- Emits E via the wrapped Channel's Pipe
- Channel's Pipe connects to Source (Circuit-provided)

#### 2. Source<E> Implementation
```java
@Override
public Subscription subscribe(Subscriber<E> subscriber) {
    // Cell IS-A Source<E>
    // But who actually manages subscribers?
    ???
}
```

**Problem:** Cell IS-A Source, but the actual Source is the one Circuit created and gave to Channel. How does Cell delegate subscribe() to that Source?

#### 3. Pool<Cell<I, E>> Implementation
```java
@Override
public Cell<I, E> get(Name name) {
    return children.computeIfAbsent(name, n -> {
        // Need to create a Channel for the child
        Channel<E> childChannel = ???;

        // Use composer to create child Cell
        Pipe<I> pipe = composer.compose(childChannel);
        return (Cell<I, E>) pipe;
    });
}
```

**Problems:**
- What Channel do we create for children?
- Do children share parent's Source or have their own?
- If own Source, how do emissions propagate up?

## The Critical Questions

### Q1: Who manages subscribers?

**Option A:** Circuit's Source
- Circuit creates Source<E>
- Circuit creates Channel<E> with that Source
- Cell delegates subscribe() to... where?

**Option B:** Cell creates its own Source
- Each Cell has its own Source
- Cell IS-A Source by having a Source field
- But then what's the Channel's Source for?

### Q2: What Source do children use?

**For hierarchical observability:**
- Need separate Sources per level
- Subscribe to parent = all child events
- Subscribe to child = just that child's events

**This requires:**
- Each Cell has its own Source
- Children must emit to parent's Source somehow
- Auto-subscription pattern?

### Q3: How does Channel connect to Source?

Looking at the API:
- Circuit creates Source<E>
- Circuit creates Channel<E>
- Circuit calls composer.compose(channel)

**The Channel must already be connected to the Source!**

Channel<E>.pipe() returns a Pipe<E> that, when emit(E) is called, notifies the Source's subscribers.

## Proposed Architecture

### Cell Does NOT Create Source

**Reasoning:**
- Circuit already created Source
- Circuit already created Channel connected to that Source
- Cell wraps Channel's Pipe
- Cell IS-A Source by... delegation? extension?

### Children Share Parent's Source OR Have Auto-Subscription

**Option 1: Shared Source**
```java
public Cell<I, E> get(Name name) {
    // Create child Channel with SAME Source as parent
    Channel<E> childChannel = new ChannelImpl<>(name, scheduler, parentSource, null);
    return (Cell<I, E>) composer.compose(childChannel);
}
```
- All children emit to same Source
- Can't filter by level
- ❌ Doesn't support hierarchical filtering

**Option 2: Own Source + Auto-Subscribe**
```java
public Cell<I, E> get(Name name) {
    // Create child with OWN Source
    Source<E> childSource = new SourceImpl<>(name);
    Channel<E> childChannel = new ChannelImpl<>(name, scheduler, childSource, null);
    Cell<I, E> child = (Cell<I, E>) composer.compose(childChannel);

    // Parent subscribes to child for upward propagation
    child.subscribe((subject, registrar) -> {
        registrar.register(emission -> {
            channelPipe.emit(emission);  // Forward to parent
        });
    });

    return child;
}
```
- Each Cell has own Source
- Auto-subscription propagates up
- ✓ Supports hierarchical filtering
- **But:** Cell needs access to Source to create children

### The Source Problem

If Cell uses Option 2, Cell needs:
```java
private final Source<E> source;  // To create child channels
```

But Cell IS-A Source<E>. So maybe:
```java
class CellImpl<I, E> implements Cell<I, E> {
    private final Source<E> source;

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        return source.subscribe(subscriber);  // Delegate
    }
}
```

**Cell IS-A Source by delegation!**

## The Complete Picture

```java
class CellImpl<I, E> implements Cell<I, E> {
    private final Subject cellSubject;
    private final Source<E> source;           // For subscribing
    private final Pipe<E> channelPipe;        // For emitting (from channel.pipe())
    private final Function<I, E> transformer; // I → E
    private final Composer<Pipe<I>, E> composer; // Create children
    private final Scheduler scheduler;        // For child channels
    private final Map<Name, Cell<I, E>> children;

    // Composer creates Cell
    public CellImpl(Channel<E> channel, Source<E> source, Function<I, E> transformer,
                    Composer<Pipe<I>, E> composer, Scheduler scheduler) {
        this.cellSubject = ...; // Create from channel.subject()
        this.source = source;
        this.channelPipe = channel.pipe();
        this.transformer = transformer;
        this.composer = composer;
        this.scheduler = scheduler;
        this.children = new ConcurrentHashMap<>();
    }

    // Pipe<I>
    @Override
    public void emit(I input) {
        E output = transformer.apply(input);
        channelPipe.emit(output);
    }

    // Source<E> - delegate
    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        return source.subscribe(subscriber);
    }

    // Pool<Cell<I, E>>
    @Override
    public Cell<I, E> get(Name name) {
        return children.computeIfAbsent(name, n -> {
            Name childName = cellSubject.name().name(n);
            Source<E> childSource = new SourceImpl<>(childName);
            Channel<E> childChannel = new ChannelImpl<>(childName, scheduler, childSource, null);
            Cell<I, E> child = (Cell<I, E>) composer.compose(childChannel);

            // Auto-subscribe for upward propagation
            child.subscribe((subject, registrar) -> {
                registrar.register(emission -> channelPipe.emit(emission));
            });

            return child;
        });
    }
}
```

## Composer Implementation

```java
public static <I, E> Composer<Pipe<I>, E> cellComposer(
    Function<I, E> transformer,
    Scheduler scheduler
) {
    return new Composer<>() {
        @Override
        public Pipe<I> compose(Channel<E> channel) {
            // Need the Source to pass to Cell!
            // But Channel doesn't expose its Source...
            // How do we get it?
            ???
        }
    };
}
```

**Problem:** Channel doesn't have a `source()` method in the API!

## Remaining Unknowns

1. How does Cell get the Source that Channel uses?
2. Does Channel need to expose source()?
3. Or does Cell create its own Source and ignore Channel's?
4. If Cell creates own Source, what is Channel even for?

**Next step:** Check if Channel has any way to access its Source, or if we need to rethink this.
