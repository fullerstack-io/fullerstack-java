# Documentation Index

**Last Updated:** October 22, 2025
**Version:** 1.0.0-M17
**API Status:** Production-ready (247 tests passing)

This index provides a complete map of Substrates documentation, helping you find the right information quickly.

---

## 📚 Quick Navigation

### For New Users
1. **[README](../README.md)** - Start here! Quick start and basic examples
2. **[Core Concepts](CONCEPTS.md)** - Understanding Substrates entities and relationships
3. **[Examples](examples/README.md)** - Hands-on examples from simple to complex

### For Developers
1. **[Architecture Guide](ARCHITECTURE.md)** - System design, M17 sealed hierarchy, virtual CPU core
2. **[Async-First Architecture](ASYNC-ARCHITECTURE.md)** ⚠️ **CRITICAL** - Understanding async queue processing
3. **[Best Practices](BEST-PRACTICES.md)** - Recommended patterns and common pitfalls
4. **[Advanced Topics](ADVANCED.md)** - Performance tuning, custom implementations, extensions

### For Performance Engineers
1. **[Performance Guide](PERFORMANCE.md)** - Performance characteristics and optimization

### For API Reference
1. **[M17 Migration Guide](../../API-ANALYSIS.md)** - Sealed interfaces and migration from M16

---

## 📖 Document Descriptions

### Core Documentation

#### [README.md](../README.md)
**Purpose:** Project overview, quick start, and basic usage examples
**Audience:** Everyone
**What's Inside:**
- Maven dependency setup
- Basic usage examples (Circuit, Conduit, Pipe, subscribe)
- Quick start guide
- M17 API features
- Performance overview (247 tests in ~16s)

#### [CONCEPTS.md](CONCEPTS.md)
**Purpose:** Deep dive into Substrates entities and their relationships
**Audience:** Developers learning the framework
**What's Inside:**
- What is Substrates? (Semiotic observability)
- Core entities (Cortex, Circuit, Conduit, Channel, Pipe, Cell, Clock, Scope)
- M17 sealed hierarchy explained
- Naming and hierarchy (NameNode)
- Event flow (producer → consumer path)
- Transformations (Flow/Sift)
- Subscription pattern (Subscriber, Registrar)
- Resource lifecycle (cleanup patterns)
- State management (State/Slot)
- Timing and scheduling (Clock)

