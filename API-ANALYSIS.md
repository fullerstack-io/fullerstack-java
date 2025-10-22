# Humainary Substrates API M17 - Analysis & Migration Guide

## Overview

This document analyzes the Humainary Substrates API M17 and documents the migration from M16 to M17.

**Current Version:** 1.0.0-M17
**Java Requirement:** Java 25
**Migration Completed:** October 2025

## M17 Major Changes

### Sealed Interfaces (Breaking Change)

M17 introduces sealed interfaces to enforce type hierarchy correctness:

```java
// Sealed type hierarchy
sealed interface Source<E> permits Context {}
sealed interface Context<E, S> permits Component {}
sealed interface Component<E, S> permits Circuit, Clock, Container {}
sealed interface Container<P, E, S> permits Conduit, Cell {}

// Non-sealed extension points (you can implement these)
non-sealed interface Circuit extends Component {}
non-sealed interface Conduit<P, E> extends Container {}
non-sealed interface Cell<I, E> extends Container {}
non-sealed interface Clock extends Component {}
non-sealed interface Sink<E> extends Substrate, Resource {}
non-sealed interface Channel<E> {}
non-sealed interface Subscriber<E> {}
```

**What This Means:**
- ✅ **You CAN implement:** Circuit, Conduit, Cell, Clock, Sink, Channel, Pipe, Subscriber
- ❌ **You CANNOT implement:** Source, Context, Component, Container (sealed)
- The API controls the type hierarchy to prevent incorrect compositions

### Impact on Fullerstack Implementation

#### Before M17 (M16):
```java
public class SourceImpl<E> implements Source<E> {
    // Implemented Source interface directly
}

private Source<E> source; // Used Source as field type
```

#### After M17:
```java
public class SourceImpl<E> {
    // No longer implements Source (it's sealed)
    // Just a utility class for subscriber management

    public Subscription subscribe(Subscriber<E> subscriber) {
        // Still provides subscribe() functionality
    }
}

private SourceImpl<E> source; // Use concrete type
```

**Key Changes:**
1. **SourceImpl** no longer implements `Source` interface
2. Changed all `Source<E>` field types to `SourceImpl<E>`
3. Updated constructors across: CircuitImpl, ConduitImpl, CellNode, ClockImpl, ChannelImpl
4. Functionality unchanged - still manages subscribers identically

## Architecture Implications

### Type Safety Benefits

The sealed hierarchy ensures:
- ✅ **Correct composition**: Can't create invalid combinations (e.g., Source without Substrate)
- ✅ **Evolution safety**: Humainary can change internals without breaking valid use cases
- ✅ **Clear contracts**: The `permits` clause documents all valid implementations

### Extension Points

M17 clearly defines where you can extend:

| Type | Status | Your Code Can... |
|------|--------|------------------|
| Source | Sealed | ❌ Not implement directly |
| Context | Sealed | ❌ Not implement directly |
| Component | Sealed | ❌ Not implement directly |
| Container | Sealed | ❌ Not implement directly |
| Circuit | Non-sealed | ✅ Implement (CircuitImpl) |
| Conduit | Non-sealed | ✅ Implement (ConduitImpl) |
| Cell | Non-sealed | ✅ Implement (CellNode) |
| Clock | Non-sealed | ✅ Implement (ClockImpl) |
| Sink | Non-sealed | ✅ Implement (SinkImpl) |
| Channel | Non-sealed | ✅ Implement (ChannelImpl) |
| Pipe | Non-sealed | ✅ Implement (PipeImpl) |

## Migration Guide (M16 → M17)

### Step 1: Update Dependencies

```xml
<!-- Before (M16) -->
<substrates-api.version>1.0.0-M16</substrates-api.version>
<serventis-api.version>1.0.0-M16</serventis-api.version>

<!-- After (M17) -->
<substrates-api.version>1.0.0-M17</substrates-api.version>
<serventis-api.version>1.0.0-M17</serventis-api.version>
```

### Step 2: Install M17 Locally

M17 is not on Maven Central yet. Build from source:

```bash
# Substrates API M17
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
mvn clean install -DskipTests
cd ..

# Serventis API M17
git clone https://github.com/humainary-io/serventis-api-java.git
cd serventis-api-java
mvn clean install -DskipTests
```

