# Documentation Index

**Last Updated:** October 16, 2025
**Version:** 1.0.0-SNAPSHOT

This index provides a complete map of Substrates documentation, helping you find the right information quickly.

---

## 📚 Quick Navigation

### For New Users
1. **[README](../README.md)** - Start here! Quick start and basic examples
2. **[Core Concepts](CONCEPTS.md)** - Understanding Substrates entities and relationships
3. **[Examples](examples/README.md)** - Hands-on examples from simple to complex

### For Developers
1. **[Architecture Guide](ARCHITECTURE.md)** - System design, data flow, and design principles
2. **[Async-First Architecture](ASYNC-ARCHITECTURE.md)** ⚠️ **CRITICAL** - Understanding async queue processing
3. **[Implementation Guide](IMPLEMENTATION-GUIDE.md)** - Recommended patterns and best practices
4. **[Advanced Topics](ADVANCED.md)** - Performance tuning, custom implementations, extensions

### For Performance Engineers
1. **[Performance Guide](PERFORMANCE.md)** - Complete performance analysis and benchmarks
2. **[Name Implementation Comparison](name-implementation-comparison.md)** - Name strategy selection guide

### For Alignment Reference
1. **[Alignment Overview](alignment/README.md)** - Humainary Substrates API alignment
2. **[Substrates 101](alignment/substrates-101.md)** - Core philosophy and concepts

---

## 📖 Document Descriptions

### Core Documentation

#### [README.md](../README.md)
**Purpose:** Project overview, quick start, and basic usage examples
**Audience:** Everyone
**What's Inside:**
- Maven dependency setup
- Basic usage examples
- Quick start guide
- Project overview

#### [CONCEPTS.md](CONCEPTS.md)
**Purpose:** Deep dive into Substrates entities and their relationships
**Audience:** Developers learning the framework
**What's Inside:**
- Entity definitions (Circuit, Conduit, Channel, Pipe, Source, etc.)
- Entity relationships and lifecycle
- **Async-First Architecture** ⚠️ **CRITICAL** - Understanding async queue processing
- Hierarchical name system
- Factory patterns (NameFactory, QueueFactory, RegistryFactory)
- Subject temporal identity
- Resource management patterns

**Key Concepts:**
- **Async-First Design** - All emissions are asynchronous via Circuit Queue
- **Persistent Temporal Identity** - Every entity caches its Subject at construction
- **Hierarchical Ownership** - Names build naturally via containment
- **Identity Map Fast Path** - InternedName + LazyTrieRegistry for 5× performance
- **Pool Singleton Pattern** - Same name → same instance throughout

#### [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) ⚠️ **CRITICAL**
**Purpose:** Complete guide to async-first design and queue processing
**Audience:** ALL developers using Substrates
**What's Inside:**
- **Async vs Sync comparison** (Substrates vs RxJava)
- **Circuit Queue Architecture** - Virtual CPU Core pattern
- **Emission flow** - Complete path from emit() to subscriber
- **circuit.await()** - The synchronization primitive for testing
- **Testing patterns** - ✅ Correct vs ❌ Wrong patterns
- **Performance characteristics** - Async queue overhead
- **Cell hierarchical architecture** - Async in Cell emissions
- **Debugging async issues** - Common mistakes and solutions

**Critical Insight:**
- `pipe.emit(value)` returns **immediately** (async boundary)
- Subscriber callbacks execute **asynchronously** on Queue thread
- **MUST use `circuit.await()` in tests** to wait for processing
- **DON'T use latches** for queue synchronization (race condition)

#### [ARCHITECTURE.md](ARCHITECTURE.md)
**Purpose:** System architecture, design principles, and data flow
**Audience:** Developers and architects
**What's Inside:**
- System architecture overview
- Data flow diagrams (producer → conduit → consumer)
- Queue architecture and threading model
- Design principles and patterns
- Component interactions
- Factory injection patterns

**Key Patterns:**
- Virtual thread per Circuit (daemon auto-cleanup)
- Single queue per Circuit (ordered execution)
- Identity map fast path (InternedName optimization)
- Factory dependency injection (pluggable implementations)

#### [ADVANCED.md](ADVANCED.md)
**Purpose:** Advanced topics, performance tuning, and extensions
**Audience:** Expert users and performance engineers
**What's Inside:**
- Performance optimization techniques
- Custom implementations (Name, Registry, Queue)
- Factory pattern usage
- Threading and concurrency patterns
- Resource lifecycle management
- Testing strategies

**Advanced Topics:**
- LazyTrieRegistry for 50% hot-path speedup
- InternedName for identity map fast path
- Hierarchical query patterns
- Multi-threading considerations

---

### Performance Documentation

