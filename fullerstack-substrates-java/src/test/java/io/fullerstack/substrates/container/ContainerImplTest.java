package io.fullerstack.substrates.container;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerImplTest {

    /**
     * Helper to create a test subscriber from a lambda.
     */
    private <E> Subscriber<E> subscriber(java.util.function.BiConsumer<Subject, Registrar<E>> handler) {
        return new Subscriber<E>() {
            @Override
            public Subject subject() {
                return new SubjectImpl(
                    IdImpl.of(java.util.UUID.randomUUID()),
                    NameImpl.of("test-subscriber"),
                    StateImpl.empty(),
                    Subject.Type.SUBSCRIBER
                );
            }

            @Override
            public void accept(Subject subject, Registrar<E> registrar) {
                handler.accept(subject, registrar);
            }
        };
    }

    @Test
    void shouldCreateContainerWithPoolAndSource() {
        Pool<String> pool = new PoolImpl<>(name -> "value-" + name.part());
        Source<String> source = new SourceImpl<>(NameImpl.of("test-source"));

        Container<Pool<String>, Source<String>> container = new ContainerImpl<>(pool, source);

        assertThat((Object) container).isNotNull();
        assertThat((Object) container.subject()).isNotNull();
        assertThat(container.subject().type()).isEqualTo(Subject.Type.CONTAINER);
    }

    @Test
    void shouldDelegateGetToPool() {
        Pool<String> pool = new PoolImpl<>(name -> "value-" + name.part());
        Source<String> source = new SourceImpl<>();

        Container<Pool<String>, Source<String>> container = new ContainerImpl<>(pool, source);

        Pool<String> poolResult = container.get(NameImpl.of("test"));

        assertThat((Object) poolResult).isSameAs(pool);
    }

    @Test
    void shouldProvideSourceForSubscriptions() {
        Pool<String> pool = new PoolImpl<>(name -> "value");
        SourceImpl<String> source = new SourceImpl<>();

        ContainerImpl<String, String> container = new ContainerImpl<>(pool, source);

        AtomicInteger notificationCount = new AtomicInteger(0);

        container.eventSource().subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> notificationCount.incrementAndGet());
        }));

        // Emit from source
        source.emit("test-event");

        assertThat(notificationCount.get()).isEqualTo(1);
    }

    @Test
    void shouldSupportBothPoolAndSourceOperations() {
        Pool<Integer> pool = new PoolImpl<>(name -> 42);
        SourceImpl<String> source = new SourceImpl<>();

        ContainerImpl<Integer, String> container = new ContainerImpl<>(pool, source);

        // Test pool functionality
        Pool<Integer> poolResult = container.get(NameImpl.of("test"));
        assertThat((Object) poolResult).isSameAs(pool);

        // Test source functionality
        AtomicInteger emissionCount = new AtomicInteger(0);
        container.eventSource().subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> emissionCount.incrementAndGet());
        }));

        source.emit("event1");
        source.emit("event2");

        assertThat(emissionCount.get()).isEqualTo(2);
    }

    @Test
    void shouldRequireNonNullPool() {
        Source<String> source = new SourceImpl<>();

        assertThatThrownBy(() -> new ContainerImpl<>(null, source))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Pool cannot be null");
    }

    @Test
    void shouldRequireNonNullSource() {
        Pool<String> pool = new PoolImpl<>(name -> "value");

        assertThatThrownBy(() -> new ContainerImpl<>(pool, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Source cannot be null");
    }

    @Test
    void shouldUseSourceNameForSubject() {
        Pool<String> pool = new PoolImpl<>(name -> "value");
        Source<String> source = new SourceImpl<>(NameImpl.of("test-source"));

        Container<Pool<String>, Source<String>> container = new ContainerImpl<>(pool, source);

        assertThat((Object) container.subject().name()).isEqualTo(NameImpl.of("test-source"));
    }

    @Test
    void shouldHandleMultipleSubscribers() {
        Pool<String> pool = new PoolImpl<>(name -> "value");
        SourceImpl<Integer> source = new SourceImpl<>();

        ContainerImpl<String, Integer> container = new ContainerImpl<>(pool, source);

        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        container.eventSource().subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> count1.incrementAndGet());
        }));

        container.eventSource().subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> count2.incrementAndGet());
        }));

        source.emit(1);
        source.emit(2);

        assertThat(count1.get()).isEqualTo(2);
        assertThat(count2.get()).isEqualTo(2);
    }

    @Test
    void shouldSupportGenericTypes() {
        // Pool of Strings, Source of Integers
        Pool<String> pool = new PoolImpl<>(name -> "str-" + name.part());
        SourceImpl<Integer> source = new SourceImpl<>();

        ContainerImpl<String, Integer> container = new ContainerImpl<>(pool, source);

        Pool<String> poolResult = container.get(NameImpl.of("test"));
        assertThat((Object) poolResult).isSameAs(pool);

        AtomicInteger sum = new AtomicInteger(0);
        container.eventSource().subscribe(subscriber((subject, registrar) -> {
            registrar.register(value -> sum.addAndGet(value));
        }));

        source.emit(10);
        source.emit(20);

        assertThat(sum.get()).isEqualTo(30);
    }
}
