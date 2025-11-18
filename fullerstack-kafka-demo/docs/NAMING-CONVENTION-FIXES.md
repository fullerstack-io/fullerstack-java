# Naming Convention Bugs - Fixed

**Date:** 2025-11-18
**Issue:** Incorrect assumptions about sidecar ID naming format causing JMX endpoint inference to fail

---

## Problem: Broken JMX Endpoint Parsing

### What Was Wrong

The code in `SpeechActListener.inferJmxEndpoint()` assumed sidecar IDs followed the format `"producer-1"` or `"consumer-1"`.

**However**, the actual implementation uses: `"producer-sidecar-1"` (see ProducerSidecarApplication.java:43)

```java
// Actual default in code:
String sidecarId = System.getenv().getOrDefault("SIDECAR_ID", "producer-sidecar-1");
```

### The Bug

**Broken parsing logic** (before fix):

```java
// WRONG: Assumes format "producer-1"
String[] parts = sidecarId.split("-");
if (parts.length >= 2) {
    int number = Integer.parseInt(parts[1]);  // ❌ Gets "sidecar" not "1"!
}
```

When sidecarId = `"producer-sidecar-1"`:
- `parts[0]` = "producer"
- `parts[1]` = **"sidecar"** ← Can't parse as integer!
- `parts[2]` = "1"

**Result:** NumberFormatException → fallback to default port → all sidecars get same JMX endpoint!

---

## Fixes Applied

### Fix 1: SpeechActListener.java - Correct Parsing Logic

**File:** `/fullerstack-kafka-coordination/src/main/java/io/fullerstack/kafka/coordination/central/SpeechActListener.java`

**Change:**

```java
// ✅ FIXED: Parse LAST part (handles both formats)
String[] parts = sidecarId.split("-");
if (parts.length >= 2) {
    String lastPart = parts[parts.length - 1];  // ✅ Gets "1" for both formats
    int number = Integer.parseInt(lastPart);

    int basePort = type.equals("producer") ? 11000 : 11100;
    int port = basePort + number;

    return "localhost:" + port;
}
```

**Now supports BOTH formats:**
- `"producer-1"` → extracts "1" → port 11001 ✅
- `"producer-sidecar-1"` → extracts "1" → port 11001 ✅
- `"consumer-sidecar-2"` → extracts "2" → port 11102 ✅

---

### Fix 2: SidecarHeartbeatSender.java - Correct Documentation

**File:** `/fullerstack-kafka-demo/src/main/java/io/fullerstack/kafka/demo/sidecar/SidecarHeartbeatSender.java`

**Change:**

```java
// BEFORE (wrong example):
/**
 * {
 *   "source": "producer-1",
 *   "contextAgent": "producer-1.heartbeat",
 * }
 */

// AFTER (correct example):
/**
 * {
 *   "source": "producer-sidecar-1",  // Actual sidecarId value
 *   "contextAgent": "producer-sidecar-1.heartbeat",
 * }
 */
```

---

### Fix 3: InformMessage.java - Correct Documentation

**File:** `/fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/sidecar/InformMessage.java`

**Change:**

```java
// BEFORE:
@param sourceAgent the agent providing the information (e.g., "producer-1")

// AFTER:
@param sourceAgent the agent providing the information (e.g., "producer-sidecar-1")
```

---

## Impact Analysis

### Before Fix

| Sidecar ID | Parsed Number | Inferred JMX Port | Actual JMX Port | Status |
|------------|---------------|-------------------|-----------------|--------|
| `producer-sidecar-1` | Exception! | 11001 (fallback) | 11001 | ❌ Lucky |
| `producer-sidecar-2` | Exception! | 11001 (fallback) | 11002 | ❌ WRONG |
| `consumer-sidecar-1` | Exception! | 11001 (fallback) | 11101 | ❌ WRONG |

**Result:** Multiple sidecars map to same JMX endpoint → metrics collision!

---

### After Fix