#### [PERFORMANCE.md](PERFORMANCE.md) ⭐ **AUTHORITATIVE**
**Purpose:** Complete performance analysis, benchmarks, and recommendations
**Audience:** Performance engineers, architects, and developers
**What's Inside:**
- **Executive Summary** - Quick performance wins
- **Hot-Path Performance** - Emission, lookup, full-path benchmarks
- **Cold-Path Performance** - Initialization and creation costs
- **Registry Comparison** - LazyTrieRegistry vs FlatMap vs EagerTrie
- **Name Comparison** - InternedName vs LinkedName vs SegmentArray
- **Integration Results** - Before/after LazyTrieRegistry integration
- **Production Guidelines** - Kafka monitoring performance budget
- **Optimization Guide** - When and how to optimize

**Key Results:**
- **Hot-path emission: 3.3ns** (2× faster than before)
- **Cached lookups: 4-7ns** (5-12× faster via identity map + slot optimization)
- **Full path: 30ns** (3.4× faster with slot optimization!)
- **Kafka monitoring: 0.033% CPU** for 100k metrics @ 1Hz

**Recommendations:**
- ✅ **Use InternedName** (default) - Identity map fast path
- ✅ **Use LazyTrieRegistry** (default) - Best overall performance
- ✅ **Use Default factories** - Optimized for production

#### [name-implementation-comparison.md](name-implementation-comparison.md)
**Purpose:** Detailed comparison of Name implementations
**Audience:** Developers choosing Name strategy
**What's Inside:**
- InternedName, LinkedName, SegmentArrayName, LRUCachedName comparison
- Performance benchmarks for each
- Memory characteristics
- Recommendation: InternedName for production

**Quick Answer:** Use InternedName (default) unless you have specific requirements.

---

### Implementation Guides

#### [IMPLEMENTATION-GUIDE.md](IMPLEMENTATION-GUIDE.md)
**Purpose:** Recommended patterns and best practices
**Audience:** Developers building with Substrates
**What's Inside:**
- **Factory Injection Patterns** - How to use NameFactory, QueueFactory, RegistryFactory
- **Entity Creation Patterns** - Best practices for Circuits, Conduits, Channels
- **Caching Patterns** - ComputeIfAbsent and singleton identity
- **Hierarchical Name Patterns** - Building organic hierarchies
- **Resource Management** - Using Scopes and Closures
- **Testing Patterns** - How to test Substrates-based code
- **Common Pitfalls** - What to avoid

**Recommended Patterns:**
```java
// ✅ Use default factories (optimized)
Cortex cortex = new CortexRuntime();

// ✅ Cache circuits by name
Circuit circuit = cortex.circuit(cortex.name("my-circuit"));

// ✅ Use computeIfAbsent pattern
Pipe<T> pipe = conduit.get(name); // Automatic caching

// ✅ Build hierarchical names naturally
Name brokerName = cortex.name("kafka.broker.1");
Name metricName = brokerName.name("jvm.heap.used");
```

---

### Alignment Documentation

#### [alignment/README.md](alignment/README.md)
**Purpose:** Overview of Humainary Substrates API alignment
**Audience:** Contributors and API users
**What's Inside:**
- Alignment status with Humainary API
- Implementation completeness
- Philosophy and design principles

#### [alignment/substrates-101.md](alignment/substrates-101.md)
**Purpose:** Core Substrates philosophy and concepts
**Audience:** Everyone
**What's Inside:**
- Humainary Substrates philosophy
- Semiotic observability concepts
- Design principles
- Core abstractions

#### Other Alignment Docs
Each alignment doc covers a specific Substrates entity:
- **[subjects.md](alignment/subjects.md)** - Subject identity and lifecycle
- **[channels.md](alignment/channels.md)** - Channel concepts and usage
- **[circuits.md](alignment/circuits.md)** - Circuit architecture
- **[composers.md](alignment/composers.md)** - Composer patterns
- **[containers.md](alignment/containers.md)** - Container composition
- **[queues-scripts-currents.md](alignment/queues-scripts-currents.md)** - Queue architecture
- **[resources-scopes-closures.md](alignment/resources-scopes-closures.md)** - Resource management
- **[states-slots.md](alignment/states-slots.md)** - State and slot concepts
- **[subscribers.md](alignment/subscribers.md)** - Subscriber patterns

---

### Examples

#### [examples/README.md](examples/README.md)
**Purpose:** Hands-on examples from simple to complex
**Audience:** Developers learning the framework
**What's Inside:**
- Example progression (Hello World → Advanced)
- Code samples with explanations
- Common patterns and recipes

#### Individual Examples
1. **[01-HelloSubstrates.md](examples/01-HelloSubstrates.md)** - Basic emission and subscription
2. **[02-Transformations.md](examples/02-Transformations.md)** - Using Sequencer/Segment
3. **[03-MultipleSubscribers.md](examples/03-MultipleSubscribers.md)** - Multiple consumer patterns
4. **[04-ResourceManagement.md](examples/04-ResourceManagement.md)** - Scope and Closure usage

