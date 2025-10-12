# Fullerstack Java

A collection of Java libraries and implementations by Fullerstack.

## Overview

This repository serves as a monorepo for various Java-based projects and libraries developed by Fullerstack. Each module is independently maintained with its own documentation, tests, and dependencies.

## Current Projects

### [Fullerstack Substrates](fullerstack-substrates-java/)

Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

**Features:**
- Circuit-based event orchestration
- Queue processing with virtual threads
- Observable streams (Source/Subscriber pattern)
- Transformation pipelines (Sequencer/Segment)
- Hierarchical resource management
- Immutable state management

**Status:** ✅ Production-ready | 264 tests passing | Apache 2.0

**Documentation:** [View README](fullerstack-substrates-java/README.md)

---

## Requirements

- **Java 24** or higher
- Maven 3.9+

## Building All Projects

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
cd fullerstack-substrates-java
mvn clean install
```

## Project Structure

```
fullerstack-java/
├── fullerstack-substrates-java/    # Substrates API implementation
├── [future-project-1]/             # Future Java projects
├── [future-project-2]/             # Future Java projects
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
- **fullerstack-substrates-java:** Apache License 2.0

## Links

- Organization: https://fullerstack.io/
- GitHub: https://github.com/fullerstack-io
- Issues: https://github.com/fullerstack-io/fullerstack-java/issues

## Authors

**Fullerstack** - https://fullerstack.io/

---

## Project Acknowledgments

### Fullerstack Substrates

Based on the Humainary Substrates API designed by William Louth.

- API Design: William Louth (Humainary)
- Implementation: Fullerstack
- License: Apache 2.0

Learn more: https://humainary.io/ | https://substrates.io/
