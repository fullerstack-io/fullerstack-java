package io.fullerstack.substrates.scope;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.name.HierarchicalName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedScopeTest {

    @Test
    void shouldCreateScopeWithName() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));

        assertThat((Object) scope).isNotNull();
        assertThat((Object) scope.subject()).isNotNull();
        assertThat(scope.part()).isEqualTo("test");
    }

    @Test
    void shouldCreateChildScope() {
        Scope parent = new ManagedScope(HierarchicalName.of("parent"));
        Scope child = parent.scope(HierarchicalName.of("child"));

        assertThat((Object) child).isNotNull();
        assertThat(child.part()).isEqualTo("child");
    }

    @Test
    void shouldCacheChildScopesByName() {
        Scope parent = new ManagedScope(HierarchicalName.of("parent"));

        Scope child1 = parent.scope(HierarchicalName.of("child"));
        Scope child2 = parent.scope(HierarchicalName.of("child"));

        assertThat((Object) child1).isSameAs(child2);
    }

    @Test
    void shouldRegisterResource() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));
        TestResource resource = new TestResource();

        TestResource registered = scope.register(resource);

        assertThat(registered).isSameAs(resource);
    }

    @Test
    void shouldCloseRegisteredResources() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));
        TestResource resource1 = new TestResource();
        TestResource resource2 = new TestResource();

        scope.register(resource1);
        scope.register(resource2);
        scope.close();

        assertThat(resource1.closed).isTrue();
        assertThat(resource2.closed).isTrue();
    }

    @Test
    void shouldCloseChildScopes() {
        Scope parent = new ManagedScope(HierarchicalName.of("parent"));
        Scope child = parent.scope(HierarchicalName.of("child"));
        TestResource childResource = new TestResource();

        child.register(childResource);
        parent.close();

        assertThat(childResource.closed).isTrue();
    }

    @Test
    void shouldSupportClosure() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));
        TestResource resource = new TestResource();
        AtomicBoolean consumed = new AtomicBoolean(false);

        scope.closure(resource).consume(r -> {
            consumed.set(true);
            assertThat(r).isSameAs(resource);
        });

        assertThat(consumed.get()).isTrue();
        assertThat(resource.closed).isTrue();
    }

    @Test
    void shouldPreventOperationsAfterClose() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));
        scope.close();

        assertThatThrownBy(() -> scope.scope())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");

        assertThatThrownBy(() -> scope.register(new TestResource()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void shouldAllowMultipleCloses() {
        Scope scope = new ManagedScope(HierarchicalName.of("test"));

        scope.close();
        scope.close(); // Should not throw

        assertThat((Object) scope).isNotNull();
    }

    private static class TestResource implements Subscription {
        boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Subject<Subscription> subject() {
            return new HierarchicalSubject<>(
                UuidIdentifier.generate(),
                HierarchicalName.of("test-resource"),
                LinkedState.empty(),
                Subscription.class
            );
        }
    }
}
