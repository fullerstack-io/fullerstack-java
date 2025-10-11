# Resources, Scopes, and Closures Implementation Alignment

**Date**: 2025-10-11
**Article**: https://humainary.io/blog/observability-x-resources-scopes-and-closures/
**Status**: ⚠️ MOSTLY ALIGNED (1 issue found)

## Summary

Our implementation of Resources, Scopes, and Closures is mostly aligned with the Humainary article, with one architectural issue regarding reverse closure ordering.

## Article's Key Requirements

### 1. ✅ Resource Interface

**Article States:**
> "Resources - Encapsulates anything requiring explicit closure when no longer in use"

**API Definition:**
```java
@Abstract
sealed interface Resource
  permits Component, Sink, Subscription {

  @Idempotent
  default void close() { }
}
```

**What Implements Resource:**
- **Component** → Circuit, Clock, Container
- **Sink** → SinkImpl
- **Subscription** → SubscriptionImpl

**Our Implementation:**
All our implementations correctly implement Resource:
- ✅ `CircuitImpl implements Circuit` (which extends Component → Resource)
- ✅ `ClockImpl implements Clock` (which extends Component → Resource)
- ✅ `ContainerImpl implements Container` (which extends Component → Resource)
- ✅ `SinkImpl implements Sink` (which extends Resource)
- ✅ `SubscriptionImpl implements Subscription` (which extends Resource)

**Verification**: ✅ Correct

### 2. ✅ Scope Interface

**Article States:**
> "Scopes - Establishes a bounded context for managing resources"
> "Automatically handles cleanup when context terminates"
> "Can be named and nested"
> "Extends the Extent interface, allowing iteration through enclosing scopes"

**API Definition:**
```java
@Utility
@Provided
@Temporal
interface Scope
  extends Substrate,
          Extent<Scope>,
          AutoCloseable {

  void close();
  <R extends Resource> R register(R resource);
  <R extends Resource> Closure<R> closure(R resource);
  Scope scope(Name name);
  Scope scope();
}
```

**Our Implementation:**
```java
public class ScopeImpl implements Scope {
    private final Name name;
    private final Scope parent;
    private final Map<Name, Scope> childScopes = new ConcurrentHashMap<>();
    private final Map<Object, Resource> resources = new ConcurrentHashMap<>();

    // Named scope creation ✓
    public Scope scope(Name name) {
        return childScopes.computeIfAbsent(name, n -> new ScopeImpl(n, this));
    }

    // Anonymous child scope ✓
    public Scope scope() {
        return new ScopeImpl(name, this);
    }

    // Resource registration ✓
    public <R extends Resource> R register(R resource) {
        resources.put(resource, resource);
        return resource;
    }

    // Closure creation ✓
    public <R extends Resource> Closure<R> closure(R resource) {
        register(resource);
        return new ClosureImpl<>(resource);
    }

    // Extends Extent<Scope> ✓
    public Optional<Scope> enclosure() {
        return Optional.ofNullable(parent);
    }
}
```

**Verification**: ✅ Correct - All Scope features implemented

### 3. ✅ Closure Interface

**Article States:**
> "Closures - Bridges resources and scopes"
> "Provides controlled access to resources"
> "Ensures proper resource management and prevents resource leaks"

**API Definition:**
```java
@Utility
@Temporal
interface Closure<R extends Resource> {
  void consume(Consumer<? super R> consumer);
}
```

**Our Implementation:**
```java
public class ClosureImpl<R extends Resource> implements Closure<R> {
    private final R resource;

    @Override
    public void consume(Consumer<? super R> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");

        // ARM pattern: use resource and ensure close() is called
        try {
            consumer.accept(resource);
        } finally {
            try {
                resource.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close resource", e);
            }
        }
    }
}
```

**Verification**: ✅ Correct - ARM pattern properly implemented

### 4. ✅ Try-With-Resources Pattern

**Article's Code Example:**
```java
try (final var scope = cortex.scope()) {
  scope
    .closure(cortex.circuit())
    .consume(circuit -> scope.closure(
      circuit.conduit(
        String.class,
        Inlet::pipe
      )
    ));
}
```

**Our Implementation Works:**
```java
// Scope implements AutoCloseable ✓
interface Scope extends AutoCloseable { ... }

// Can be used in try-with-resources ✓
try (Scope scope = cortex.scope()) {
    // Resources registered here are automatically closed
}
```

**From our tests:**
```java
scope.closure(resource).consume(r -> {
    // Use resource here
    assertThat(r).isSameAs(resource);
});
// resource.close() called automatically ✓
```

