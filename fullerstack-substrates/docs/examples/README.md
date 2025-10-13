# Substrates Examples

Practical examples demonstrating common Substrates usage patterns.

## Quick Start Examples

1. **[Hello Substrates](01-HelloSubstrates.md)** - Simplest producer-consumer example
2. **[Transformations](02-Transformations.md)** - Using Sequencer for filtering and sampling
3. **[Multiple Subscribers](03-MultipleSubscribers.md)** - Fan-out pattern with multiple consumers
4. **[Resource Management](04-ResourceManagement.md)** - Lifecycle management with Scope and Closure

## Running Examples

All examples are standalone Java programs. To run:

```bash
# Compile
javac -cp "target/classes:$HOME/.m2/repository/..." Example.java

# Run
java -cp ".:target/classes:..." Example
```

Or use your IDE to run the main methods directly.

## Example Index

### Basic Patterns

- **Producer-Consumer** - [Example 1](01-HelloSubstrates.md)
- **Fan-out (1→N)** - [Example 3](03-MultipleSubscribers.md)
- **Transformations** - [Example 2](02-Transformations.md)

### Advanced Patterns

See the [Architecture Guide](../ARCHITECTURE.md#advanced-patterns) for:
- Fan-in (N→1)
- Relay pattern
- Transform pattern
- Conditional subscription

### Resource Management

- **Manual lifecycle** - [Example 4](04-ResourceManagement.md)
- **Scope-based** - [Example 4](04-ResourceManagement.md)
- **Closure (ARM)** - [Example 4](04-ResourceManagement.md)
- **Try-with-resources** - [Example 4](04-ResourceManagement.md)

## Common Use Cases

### Logging and Monitoring

```java
Conduit<Pipe<LogEvent>, LogEvent> logs = circuit.conduit(
    cortex.name("logs"),
    Composer.pipe()
);

// Console logger
logs.source().subscribe(consoleSubscriber);

// File logger
logs.source().subscribe(fileSubscriber);

// Metrics collector
logs.source().subscribe(metricsSubscriber);
```

### Event Processing Pipeline

```java
Conduit<Pipe<Event>, Event> events = circuit.conduit(
    cortex.name("events"),
    Composer.pipe(segment -> segment
        .guard(e -> e.isValid())        // Filter invalid
        .replace(e -> e.normalize())     // Normalize
        .limit(10000)                    // Rate limit
    )
);
```

### Time-based Collection

```java
Clock clock = circuit.clock(cortex.name("metrics"));

clock.consume(
    cortex.name("collector"),
    Clock.Cycle.SECOND,
    instant -> collectMetrics(instant)
);
```

### Hierarchical Resource Cleanup

```java
try (Scope scope = cortex.scope(cortex.name("request"))) {
    Circuit circuit = scope.register(cortex.circuit());
    // ... use circuit
} // Auto-closes all
```

## Tips

1. **Always close** Circuit, Clock, Subscription, Sink
2. **Use Scope** for managing multiple related resources
3. **Subscribe early** before emitting to avoid missed events
4. **Sleep briefly** after emitting in examples to allow async processing
5. **Check Subject.name()** in subscribers for conditional logic

## Next Steps

- Read the [Concepts Guide](../CONCEPTS.md) for deeper understanding
- Review the [Architecture Guide](../ARCHITECTURE.md) for design details
- Check the [Substrates API JavaDoc](https://github.com/humainary-io/substrates-api-java)
