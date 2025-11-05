# Humainary API Compliance Skill

You are an expert at maintaining **100% API compliance** between Humainary Substrates/Serventis APIs and the fullerstack-substrates implementation.

## Skill Purpose

This skill handles the complete workflow of:
1. **Discovering** new Humainary API versions
2. **Auditing** fullerstack-substrates for missing/outdated methods
3. **Implementing** all missing API methods
4. **Testing** the implementation
5. **Committing** compliance updates

## When to Use This Skill

Invoke this skill when:
- User says "update to latest Humainary API"
- User asks to "pull latest from Humainary"
- You detect API version mismatch
- User requests "full API compliance audit"
- After upgrading Substrates/Serventis versions

## Humainary Repository Locations

### Local Clones
- **Substrates API**: `/workspaces/substrates-api-java`
- **Serventis API**: Bundled in Substrates as `ext/serventis`

### Implementation
- **fullerstack-substrates**: `/workspaces/fullerstack-java/fullerstack-substrates`
  - Main: `src/main/java/io/fullerstack/substrates/CortexRuntime.java`
  - Tests: `src/test/java/io/fullerstack/substrates/**/*Test.java`

## Step-by-Step Workflow

### Phase 1: Pull Latest API

```bash
# 1. Pull Substrates API
cd /workspaces/substrates-api-java
git fetch origin
git log origin/main --oneline -5  # Check what's new
git pull origin main

# 2. Check version
grep "<revision>" pom.xml  # Should show RC5, RC6, etc.

# 3. Build and install locally
mvn clean install -DskipTests
```

### Phase 2: Audit Missing Methods

**Critical Files to Check:**
1. **Cortex interface**: `substrates-api-java/api/src/main/java/io/humainary/substrates/api/Substrates.java`
   - Start line: `interface Cortex {`
   - Methods: circuit(), current(), name(), pool(), scope(), state(), sink(), slot(), subscriber(), pipe()

2. **Circuit interface**: Same file, `interface Circuit {`
   - Methods: conduit(), cell(), subscribe(), await(), close()

3. **Conduit interface**: Same file, `interface Conduit {`
   - Methods: get(), subscribe()

4. **Channel interface**: Same file, `interface Channel {`
   - Methods: pipe(), pipe(Flow.Configurer)

**Audit Script:**
```bash
# Count API methods in Cortex
cd /workspaces/substrates-api-java/api/src/main/java/io/humainary/substrates/api
awk '/interface Cortex \{/,/^  interface Circuit/' Substrates.java | \
  grep -E "^\s{4}[A-Z]" | grep "(" | wc -l

# Count implemented methods in CortexRuntime
cd /workspaces/fullerstack-java/fullerstack-substrates/src/main/java/io/fullerstack/substrates
grep "@Override" CortexRuntime.java | wc -l

# If counts don't match → missing methods!
```

**Find Exact Missing Methods:**
- Try compiling: `mvn clean compile`
- Look for: "CortexRuntime is not abstract and does not override abstract method X"
- OR manually diff interface vs implementation

### Phase 3: Implement Missing Methods

**Pattern to Follow:**

```java
// In CortexRuntime.java

// ========== [Section Name] (X methods) ==========

@Override
public ReturnType methodName(ParamType param) {
    Objects.requireNonNull(param, "param cannot be null");

    // Implementation here
    return result;
}
```

**Common Implementations:**

1. **Name factory methods** → Delegate to `HierarchicalName.of()`
2. **Pool methods** → Create `new ConcurrentPool<>(factory)`
3. **Scope methods** → Create `new ManagedScope(name)`
4. **State methods** → Use `LinkedState.empty()` or `LinkedState.of()`
5. **Pipe methods** → Create anonymous Pipe implementation
6. **Slot methods** → Delegate to `TypedSlot.of()`
7. **Subscriber methods** → Delegate to `FunctionalSubscriber`
8. **Sink methods** → Delegate to `CollectingSink`

**Example - Missing pipe() method:**
```java
@Override
public Pipe<Object> pipe() {
    return new Pipe<Object>() {
        @Override
        public void emit(Object value) {
            // No-op: discard emissions
        }

        @Override
        public void flush() {
            // No-op: no buffering
        }
    };
}
```

### Phase 4: Test Implementation

