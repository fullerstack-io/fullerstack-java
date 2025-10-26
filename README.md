# Fullerstack Java

A collection of Java libraries and implementations by Fullerstack.

## Overview

This repository serves as a monorepo for various Java-based projects and libraries developed by Fullerstack. Each module is independently maintained with its own documentation, tests, and dependencies.

## Current Projects

### [Fullerstack Substrates](fullerstack-substrates/)

Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

**Features:**
- Circuit-based event orchestration with virtual CPU core pattern
- Hierarchical naming with dot notation (NameNode)
- Observable event streams (subscriber management)
- Transformation pipelines (Flow/Sift)
- Hierarchical state management (CellNode)
- Immutable state with slots
- Shared scheduler optimization for Clocks

**Status:** ✅ 540 tests passing | Apache 2.0

**Documentation:** [View README](fullerstack-substrates/README.md)

### [Fullerstack Serventis](fullerstack-serventis/)

Concrete signal implementations for the [Humainary Serventis API](https://github.com/humainary-io/serventis-api-java) - semiotic intelligence for observability.

**Features:**
- Immutable signal records (Monitor, Service, Queue, Reporter, Probe, Resource)
- Vector clock state management
- Zero-allocation signal types
- Full Serventis API M18 support

**Status:** ✅ 36 tests passing | Apache 2.0

**Documentation:** [View README](fullerstack-serventis/README.md)

---

## Requirements

- **Java 25** or higher (LTS)
- Maven 3.9+

## Building All Projects

### Prerequisites

**Note:** This project uses Humainary API version **1.0.0-M18**. The Humainary APIs are not yet published to Maven Central.

**Dependency Status:**
- **This project uses**: v1.0.0-M18 - Latest with static Cortex access (Java 25 required)
- **Installation required**: M18 artifacts must be built from source

**Building Humainary M18 Dependencies:**

Before building this project, you must install the Humainary M18 dependencies to your local Maven repository:

```bash
# Clone and build Substrates API M18
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
# Main branch is currently at M18
mvn clean install -DskipTests
cd ..

# Clone and build Serventis API M18
git clone https://github.com/humainary-io/serventis-api-java.git
cd serventis-api-java
# Main branch is currently at M18
mvn clean install -DskipTests
cd ..
```

**M18 Key Features:**
M18 introduces major improvements:
- **Static Cortex access** - `Cortex.circuit()`, `Cortex.name()`, etc. (no more instance creation)
- **Cell hierarchy** - Cells can contain child cells via `Container.get(name)`
- **Conduit creation** - Three variants for creating conduits
- **Flow.skip()** - New skip transformation
- Sealed interfaces for type safety (`Source`, `Context`, `Component`, `Container`)
- See [API-ANALYSIS.md](API-ANALYSIS.md) for migration details

### Building Fullerstack Projects

```bash
# Clone the repository
git clone https://github.com/fullerstack-io/fullerstack-java.git
cd fullerstack-java

# Build all modules
mvn clean install

# Run all tests
mvn test

# Skip tests
mvn clean install -DskipTests
```

## Building Individual Projects

Each project can be built independently:

```bash
cd fullerstack-substrates
mvn clean install
```

## Project Structure

```
fullerstack-java/
├── fullerstack-substrates/         # Substrates API implementation
├── fullerstack-serventis/          # Serventis signals implementation
├── API-ANALYSIS.md                 # API version analysis and migration notes
├── CELL-IMPLEMENTATION-PLAN.md     # Cell architecture documentation
└── pom.xml                         # Parent POM (manages M18 versions)
```

## Architecture Highlights

### Simplified Design (Post-Refactoring)

The implementation has been significantly simplified:
- **Single Name implementation**: NameNode (removed 4 alternative implementations)
- **No registry abstractions**: Removed 8 registry implementations (API provides Registry)
- **Lean dependencies**: Removed benchmark and queue packages
- **Optimized Clocks**: Shared ScheduledExecutorService across all Clocks in a Circuit
- **Direct API usage**: Removed unnecessary abstraction layers

### Key Implementation Classes

| Package | Class | Purpose |
|---------|-------|---------|
| circuit | CircuitImpl | Event orchestration with virtual CPU core pattern |
| conduit | ConduitImpl | Channel/Source coordination |
| cell | CellNode | Hierarchical state transformation |
| clock | ClockImpl | Scheduled event emission |
| name | NameNode | Hierarchical dot-notation names |
| source | SourceImpl | Internal subscriber management |
| channel | ChannelImpl | Emission ports |
| pipe | PipeImpl | Event transformation |
| sink | SinkImpl | Event capture |

## Adding New Projects

New Java projects can be added to this repository by:

1. Creating a new module directory
2. Adding the module to the parent `pom.xml`
3. Following the existing project structure conventions
4. Including comprehensive tests and documentation

## Contributing

Contributions are welcome! Please:

- Ensure all tests pass before submitting
- Follow existing code style and conventions
- Add tests for new functionality
- Update documentation as needed
- Maintain project-specific README files

## License

Each project in this repository may have its own license. Please refer to individual project directories for specific license information.

Currently:
- **fullerstack-substrates:** Apache License 2.0
- **fullerstack-serventis:** Apache License 2.0

## Links

- Organization: https://fullerstack.io/
- GitHub: https://github.com/fullerstack-io
- Issues: https://github.com/fullerstack-io/fullerstack-java/issues

## Authors

**Fullerstack** - https://fullerstack.io/

---

## Project Acknowledgments

### Fullerstack Substrates & Serventis

Based on the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) and [Serventis API](https://github.com/humainary-io/serventis-api-java) designed by [William Louth](https://humainary.io/).

- **API Design:** [William Louth](https://humainary.io/) ([Humainary](https://github.com/humainary-io))
- **Implementation:** Fullerstack
- **License:** Apache 2.0

**Learn more:**
- Humainary: https://humainary.io/
- Substrates API (Java): https://github.com/humainary-io/substrates-api-java
- Serventis API (Java): https://github.com/humainary-io/serventis-api-java
- Observability X Blog: https://humainary.io/blog/category/observability-x/
- William Louth's Articles: https://humainary.io/blog/
