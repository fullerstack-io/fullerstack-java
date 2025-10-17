package io.fullerstack.substrates.scope;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.closure.ClosureImpl;
import io.fullerstack.substrates.registry.LazyTrieRegistry;
import io.fullerstack.substrates.registry.RegistryFactory;
import io.fullerstack.substrates.registry.LazyTrieRegistryFactory;

import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Implementation of Substrates.Scope for hierarchical context management.
 *
 * <p>Scopes support hierarchical resource management and can be nested.
 * Resources are closed in LIFO (Last In, First Out) order, matching Java's
 * try-with-resources semantics.
 *
 * @see Scope
 */
public class ScopeImpl implements Scope {
    private final Name name;
    private final Scope parent;
    private final RegistryFactory registryFactory;
    private final Map<Name, Scope> childScopes;
    private final Deque<Resource> resources = new ConcurrentLinkedDeque<>();
    private volatile boolean closed = false;

    // Cache Subject - each Scope has a persistent identity
    private final Subject scopeSubject;

    /**
     * Creates a root scope with default {@link LazyTrieRegistryFactory}.
     *
     * @param name scope name
     */
    public ScopeImpl(Name name) {
        this(name, null, LazyTrieRegistryFactory.getInstance());
    }

    /**
     * Creates a root scope with custom {@link RegistryFactory}.
     *
     * @param name scope name
     * @param registryFactory the factory to use for creating Registry instances
     */
    public ScopeImpl(Name name, RegistryFactory registryFactory) {
        this(name, null, registryFactory);
    }

    /**
     * Creates a child scope (inherits RegistryFactory from parent).
     *
     * @param name scope name
     * @param parent parent scope
     */
    private ScopeImpl(Name name, ScopeImpl parent) {
        this(name, parent, parent != null ? parent.registryFactory : LazyTrieRegistryFactory.getInstance());
    }

    /**
     * Internal constructor for full control.
     *
     * @param name scope name
     * @param parent parent scope (nullable)
     * @param registryFactory the factory to use for creating Registry instances
     */
    @SuppressWarnings("unchecked")
    private ScopeImpl(Name name, Scope parent, RegistryFactory registryFactory) {
        this.name = Objects.requireNonNull(name, "Scope name cannot be null");
        this.parent = parent;
        this.registryFactory = Objects.requireNonNull(registryFactory, "RegistryFactory cannot be null");
        this.childScopes = (Map<Name, Scope>) registryFactory.create();
        // Create Subject once - represents persistent identity of this Scope
        this.scopeSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SCOPE
        );
    }

    @Override
    public Subject subject() {
        return scopeSubject;
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
        Objects.requireNonNull(resource, "Resource cannot be null");
        resources.addFirst(resource);  // Add to front for LIFO closure ordering
        return resource;
    }

    @Override
    public <R extends Resource> Closure<R> closure(R resource) {
        checkClosed();
        register(resource);
        return new ClosureImpl<>(resource);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Close all child scopes first
        for (Scope scope : childScopes.values()) {
            try {
                scope.close();
            } catch (Exception e) {
                // Log but continue closing others
            }
        }

        // Close all resources in LIFO order (reverse of registration)
        // Since we use addFirst(), iteration is already in LIFO order
        resources.forEach(resource -> {
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
