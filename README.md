# Fullerstack Java

A collection of Java libraries and implementations by Fullerstack.

## Overview

This repository serves as a monorepo for various Java-based projects and libraries developed by Fullerstack. Each module is independently maintained with its own documentation, tests, and dependencies.

## Current Projects

### Fullerstack Kafka

Kafka client libraries and observability extensions using Substrates/Serventis APIs.

**Modules:**
- `fullerstack-kafka-core` - Core Kafka client abstractions
- `fullerstack-kafka-broker` - Broker monitoring and management
- `fullerstack-kafka-producer` - Producer implementations
- `fullerstack-kafka-consumer` - Consumer implementations
- `fullerstack-kafka-runtime` - Runtime and observability
- `fullerstack-kafka-msk` - AWS MSK integration

**Dependencies:**
- [Fullerstack Substrates](https://github.com/fullerstack-io/fullerstack-humainary/tree/main/fullerstack-substrates) - Event-driven infrastructure (separate repo)
- Apache Kafka 3.8.0

**Status:** In Development

---

## Related Projects

### [Fullerstack Substrates](https://github.com/fullerstack-io/fullerstack-humainary)

**Moved to separate repository:** https://github.com/fullerstack-io/fullerstack-humainary

Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

**Features:**
- 100% TCK compliance (381/381 tests passing)
- Sequential processing with Virtual CPU Core pattern
- Dual-queue Valve architecture
- Dynamic routing with on-demand channels
- SPI-based provider loading

**Status:** ✅ Production-ready | Apache 2.0

---

## Requirements

- **Java 25** or higher (LTS)
- Maven 3.9+

## Building All Projects

### Prerequisites

**Note:** This project uses Humainary API version **1.0.0-PREVIEW**. The Humainary APIs are not yet published to Maven Central.

**Dependency Status:**
- **This project uses**: v1.0.0-PREVIEW - Latest stable API
- **Installation required**: PREVIEW artifacts must be built from source

**Building Humainary PREVIEW Dependencies:**

Before building this project, you must install the Humainary PREVIEW dependencies to your local Maven repository:

```bash
# Clone and build Substrates API (includes Serventis extensions)
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
mvn clean install -DskipTests
cd ..

# Clone and build Fullerstack Substrates implementation
git clone https://github.com/fullerstack-io/fullerstack-humainary.git
cd fullerstack-humainary/fullerstack-substrates
mvn clean install -DskipTests
cd ../..
```

**PREVIEW Key Features:**
PREVIEW introduces major improvements:
- **Static Cortex access** - `cortex().circuit()`, `cortex().name()`, etc.
- **Cell extends Pipe** - Cells can emit directly (no .pipe() call needed)
- **Sealed interfaces** - Type safety with restricted implementations
- **12 Serventis instruments** - Full OODA loop coverage (Observe, Orient, Decide, Act)

### Building Fullerstack Kafka Projects

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

## Project Structure

```
fullerstack-java/
├── fullerstack-kafka-core/         # Core Kafka abstractions
├── fullerstack-kafka-broker/       # Broker monitoring
├── fullerstack-kafka-producer/     # Producer implementations
├── fullerstack-kafka-consumer/     # Consumer implementations
├── fullerstack-kafka-runtime/      # Runtime and observability
├── fullerstack-kafka-msk/          # AWS MSK integration
└── pom.xml                         # Parent POM (manages versions)
```

## Architecture Highlights

### Kafka Observability with Substrates

This project uses Fullerstack Substrates (separate repo) to provide event-driven observability for Apache Kafka:

- **Semiotic Intelligence**: Transforms raw Kafka metrics into meaningful signs and symptoms
- **OODA Loop**: Implements Boyd's Observe-Orient-Decide-Act pattern
- **12 Serventis Instruments**: Covers full observability spectrum (Probes, Monitors, Services, etc.)
- **Causal Analysis**: Vector clocks track relationships across distributed sensors

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

Apache License 2.0 - All Kafka modules

## Links

- Organization: https://fullerstack.io/
- GitHub: https://github.com/fullerstack-io
- Issues: https://github.com/fullerstack-io/fullerstack-java/issues

## Authors

**Fullerstack** - https://fullerstack.io/

---

## Acknowledgments

This project builds on the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) and [Serventis Extensions](https://github.com/humainary-io/substrates-api-java/tree/main/ext/serventis) designed by [William Louth](https://humainary.io/).

- **API Design:** [William Louth](https://humainary.io/) ([Humainary](https://github.com/humainary-io))
- **Substrates Implementation:** [Fullerstack Humainary](https://github.com/fullerstack-io/fullerstack-humainary)
- **Kafka Extensions:** Fullerstack

**Learn more:**
- Humainary: https://humainary.io/
- Substrates API: https://github.com/humainary-io/substrates-api-java
- Fullerstack Substrates: https://github.com/fullerstack-io/fullerstack-humainary
- Observability X Blog: https://humainary.io/blog/category/observability-x/