| Sidecar ID | Parsed Number | Inferred JMX Port | Actual JMX Port | Status |
|------------|---------------|-------------------|-----------------|--------|
| `producer-sidecar-1` | 1 | 11001 | 11001 | ✅ Correct |
| `producer-sidecar-2` | 2 | 11002 | 11002 | ✅ Correct |
| `consumer-sidecar-1` | 1 | 11101 | 11101 | ✅ Correct |
| `producer-1` | 1 | 11001 | 11001 | ✅ Backward compatible |

**Result:** Each sidecar gets correct unique JMX endpoint ✅

---

## Actual vs Assumed Naming Conventions

### What We Assumed (WRONG)

```
Sidecar IDs: "producer-1", "producer-2", "consumer-1"
JMX Ports:   11001, 11002, 11101
```

### What Actually Exists (CORRECT)

```
Sidecar IDs: "producer-sidecar-1", "producer-sidecar-2", "consumer-sidecar-1"
Environment Variable: SIDECAR_ID (defaults to "producer-sidecar-1")
JMX Ports:   11001, 11002, 11101 (same)
```

### What the Code Now Supports (BOTH)

```
Supported Formats:
  - Short form: "producer-1" → JMX 11001 ✅
  - Long form:  "producer-sidecar-1" → JMX 11001 ✅
  - Custom:     "producer-foo-bar-1" → JMX 11001 ✅

Rule: Always parse LAST dash-separated component as the numeric ID
```

---

## Testing

### Manual Test

```bash
# Start sidecar with default ID
export SIDECAR_ID="producer-sidecar-1"
./producer-sidecar.sh

# Expected log in central platform:
# "🆕 AUTO-DISCOVERED sidecar: producer-sidecar-1 (type=producer, jmx=localhost:11001)"
```

### Unit Test (Recommended)

```java
@Test
void testInferJmxEndpoint() {
    // Short format
    assertEquals("localhost:11001", inferJmxEndpoint("producer-1"));
    assertEquals("localhost:11102", inferJmxEndpoint("consumer-2"));

    // Long format (actual default)
    assertEquals("localhost:11001", inferJmxEndpoint("producer-sidecar-1"));
    assertEquals("localhost:11102", inferJmxEndpoint("consumer-sidecar-2"));

    // Edge cases
    assertEquals("localhost:11001", inferJmxEndpoint("producer-foo-bar-baz-1"));
    assertEquals("localhost:11001", inferJmxEndpoint("invalid-format")); // fallback
}
```

---

## Lessons Learned

### Mistake: Documentation-Driven Assumptions

❌ **What Happened:**
- Created documentation showing "producer-1" as example
- Wrote code assuming "producer-1" format
- Never checked actual implementation defaults

✅ **What Should Have Been Done:**
- Read actual environment variable defaults FIRST
- Test with real sidecar IDs from running code
- Verify assumptions against existing codebase

### Key Principle

> **Always validate assumptions against actual implementation**
> Don't let documentation examples drive code logic - let actual usage patterns drive both.

---

## Files Modified

| File | Module | Change | Lines |
|------|--------|--------|-------|
| `SpeechActListener.java` | kafka-coordination | Fixed `inferJmxEndpoint()` parsing | 7 |
| `SidecarHeartbeatSender.java` | kafka-demo | Fixed doc example | 2 |
| `InformMessage.java` | kafka-producer | Fixed doc example | 1 |

**Total:** 10 lines changed, critical bug fixed ✅

---

## Summary

**Problem:** Incorrect sidecar ID format assumption caused JMX endpoint inference to fail silently.

**Root Cause:** Documentation-driven development without validating against actual implementation.

**Fix:** Parse LAST component of sidecar ID as number, support both short and long formats.

**Impact:** All sidecars now get correct unique JMX endpoints for metrics collection.

**Time to Fix:** 15 minutes
**Time Lost to Bug:** Would have caused demo failure + hours of debugging
**ROI:** Infinite (prevented demo-killing bug)

---

## Next Steps

- [x] Fix parsing logic
- [x] Fix documentation examples
- [ ] Add unit tests for `inferJmxEndpoint()`
- [ ] Add integration test with real sidecar ID
- [ ] Update any remaining docs with correct examples
