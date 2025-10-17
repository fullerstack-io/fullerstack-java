# Fullerstack Java

A collection of Java libraries and implementations by Fullerstack.

## Overview

This repository serves as a monorepo for various Java-based projects and libraries developed by Fullerstack. Each module is independently maintained with its own documentation, tests, and dependencies.

## Current Projects

### [Fullerstack Substrates](fullerstack-substrates/)

Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

**Features:**
- Circuit-based event orchestration
- Queue processing with virtual threads
- Observable streams (Source/Subscriber pattern)
- Transformation pipelines (Sequencer/Segment)
- Hierarchical resource management
- Immutable state management

**Status:** ✅ Production-ready | 264 tests passing | Apache 2.0

**Documentation:** [View README](fullerstack-substrates/README.md)

---

## Requirements

- **Java 25** or higher (LTS)
- Maven 3.9+

## Building All Projects

### Prerequisites

**Note:** This project uses Humainary API version 1.0.0-M13. The Humainary APIs are not published to Maven Central, and M13 is not tagged as a release in their repositories.

**Dependency Status:**
- **Latest Humainary** (`main` branch): v1.0.0-M15 - Requires Java 25 and has breaking API changes (missing Queue, Script, Sequencer, Segment classes)
- **This project uses**: v1.0.0-M13 - Compatible with Java 25, stable API
- **Installation required**: M13 artifacts must be built from specific commits

**Building Humainary M13 Dependencies:**

Before building this project, you must install the Humainary M13 dependencies to your local Maven repository:

```bash
# Clone and build Substrates API M13
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
git checkout 8c9780b  # M13 commit
mvn clean install
cd ..

# Clone and build Serventis API M13
git clone https://github.com/humainary-io/serventis-api-java.git
cd serventis-api-java
git checkout afc1e0d  # M13 commit
mvn clean install
cd ..
```

**Migration to M15:**
Updating to Humainary M15 requires significant code migration work due to API changes. The M13 version is stable and works with Java 25.

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
├── [future-project-n]/             # Future Java projects
└── pom.xml                         # Parent POM
```

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

### Fullerstack Substrates

Based on the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) designed by [William Louth](https://humainary.io/).

- **API Design:** [William Louth](https://humainary.io/) ([Humainary](https://github.com/humainary-io))
- **Implementation:** Fullerstack
- **License:** Apache 2.0

**Learn more:**
- Humainary: https://humainary.io/
- Substrates API (Java): https://github.com/humainary-io/substrates-api-java
- Observability X Blog: https://humainary.io/blog/category/observability-x/
- William Louth's Articles: https://humainary.io/blog/
