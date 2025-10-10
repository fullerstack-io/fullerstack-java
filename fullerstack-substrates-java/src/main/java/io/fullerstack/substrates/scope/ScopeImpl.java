package io.fullerstack.substrates.scope;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.state.StateImpl;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of Substrates.Scope for hierarchical context management.
 *
 * <p>Scopes support hierarchical resource management and can be nested.
 *
 * @see Scope
 */
public class ScopeImpl implements Scope {
    private final Name name;
    private final Scope parent;
    private final Map<Name, Scope> childScopes = new ConcurrentHashMap<>();
    private final Map<Object, Resource> resources = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Creates a root scope.
     *
     * @param name scope name
     */
    public ScopeImpl(Name name) {
        this(name, null);
    }

    /**
     * Creates a child scope.
     *
     * @param name scope name
     * @param parent parent scope
     */
    public ScopeImpl(Name name, Scope parent) {
        this.name = Objects.requireNonNull(name, "Scope name cannot be null");
        this.parent = parent;
    }

    @Override
    public Subject subject() {
        return new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SCOPE
        );
    }

    @Override
    public Scope scope() {
        checkClosed();
        return new ScopeImpl(name, this);
    }

    @Override
    public Scope scope(Name name) {
        checkClosed();
        return childScopes.computeIfAbsent(name, n -> new ScopeImpl(n, this));
    }

    @Override
    public <R extends Resource> R register(R resource) {
        checkClosed();
        resources.put(resource, resource);
        return resource;
    }

    @Override
    public <R extends Resource> Closure<R> closure(R resource) {
        checkClosed();
        register(resource);
        return new Closure<R>() {
            @Override
            public void consume(java.util.function.Consumer<? super R> consumer) {
                consumer.accept(resource);
                resources.remove(resource);
                try {
                    resource.close();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to close resource", e);
                }
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Close all child scopes
        for (Scope scope : childScopes.values()) {
            try {
                scope.close();
            } catch (Exception e) {
                // Log but continue closing others
            }
        }

        // Close all resources
        resources.values().forEach(resource -> {
            try {
                resource.close();
            } catch (Exception e) {
                // Log but continue closing others
            }
        });

        resources.clear();
        childScopes.clear();
    }

    @Override
    public CharSequence part() {
        return name.part();
    }

    @Override
    public Optional<Scope> enclosure() {
        return Optional.ofNullable(parent);
    }

    /**
     * Gets the parent scope.
     *
     * @return parent scope or null if root
     */
    public Scope parent() {
        return parent;
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Scope is closed");
        }
    }

    @Override
    public String toString() {
        return "Scope[name=" + name + ", closed=" + closed + "]";
    }
}