---

## 🔍 Find by Topic

### Factory Patterns
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Factory patterns section
- **Implementation:** [IMPLEMENTATION-GUIDE.md](IMPLEMENTATION-GUIDE.md) - Factory injection patterns
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - Pluggable factories

### Performance
- **Primary:** [PERFORMANCE.md](PERFORMANCE.md) - Complete guide
- **Hot-path:** [PERFORMANCE.md](PERFORMANCE.md) - Hot-path performance section
- **Name choice:** [name-implementation-comparison.md](name-implementation-comparison.md)
- **Registry choice:** [PERFORMANCE.md](PERFORMANCE.md) - Registry comparison section

### Entity Relationships
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Entity relationships
- **Data flow:** [ARCHITECTURE.md](ARCHITECTURE.md) - Data flow section
- **Examples:** [examples/01-HelloSubstrates.md](examples/01-HelloSubstrates.md)

### Hierarchical Names
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Hierarchical name system
- **Implementation:** [IMPLEMENTATION-GUIDE.md](IMPLEMENTATION-GUIDE.md) - Name patterns
- **Performance:** [name-implementation-comparison.md](name-implementation-comparison.md)

### Resource Management
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Resource lifecycle
- **Alignment:** [alignment/resources-scopes-closures.md](alignment/resources-scopes-closures.md)
- **Example:** [examples/04-ResourceManagement.md](examples/04-ResourceManagement.md)

### Async Architecture & Queue Processing ⚠️
- **PRIMARY:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - **MUST READ**
- **Quick Reference:** [CONCEPTS.md](CONCEPTS.md) - Async-First Architecture section
- **Implementation:** [ARCHITECTURE.md](ARCHITECTURE.md) - Queue architecture details
- **Alignment:** [alignment/queues-scripts-currents.md](alignment/queues-scripts-currents.md)

### Threading & Concurrency
- **Primary:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - Virtual CPU Core pattern
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - Queue architecture
- **Advanced:** [ADVANCED.md](ADVANCED.md) - Concurrency patterns
- **Performance:** [PERFORMANCE.md](PERFORMANCE.md) - Multi-threading benchmarks

### Testing
- **PRIMARY:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - Testing async patterns ⚠️
- **Implementation:** [IMPLEMENTATION-GUIDE.md](IMPLEMENTATION-GUIDE.md) - Testing patterns
- **Advanced:** [ADVANCED.md](ADVANCED.md) - Testing strategies

---

## 📊 Documentation Status

| Document | Status | Last Updated | Completeness |
|----------|--------|--------------|--------------|
| README.md | ✅ Current | Oct 2025 | Complete |
| CONCEPTS.md | ✅ Current | Oct 2025 | Complete |
| ASYNC-ARCHITECTURE.md | ✅ Current | Oct 2025 | Complete ⚠️ **CRITICAL** |
| ARCHITECTURE.md | ✅ Current | Oct 2025 | Complete |
| ADVANCED.md | ✅ Current | Oct 2025 | Complete |
| PERFORMANCE.md | ✅ Current | Oct 2025 | Complete |
| IMPLEMENTATION-GUIDE.md | ✅ Current | Oct 2025 | Complete |
| name-implementation-comparison.md | ✅ Current | Oct 2025 | Complete |
| alignment/* | ✅ Current | Oct 2025 | Complete |
| examples/* | ✅ Current | Oct 2025 | Complete |

---

## 📝 Contributing to Documentation

When updating documentation:

1. **Update this index** if you add/remove/rename docs
2. **Update "Last Updated" dates** in modified documents
3. **Keep cross-references current** - check links when moving content
4. **Follow existing structure** - consistent formatting across docs
5. **Update README.md** if core concepts change

---

## 🗂️ Archive

Old performance documentation has been consolidated into [PERFORMANCE.md](PERFORMANCE.md).
Archived versions available in `docs/archive/` for historical reference:

- `performance-analysis.md` (original, before integration)
- `registry-implementation-comparison.md` (registry benchmarks, before integration)
- `registry-benchmark-comparison-after-integration.md` (registry, after integration)
- `performance-comparison-after-lazy-trie-integration.md` (full framework, after integration)

These documents are superseded by the consolidated [PERFORMANCE.md](PERFORMANCE.md).

---

## 🚀 Quick Links

- **[Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)** - Official API repository
- **[Observability X Blog](https://humainary.io/blog/category/observability-x/)** - William Louth's blog series
- **[Humainary Website](https://humainary.io/)** - Semiotic observability resources

---

**Questions or suggestions?** Open an issue or submit a PR!
