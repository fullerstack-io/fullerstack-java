package io.fullerstack.substrates.closure;

import io.humainary.substrates.api.Substrates.*;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implementation of Substrates.Closure for automatic resource management (ARM).
 *
 * <p>Closure provides a try-with-resources pattern wrapper that ensures
 * the resource is properly closed after the consumer completes (or throws).
 *
 * <p>Example usage:
 * <pre>
 * Closure&lt;Sink&lt;String&gt;&gt; closure = scope.closure(sink);
 * closure.consume(s -> {
 *     // Use sink here
 *     s.drain().forEach(System.out::println);
 * });
 * // sink.close() is called automatically
 * </pre>
 *
 * @param <R> the resource type
 * @see Closure
 * @see Resource
 * @see Scope
 */
public class ClosureImpl<R extends Resource> implements Closure<R> {

    private final R resource;

    /**
     * Creates a Closure that manages the given Resource.
     *
     * @param resource the resource to manage
     * @throws NullPointerException if resource is null
     */
    public ClosureImpl(R resource) {
        this.resource = Objects.requireNonNull(resource, "Resource cannot be null");
    }

    @Override
    public void consume(Consumer<? super R> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");

        // ARM pattern: use resource and ensure close() is called
        try {
            consumer.accept(resource);
        } finally {
            try {
                resource.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close resource", e);
            }
        }
    }
}
