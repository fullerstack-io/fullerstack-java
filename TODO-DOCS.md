# Documentation TODO

## fullerstack-substrates/README.md

Need to update for M17 and recent refactoring:

### Sections to Update:

1. **Features** (lines 10-21):
   - Remove "Factory Patterns" (we removed NameFactory, QueueFactory, RegistryFactory)
   - Remove "Sequencer/Segment" references (API provides Flow/Sift now)
   - Remove "Identity Map Fast Path" (we removed InternedName + LazyTrieRegistry)
   - Update "Source" description (now internal SourceImpl for subscriber management)
   - Add "NameNode" - hierarchical dot-notation names
   - Add "CellNode" - hierarchical state transformation
   - Update "Queue" description (internal to Circuit)

2. **Performance** (lines 23-38):
   - Update benchmarks to reflect current simplified implementation
   - Remove identity map optimization references
   - Re-run and update with current numbers (if needed)

3. **Quick Start - Basic Usage** (lines 52-80):
   - Fix `Source<String> source = conduit.source();` - this won't compile in M17
   - Circuit implements Source via sealed hierarchy, not via exposed source() method
   - Need to update example to show proper M17 usage

4. **Throughout**:
   - Replace "NameTree" → "NameNode"
   - Replace "CellTree" → "CellNode"
   - Update API version references M13/M15 → M17
   - Note that Source is sealed in M17

## fullerstack-serventis/README.md

Update for M17:

1. Update API version to M17
2. Note sealed interface changes in Serventis API
3. Update Monitor/Service subject() signature changes

## Documentation Files Status:

### ✅ COMPLETED
1. fullerstack-substrates/README.md - Updated for M17 and simplified architecture
2. fullerstack-serventis/README.md - Updated for M17
3. Root README.md - Updated for M17
4. API-ANALYSIS.md - Complete rewrite for M17

### ⚠️ NEEDS EXTENSIVE UPDATES

#### docs/ARCHITECTURE.md - CRITICAL
Found 45+ references to removed implementations:
- LazyTrieRegistry, RegistryFactory, NameFactory, QueueFactory
- InternedName, identity map optimization
- Sequencer/Segment (now Flow/Sift)

**Recommendation:** Archive and rewrite from scratch focusing on:
- NameNode implementation
- CellNode hierarchies
- Virtual CPU core pattern
- M17 sealed interfaces
- Simplified caching (just ConcurrentHashMap)

#### docs/IMPLEMENTATION-GUIDE.md - CRITICAL
30+ references to removed optimizations.
This guide is almost entirely about things we removed!

**Recommendation:** Archive and rewrite as "Best Practices" guide:
- NameNode usage patterns
- Flow/Sift transformations
- Resource lifecycle management
- Testing strategies

#### docs/PERFORMANCE.md - CRITICAL
40+ references to identity map benchmarks we no longer have.

**Recommendation:** Rewrite focusing on:
- Test suite performance (247 tests in ~16s)
- Production readiness claims
- Virtual CPU core benefits
- Shared scheduler optimization

#### docs/CONCEPTS.md - MAJOR UPDATES
25+ references to removed implementations.

**Recommendation:** Update to remove:
- Factory pattern sections
- Identity map sections
- Name implementation comparisons
Update transformation sections (Sequencer → Flow/Sift)

### ✓ SHOULD BE CURRENT
- docs/ASYNC-ARCHITECTURE.md - Verify no outdated references

### 📝 CHECK LATER
- docs/examples/*.md - May have outdated API usage patterns

## Recommended Approach:

Given the extensive outdated content, I recommend:

1. **Archive outdated docs** to docs/archive/pre-m17-refactoring/
2. **Rewrite critical docs** from scratch rather than trying to edit
3. **Focus on current implementation** not removed optimizations