**Verification**: ✅ Correct

### 5. ✅ Automatic Cleanup

**Article States:**
> "Automatically handles cleanup when context terminates"

**Our Implementation:**
```java
@Override
public void close() {
    if (closed) {
        return;
    }
    closed = true;

    // Close all child scopes
    for (Scope scope : childScopes.values()) {
        try {
            scope.close();
        } catch (Exception e) {
            // Log but continue closing others
        }
    }

    // Close all resources
    resources.values().forEach(resource -> {
        try {
            resource.close();
        } catch (Exception e) {
            // Log but continue closing others
        }
    });

    resources.clear();
    childScopes.clear();
}
```

**Verification**: ✅ Correct - Hierarchical cleanup with error handling

### 6. ⚠️ **Reverse Resource Ordering**

**Article States:**
> "Reverse resource ordering during closure"

**Issue in Our Implementation:**

**ScopeImpl.java:25**
```java
private final Map<Object, Resource> resources = new ConcurrentHashMap<>();
```

**Problem**: `ConcurrentHashMap` does **NOT** maintain insertion order!

Resources should be closed in **REVERSE** order of registration (LIFO - Last In, First Out), similar to how try-with-resources works in Java. This ensures that dependent resources are closed before their dependencies.

**Example**:
```java
scope.register(database);    // 1st
scope.register(connection);  // 2nd (depends on database)
scope.register(transaction); // 3rd (depends on connection)

// Should close in reverse order:
transaction.close();   // 3rd closed first
connection.close();    // 2nd
database.close();      // 1st closed last
```

**Current Behavior**: Resources are closed in **undefined order** (HashMap iteration order)

**Fix Required**:
```java
// Replace ConcurrentHashMap with LinkedHashMap + synchronization
// Or use ConcurrentLinkedDeque for LIFO ordering
private final Deque<Resource> resources = new ConcurrentLinkedDeque<>();

public <R extends Resource> R register(R resource) {
    checkClosed();
    resources.addFirst(resource);  // Add to front for LIFO
    return resource;
}

@Override
public void close() {
    // ... close child scopes first ...

    // Close resources in LIFO order (reverse of registration)
    resources.forEach(resource -> {
        try {
            resource.close();
        } catch (Exception e) {
            // Log but continue
        }
    });

    resources.clear();
}
```

**Verification**: ❌ **INCORRECT** - Not maintaining reverse order

### 7. ✅ Nested Resource Management

**Article States:**
> "Nested resource construct management"

**Our Implementation:**
```java
@Test
void shouldCloseChildScopes() {
    Scope parent = new ScopeImpl(NameImpl.of("parent"));
    Scope child = parent.scope(NameImpl.of("child"));
    TestResource childResource = new TestResource();

    child.register(childResource);
    parent.close();

    assertThat(childResource.closed).isTrue();  // ✓ Works
}
```

**ScopeImpl.close():**
```java
// Close all child scopes FIRST
for (Scope scope : childScopes.values()) {
    try {
        scope.close();
    } catch (Exception e) {
        // Log but continue
    }
}

// Then close resources
resources.values().forEach(resource -> { ... });
```

**Verification**: ✅ Correct - Child scopes closed before parent's resources

### 8. ✅ Idempotent Close

**Article States:**
> Resource interface has `@Idempotent` close method

**Our Implementation:**
```java
@Override
public void close() {
    if (closed) {
        return;  // Multiple close() calls are safe ✓
    }
    closed = true;
    // ... cleanup ...
}
```

**From tests:**
```java
@Test
void shouldAllowMultipleCloses() {
    Scope scope = new ScopeImpl(NameImpl.of("test"));
    scope.close();
    scope.close(); // Should not throw ✓
}
```

**Verification**: ✅ Correct

### 9. ✅ Closure Registers Resource

**Article Pattern:**
```java
scope.closure(resource).consume(r -> { ... });
```

**Our Implementation:**
```java
public <R extends Resource> Closure<R> closure(R resource) {
    checkClosed();
    register(resource);  // ✓ Resource is registered with scope
    return new ClosureImpl<>(resource);
}
```

**Verification**: ✅ Correct - Closure automatically registers resource

### 10. ✅ Extent Interface Support

**Article States:**
> "Extends the Extent interface, allowing iteration through enclosing scopes"

