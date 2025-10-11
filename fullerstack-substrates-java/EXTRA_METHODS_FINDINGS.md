# Extra Public Methods Findings

**Date:** 2025-10-11

## Summary

Found several public methods in implementation classes that are NOT part of the Substrates API interfaces. These should be removed or made package-private/private.

## Critical Findings

### 1. SourceImpl
**File:** `src/main/java/io/fullerstack/substrates/source/SourceImpl.java`

**API Contract (Source<E>):**
- `Subject subject()` (from Substrate)
- `Subscription subscribe(Subscriber<E>)` (from Source)

**Extra Methods Found:**
- ❌ `public List<Subscriber<E>> getSubscribers()` - **REMOVE OR MAKE PACKAGE-PRIVATE**

**Action Required:**
This method is used by ConduitImpl to access subscribers. Options:
1. Make it package-private (allow same-package access only)
2. Keep as implementation detail but not part of public API
3. Rethink architecture if cross-package access is needed

---

### 2. ConduitImpl
**File:** `src/main/java/io/fullerstack/substrates/conduit/ConduitImpl.java`

**API Contract (Conduit<P, E>):**
- `Subject subject()` (from Substrate → Context → Component → Container/Pool)
- `Source<E> source()` (from Component → Context)
- `P get(Name)` (from Pool)
- `Conduit<P, E> tap(Consumer<? super Conduit<P, E>>)` (from Tap)

**Extra Methods Found:**
- ❌ `public void shutdown()` - **NOT IN API**

**Action Required:**
- Remove `shutdown()` method or explain why it's needed
- If lifecycle management is needed, it should go through `close()` from Resource interface

---

### 3. Anonymous Registrar in processEmission()
**File:** `src/main/java/io/fullerstack/substrates/conduit/ConduitImpl.java` line ~132

**Issue:**
```java
new Registrar<E>() {
    @Override
    public void register(Pipe<E> pipe) {
        pipes.add(pipe);
    }
}
```

The `register()` method appears in the public method list because it's part of an anonymous inner class. This is fine - not an issue.

---

## Recommendations

### Priority 1: Fix SourceImpl.getSubscribers()
```java
// Change from:
public List<Subscriber<E>> getSubscribers() {
    return subscribers;
}

// To:
List<Subscriber<E>> getSubscribers() {
    return subscribers;
}
```

This makes it package-private, allowing ConduitImpl to access it (same package) but not exposing it as public API.

### Priority 2: Remove or Fix Conduit.shutdown()
```java
// Either remove entirely, or if needed for lifecycle:
// Conduit should implement Resource which has close()
// Then shutdown() can be private and called from close()

private void shutdown() {
    if (queueProcessor != null && queueProcessor.isAlive()) {
        queueProcessor.interrupt();
    }
}
```

### Priority 3: Audit All Other Implementations
Continue systematic review of all 19 implementations to find other extra methods.

---

## Next Steps

1. ✅ Fix SourceImpl.getSubscribers() → package-private
2. ✅ Fix/Remove ConduitImpl.shutdown()
3. ⏳ Check remaining 17 implementations
4. ⏳ Verify all API methods are present
5. ⏳ Run tests after fixes
6. ⏳ Commit changes

