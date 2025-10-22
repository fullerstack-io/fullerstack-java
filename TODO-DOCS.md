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

## Documentation Files to Review:

-  docs/ARCHITECTURE.md - May reference old registry/queue implementations
- docs/ASYNC-ARCHITECTURE.md - Should be current
- docs/IMPLEMENTATION-GUIDE.md - May reference old factory patterns
- docs/PERFORMANCE.md - Needs benchmark updates
- docs/CONCEPTS.md - May reference removed concepts
- docs/examples/*.md - All examples may need M17 updates

## Priority:

1. High: fullerstack-substrates README features and quick start
2. High: fullerstack-serventis README
3. Medium: Architecture docs
4. Low: Example docs (can be updated incrementally)