**Our Implementation:**
```java
public class ScopeImpl implements Scope {
    private final Scope parent;

    @Override
    public Optional<Scope> enclosure() {
        return Optional.ofNullable(parent);  // ✓ Returns parent
    }

    @Override
    public CharSequence part() {
        return name.part();  // ✓ Returns name part
    }
}
```

**Scope extends Extent<Scope>**, which provides:
- `iterator()` - iterate through scope hierarchy
- `stream()` - stream of scopes from this to root
- `depth()` - depth in hierarchy
- `path()` - full hierarchical path

**Verification**: ✅ Correct

## Test Coverage

**Tests that verify article's concepts:**

```java
// ✅ Resource registration and cleanup
@Test
void shouldCloseRegisteredResources() {
    scope.register(resource1);
    scope.register(resource2);
    scope.close();
    assertThat(resource1.closed).isTrue();
    assertThat(resource2.closed).isTrue();
}

// ✅ Child scope cleanup
@Test
void shouldCloseChildScopes() {
    child.register(childResource);
    parent.close();
    assertThat(childResource.closed).isTrue();
}

// ✅ Closure ARM pattern
@Test
void shouldSupportClosure() {
    scope.closure(resource).consume(r -> {
        consumed.set(true);
    });
    assertThat(resource.closed).isTrue();
}

// ✅ Prevent operations after close
@Test
void shouldPreventOperationsAfterClose() {
    scope.close();
    assertThatThrownBy(() -> scope.register(resource))
        .isInstanceOf(IllegalStateException.class);
}
```

## Alignment Summary

| Concept | Article Requirement | Our Implementation | Status |
|---------|-------------------|-------------------|--------|
| Resource interface | ✓ | Circuit, Clock, Sink, Subscription implement Resource | ✅ |
| Scope as bounded context | ✓ | ScopeImpl with resource tracking | ✅ |
| Named and nested scopes | ✓ | scope(Name), scope(), parent tracking | ✅ |
| Extends Extent | ✓ | Scope extends Extent<Scope> | ✅ |
| Closure ARM pattern | ✓ | ClosureImpl with try-finally | ✅ |
| Automatic cleanup | ✓ | close() handles children + resources | ✅ |
| **Reverse resource ordering** | ✓ | **ConcurrentHashMap (no order!)** | ❌ |
| Nested resource mgmt | ✓ | Children closed before parent resources | ✅ |
| Idempotent close | ✓ | Multiple close() calls safe | ✅ |
| Closure registers resource | ✓ | closure() calls register() | ✅ |
| Try-with-resources support | ✓ | Scope implements AutoCloseable | ✅ |

## Critical Issue

### ❌ **Reverse Resource Ordering Not Implemented**

**Impact**: MEDIUM
- Resources may be closed in wrong order
- Could cause issues if resources have dependencies
- Violates article's stated principle

**Required Fix**:
Replace `Map<Object, Resource>` with `Deque<Resource>` to maintain LIFO order:

```java
// ScopeImpl.java
private final Deque<Resource> resources = new ConcurrentLinkedDeque<>();

public <R extends Resource> R register(R resource) {
    checkClosed();
    resources.addFirst(resource);  // LIFO ordering
    return resource;
}

@Override
public void close() {
    // ... close children ...

    // Resources are iterated in LIFO order (reverse of registration)
    resources.forEach(resource -> {
        try {
            resource.close();
        } catch (Exception e) {
            // Log but continue
        }
    });

    resources.clear();
}
```

**Priority**: MEDIUM - Should be fixed to ensure proper resource cleanup ordering

## Conclusion

**Status: ⚠️ MOSTLY ALIGNED (1 issue)**

Our Resources, Scopes, and Closures implementation correctly implements **10 out of 11** key concepts from the Humainary article:

✅ **Correct**:
1. Resource interface and implementations
2. Scope as bounded context
3. Named and nested scopes
4. Extent interface integration
5. Closure ARM pattern
6. Automatic cleanup
7. Nested resource management
8. Idempotent close
9. Closure auto-registration
10. Try-with-resources support

❌ **Incorrect**:
1. **Reverse resource ordering** - Using ConcurrentHashMap instead of ordered collection

**Recommendation**: Fix the resource ordering issue by replacing the `Map` with a `Deque` to ensure LIFO (Last In, First Out) closure ordering, matching Java's try-with-resources semantics.

## References

- **Article**: https://humainary.io/blog/observability-x-resources-scopes-and-closures/
- **Key Quote**: "Reverse resource ordering during closure"
- **Java Try-With-Resources**: Closes resources in reverse order of declaration