```bash
cd /workspaces/fullerstack-java/fullerstack-substrates

# 1. Compile
mvn clean compile

# 2. Run tests
mvn test

# Expected: All tests passing
# If failures → fix implementation
```

**Key Test Files:**
- `CortexRuntimeTest.java` - Tests Cortex methods
- Circuit tests in `circuit/` directory
- Conduit tests in `conduit/` directory
- Integration tests in `integration/` directory

### Phase 5: Update Documentation

Update the JavaDoc comment at top of `CortexRuntime.java`:

```java
/**
 * Complete implementation of Substrates.Cortex interface.
 * <p>
 * This class implements ALL XX methods of the Cortex interface (RC5),
 * providing full Humainary Substrates API compliance.
 * <p>
 * Methods include:
 * <ul>
 *   <li>Circuit management (X methods)</li>
 *   <li>Current management (1 method)</li>
 *   <li>Pipe factory (X methods)</li>
 *   <li>Name factory (X methods)</li>
 *   <li>Pool management (X methods)</li>
 *   <li>Scope management (X methods)</li>
 *   <li>State factory (X methods)</li>
 *   <li>Sink creation (X methods)</li>
 *   <li>Slot management (X methods)</li>
 *   <li>Subscriber management (X methods)</li>
 * </ul>
 */
```

### Phase 6: Commit and Push

```bash
cd /workspaces/fullerstack-java

git add -A
git commit -m "feat: Implement missing Substrates RCX API methods

Complete 100% API compliance with Humainary Substrates RCX.

Added methods:
- Cortex: [list methods]
- Circuit: [list methods]
- [other interfaces]

Testing:
- All XXX tests passing ✅
- Zero compilation errors ✅
- Full RCX API coverage ✅

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"

git push
```

## Critical Rules

### ✅ DO
- **Always compile** after implementing methods
- **Run full test suite** before committing
- **Check for breaking changes** in API between versions
- **Update JavaDoc** to reflect new method counts
- **Follow existing patterns** in CortexRuntime
- **Use Objects.requireNonNull()** for parameter validation
- **Delegate to existing implementations** when possible

### ❌ DON'T
- Don't skip tests - they catch API mismatches
- Don't guess at implementations - check existing patterns
- Don't implement in wrong class (Cortex methods go in CortexRuntime)
- Don't forget to handle null checks
- Don't break existing functionality

## Common Issues and Solutions

### Issue: "cannot find symbol: method X"
**Cause**: API added new method, not implemented yet
**Solution**: Implement method in appropriate class

### Issue: "method X cannot be applied to given types"
**Cause**: Method signature changed in API
**Solution**: Update parameter types/order to match API

### Issue: Tests failing after update
**Cause**: Breaking changes in API behavior
**Solution**: Update test expectations or implementation logic

### Issue: "is not abstract and does not override abstract method"
**Cause**: Missing method implementation
**Solution**: Add @Override method with correct signature

## Verification Checklist

Before completing, verify:
- [ ] Latest Humainary API pulled and installed locally
- [ ] All Cortex methods implemented
- [ ] All Circuit methods implemented
- [ ] All Conduit/Channel methods implemented
- [ ] Compilation successful (zero errors)
- [ ] All tests passing
- [ ] JavaDoc updated with method counts
- [ ] Changes committed with descriptive message
- [ ] Changes pushed to remote

## Output Format

When complete, provide:

```
## Humainary API Compliance Update Complete ✅

**Version**: Substrates RCX / Serventis RCX

### Changes Made:
- Implemented X missing Cortex methods
- Implemented Y missing Circuit methods
- Updated Z existing methods for new signatures

### Testing Results:
- ✅ XXX tests passing
- ✅ Zero compilation errors
- ✅ 100% API coverage achieved

### Commits:
- abc1234 - feat: Implement missing RCX API methods

### Next Steps:
[Any follow-up tasks, if needed]
```

## Version-Specific Notes

### RC5 → RC6 Changes (Example)
- Added: `Cortex.current()` - Returns Current execution context
- Changed: Serventis artifact ID
- Added: Three new Serventis APIs (Caches, Counters, Gauges)

### Known Patterns

**RC1-RC3**: Method-based Serventis instruments
**RC5+**: Added Caches/Counters/Gauges, Current interface
**M18**: Static Cortex access pattern

## End of Skill

Use this systematic approach to maintain 100% API compliance with Humainary Substrates/Serventis APIs.