**Key Concepts:**
- **M17 Sealed Interfaces** - Type safety via sealed hierarchy
- **Virtual CPU Core Pattern** - Precise event ordering per Circuit
- **Hierarchical Naming** - Dot-notation organization (kafka.broker.1.metrics)
- **Immutable State** - Thread-safe state via Slot API
- **Resource Cleanup** - Explicit lifecycle via close()

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
- Architecture principles (simplified, lean design)
- M17 sealed interface hierarchy (what you can/can't implement)
- Core components:
  - CortexRuntime - Entry point
  - NameNode - Hierarchical naming
  - CircuitImpl - Virtual CPU core pattern
  - ConduitImpl - Channel/Pipe/Source coordinator
  - ChannelImpl - Emission port
  - PipeImpl - Event transformation
  - SourceImpl - Subscriber management (internal)
  - CellNode - Hierarchical state transformation
  - ClockImpl - Scheduled events
  - ScopeImpl - Resource lifecycle
- Data flow architecture
- Caching strategy (ConcurrentHashMap everywhere)
- Thread safety patterns
- Resource lifecycle
- State management (immutable slots)
- Performance characteristics
- Design patterns
- Integration with Serventis
- Testing strategy

**Key Patterns:**
- Virtual CPU core - Single thread, FIFO queue, precise ordering
- Simplified caching - Just ConcurrentHashMap, no complex optimizations
- M17 sealed hierarchy - SourceImpl doesn't implement Source (sealed)
- Shared scheduler - One ScheduledExecutorService per Circuit

#### [BEST-PRACTICES.md](BEST-PRACTICES.md)
**Purpose:** Recommended patterns and best practices
**Audience:** Developers building with Substrates
**What's Inside:**
- General principles (cache pipes, hierarchical names, close resources)
- Naming best practices (dot notation, consistent conventions)
- Circuit management (one per domain, lifecycle)
- Conduit and Channel patterns (by signal type, subject-based)
- Flow and Sift transformations (configure once, common patterns)
- Subscriber management (subscribe once, unsubscribe)
- Resource lifecycle (Scope, try-with-resources, cleanup order)
- Cell hierarchies (transformation pipelines, multiple children)
- Clock and timing (periodic tasks, multiple cycles)
- Testing strategies (unit, transformations, clock, cleanup)
- Performance tips (reuse, batch, avoid blocking)
- Common pitfalls (resource leaks, mixing signal types, async nature)

**Recommended Patterns:**
```java
// ✅ Cache pipes for repeated emissions
Pipe<T> pipe = conduit.get(name);
for (T value : values) {
    pipe.emit(value);
}

// ✅ Use hierarchical names
Name broker = cortex.name("kafka").name("broker").name("1");

// ✅ Close resources explicitly
try (Circuit circuit = cortex.circuit(name)) {
    // Use circuit
}
```

#### [ADVANCED.md](ADVANCED.md)
**Purpose:** Advanced topics, performance tuning, and extensions
**Audience:** Expert users and performance engineers
**What's Inside:**
- Advanced threading and concurrency patterns
- Custom implementations
- Resource lifecycle management
- Testing strategies for complex scenarios

**Note:** This document may reference older patterns. Refer to BEST-PRACTICES.md for current recommendations.

---

### Performance Documentation

#### [PERFORMANCE.md](PERFORMANCE.md)
**Purpose:** Performance characteristics, benchmarks, and optimization guide
**Audience:** Performance engineers, architects, and developers
**What's Inside:**
- Performance summary (test suite: 247 tests in ~16s)
- Production readiness (100k+ metrics @ 1Hz)
- Architecture performance characteristics:
  - Virtual CPU core pattern
  - Component caching (ConcurrentHashMap)
  - Pipe emission (~100-300ns per emission)
  - Shared scheduler optimization
  - NameNode hierarchy (cached paths)
  - Subscriber management (CopyOnWriteArrayList)
- Real-world performance (Kafka monitoring scenario)
- Benchmark scenarios (high-frequency, many subjects, many subscribers)
- Performance best practices (cache pipes, batch, transformations, avoid blocking)
- Scaling considerations (vertical, horizontal)
- Memory characteristics (~1KB per metric)
- Performance monitoring (queue depth, emission rate)
- Profiling tips (JFR, async-profiler)
- When to optimize (philosophy: simple first, optimize if needed)
- Comparison to alternatives (EventBus, Reactive Streams)

**Philosophy:**
> "Premature optimization is the root of all evil." - Donald Knuth

Build it simple, build it correct, measure in production, optimize actual bottlenecks.

**Note:** Some benchmark numbers are estimates pending actual load tests.

---

### Migration Documentation

#### [API-ANALYSIS.md](../../API-ANALYSIS.md)
**Purpose:** M17 API analysis and migration guide
**Audience:** Developers migrating from M16 or understanding M17
**What's Inside:**
- M17 major changes (sealed interfaces)
- Impact on Fullerstack implementation
- Architecture implications (type safety benefits)
- Migration guide (step-by-step from M16)
- API surface (sealed vs non-sealed types)
- Fullerstack implementation summary
- Testing status (247 substrates + 12 serventis tests passing)
- Known issues and workarounds
- Recommendations

**Key Changes:**
- Source/Context/Component/Container now sealed
- SourceImpl no longer implements Source (internal utility)
- All Source<E> field types changed to SourceImpl<E>

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
2. **[02-Transformations.md](examples/02-Transformations.md)** - Using Flow/Sift
3. **[03-MultipleSubscribers.md](examples/03-MultipleSubscribers.md)** - Multiple consumer patterns
4. **[04-ResourceManagement.md](examples/04-ResourceManagement.md)** - Scope and Closure usage

**Note:** Examples may need updates for M17 API. Check main README for current patterns.

---

## 🔍 Find by Topic

### M17 Sealed Interfaces
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - M17 Sealed Hierarchy section
- **Migration:** [API-ANALYSIS.md](../../API-ANALYSIS.md) - Complete migration guide
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - Sealed hierarchy impact

### Virtual CPU Core Pattern
- **Primary:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - **MUST READ**
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - CircuitImpl section
- **Concepts:** [CONCEPTS.md](CONCEPTS.md) - Event Flow section

### Performance
- **Primary:** [PERFORMANCE.md](PERFORMANCE.md) - Complete guide
- **Best Practices:** [BEST-PRACTICES.md](BEST-PRACTICES.md) - Performance tips section
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - Performance characteristics

### Entity Relationships
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Core Entities section
- **Data flow:** [ARCHITECTURE.md](ARCHITECTURE.md) - Data Flow Architecture
- **Examples:** [examples/01-HelloSubstrates.md](examples/01-HelloSubstrates.md)

### Hierarchical Names (NameNode)
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Naming and Hierarchy section
- **Best Practices:** [BEST-PRACTICES.md](BEST-PRACTICES.md) - Naming section
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - NameNode section

### Resource Management
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Resource Lifecycle section
- **Best Practices:** [BEST-PRACTICES.md](BEST-PRACTICES.md) - Resource Lifecycle section
- **Examples:** [examples/04-ResourceManagement.md](examples/04-ResourceManagement.md)

### Async Architecture & Queue Processing ⚠️
- **PRIMARY:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - **MUST READ**
- **Quick Reference:** [CONCEPTS.md](CONCEPTS.md) - Event Flow section
- **Implementation:** [ARCHITECTURE.md](ARCHITECTURE.md) - Virtual CPU Core Pattern

### Threading & Concurrency
- **Primary:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - Virtual CPU Core pattern
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md) - Thread Safety section
- **Performance:** [PERFORMANCE.md](PERFORMANCE.md) - Scaling considerations