### Step 3: Refactor SourceImpl

If you have a class implementing `Source`:

```java
// Before (M16)
public class SourceImpl<E> implements Source<E> {
    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        // implementation
    }
}

// After (M17)
public class SourceImpl<E> {
    // No longer implements Source

    public Subscription subscribe(Subscriber<E> subscriber) {
        // same implementation, just not @Override
    }
}
```

### Step 4: Update Field Types

Change all references from `Source<E>` to `SourceImpl<E>`:

```java
// Before
private final Source<E> source;

public ChannelImpl(Name name, Scheduler scheduler, Source<E> source) {
    this.source = source;
}

// After
private final SourceImpl<E> source;

public ChannelImpl(Name name, Scheduler scheduler, SourceImpl<E> source) {
    this.source = source;
}
```

### Step 5: Update Tests

Add imports and update types in test files:

```java
import io.fullerstack.substrates.source.SourceImpl;

// Update variable types
SourceImpl<Instant> source = clock.getSource();
```

## API Surface

### Core Types (Sealed)

These define the architectural foundation:

```
Assembly (sealed root)
├── Context (sealed) - Things that are both Source + Substrate
│   └── Component (sealed) - Observable components
│       ├── Circuit (non-sealed) ✓
│       ├── Clock (non-sealed) ✓
│       └── Container (sealed)
│           ├── Conduit (non-sealed) ✓
│           └── Cell (non-sealed) ✓
```

### Extension Types (Non-sealed)

You implement these for functionality:
- **Circuit**: Event orchestration hub
- **Conduit**: Channel/Pipe/Source coordination
- **Cell**: Hierarchical state transformation
- **Clock**: Scheduled event emission
- **Sink**: Event capture
- **Channel**: Emission port
- **Pipe**: Event transformation
- **Subscriber**: Event consumer

## Fullerstack Implementation Summary

### What We Implement

| Interface | Our Class | Lines | Purpose |
|-----------|-----------|-------|---------|
| Circuit | CircuitImpl | 350 | Virtual CPU core pattern, queue management |
| Conduit | ConduitImpl | 150 | Channel/Source coordination |
| Cell | CellNode | 180 | Hierarchical state with transformations |
| Clock | ClockImpl | 130 | Scheduled emissions (shared scheduler) |
| Channel | ChannelImpl | 180 | Emission ports |
| Pipe | PipeImpl | 200 | Transformation pipelines |
| Sink | SinkImpl | 100 | Event storage |
| Name | NameNode | 210 | Hierarchical dot-notation names |
| *(internal)* | SourceImpl | 220 | Subscriber management (not Source interface) |

### What We Don't Implement

We **don't** provide our own implementations for:
- **Registry**: API provides via Circuit
- **Queue/Script**: Part of Circuit's internal model
- **Flow/Sift**: API provides default transformations
- **State/Slot**: API provides immutable state

## Testing Status

✅ **fullerstack-substrates:** 247 tests, 0 failures
✅ **fullerstack-serventis:** 12 tests, 0 failures

All tests pass with M17!

## Known Issues & Workarounds

### Issue: M17 Not on Maven Central

**Workaround:** Build and install locally from GitHub (see Step 2 above)

### Issue: Sealed Interfaces Prevent Extension

**Not Really an Issue:** This is intentional design. Only implement the `@Provided` (non-sealed) types.

## Future Considerations

If M18+ continues adding sealed interfaces:
- More types might become sealed
- May need to refactor other internal utilities
- As long as we stick to `@Provided` types, we're safe

## Recommendations

1. ✅ **Embrace sealed interfaces** - They enforce correct architecture
2. ✅ **Only implement non-sealed types** - Marked with `@Provided`
3. ✅ **Keep SourceImpl** - It's necessary for subscriber management
4. ✅ **Use concrete types** - `SourceImpl<E>` instead of `Source<E>`
5. ✅ **Follow API hierarchy** - Don't try to extend sealed types

## References

- Substrates API: https://github.com/humainary-io/substrates-api-java
- Serventis API: https://github.com/humainary-io/serventis-api-java
- Humainary Blog: https://humainary.io/blog/
- Java Sealed Classes: https://openjdk.org/jeps/409

---

**Last Updated:** October 2025
**API Version:** M17
**Migration Status:** ✅ Complete
