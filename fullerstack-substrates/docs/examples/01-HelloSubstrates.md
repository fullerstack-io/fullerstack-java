# Example 1: Hello Substrates

The simplest possible Substrates example - one producer, one consumer.

## Code

```java
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;

public class HelloSubstrates {
    public static void main(String[] args) throws InterruptedException {
        // 1. Create Cortex runtime
        Cortex cortex = CortexRuntime.create();

        // 2. Create Circuit (central processing engine)
        Circuit circuit = cortex.circuit(cortex.name("hello-circuit"));

        // 3. Create Conduit with Pipe composer
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("messages"),
            Composer.pipe()
        );

        // 4. CONSUMER: Subscribe to observe emissions
        conduit.source().subscribe(
            cortex.subscriber(
                cortex.name("console-logger"),
                (subject, registrar) -> {
                    // Register a consumer Pipe
                    registrar.register(message -> {
                        System.out.println("Received: " + message);
                    });
                }
            )
        );

        // 5. PRODUCER: Get a pipe and emit
        Pipe<String> pipe = conduit.get(cortex.name("producer1"));
        pipe.emit("Hello, Substrates!");
        pipe.emit("This is message 2");
        pipe.emit("And message 3");

        // 6. Wait a moment for async processing
        Thread.sleep(100);

        // 7. Clean up
        circuit.close();

        System.out.println("Done!");
    }
}
```

## Expected Output

```
Received: Hello, Substrates!
Received: This is message 2
Received: And message 3
Done!
```

## What's Happening

1. **Cortex** - Runtime entry point
2. **Circuit** - Central processing engine that manages all components
3. **Conduit** - Routes messages from producers (Channels) to consumers (Subscribers)
4. **Subscriber** - Registers a consumer Pipe to receive messages
5. **Producer** - Gets a Pipe from conduit and emits messages
6. **Async Processing** - Conduit processes emissions on background thread
7. **Cleanup** - Circuit closes all resources

## Data Flow

```
pipe.emit("Hello")
  ↓
Conduit's queue
  ↓
Queue processor (async)
  ↓
Source.emit()
  ↓
Subscriber notified
  ↓
Consumer Pipe receives message
  ↓
"Received: Hello"
```

## Key Points

- **Asynchronous** - Emissions are queued and processed on background thread
- **Thread-safe** - Multiple producers can emit concurrently
- **Observable** - Multiple subscribers can observe same emissions
- **Clean separation** - Producer doesn't know about consumers