### Testing
- **PRIMARY:** [ASYNC-ARCHITECTURE.md](ASYNC-ARCHITECTURE.md) - Testing async patterns ⚠️
- **Best Practices:** [BEST-PRACTICES.md](BEST-PRACTICES.md) - Testing Strategies section
- **Advanced:** [ADVANCED.md](ADVANCED.md) - Testing strategies

### Transformations (Flow/Sift)
- **Primary:** [CONCEPTS.md](CONCEPTS.md) - Transformations section
- **Best Practices:** [BEST-PRACTICES.md](BEST-PRACTICES.md) - Flow and Sift section
- **Examples:** [examples/02-Transformations.md](examples/02-Transformations.md)

---

## 📊 Documentation Status

| Document | Status | Last Updated | Completeness |
|----------|--------|--------------|--------------|
| README.md | ✅ Current | Oct 2025 | Complete (M17) |
| CONCEPTS.md | ✅ Current | Oct 22, 2025 | Complete (Rewritten) |
| ASYNC-ARCHITECTURE.md | ✅ Current | Oct 2025 | Complete ⚠️ **CRITICAL** |
| ARCHITECTURE.md | ✅ Current | Oct 22, 2025 | Complete (Rewritten) |
| BEST-PRACTICES.md | ✅ Current | Oct 22, 2025 | Complete (New) |
| PERFORMANCE.md | ✅ Current | Oct 22, 2025 | Complete (Rewritten) |
| ADVANCED.md | ⚠️ May be outdated | Oct 2025 | Review recommended |
| API-ANALYSIS.md | ✅ Current | Oct 2025 | Complete (M17) |
| examples/* | ⚠️ May need M17 updates | Oct 2025 | Review recommended |

---

## 📝 Recent Changes

**October 22, 2025 - Major Documentation Refresh:**
- ✅ Rewrote ARCHITECTURE.md for M17 and simplified implementation
- ✅ Created BEST-PRACTICES.md (replaces IMPLEMENTATION-GUIDE.md)
- ✅ Rewrote PERFORMANCE.md focusing on current implementation
- ✅ Rewrote CONCEPTS.md removing factory patterns and identity map
- ✅ Removed outdated archived documents (pre-M17 refactoring)
- ✅ Updated all references to reflect:
  - M17 sealed interfaces
  - NameNode/CellNode (not NameTree/CellTree)
  - Flow/Sift (not Sequencer/Segment)
  - SourceImpl as internal utility (not implementing Source)
  - Simplified caching (ConcurrentHashMap, no LazyTrieRegistry)
  - No factory patterns (NameFactory, QueueFactory, RegistryFactory removed)

---

## 📚 Obsolete Concepts (Removed in Refactoring)

The following were removed from the implementation:
- ❌ NameFactory, QueueFactory, RegistryFactory (removed)
- ❌ InternedName, LazyTrieRegistry (removed)
- ❌ Identity map optimization (removed)
- ❌ Sequencer/Segment (replaced by Flow/Sift in API)
- ❌ NameTree/CellTree (renamed to NameNode/CellNode)
- ❌ Multiple Name/Registry implementations (simplified to one each)

**If you see references to these in older docs, they are outdated.**

---

## 🚀 Quick Links

- **[Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)** - Official API repository (M17)
- **[Observability X Blog](https://humainary.io/blog/category/observability-x/)** - William Louth's blog series
- **[Humainary Website](https://humainary.io/)** - Semiotic observability resources

---

**Questions or suggestions?** Open an issue or submit a PR!
