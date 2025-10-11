package io.fullerstack.substrates.source;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceImplTest {

    /**
     * Helper to create a test subscriber from a lambda.
     * Since Subscriber extends Substrate, it's not a functional interface.
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

    /**
     * Helper to create a test Subject (simulating a Channel).
     */
    private Subject testSubject(String name) {
        return new SubjectImpl(
            IdImpl.generate(),
            NameImpl.of(name),
            StateImpl.empty(),
            Subject.Type.CHANNEL
        );
    }

    /**
     * Helper to simulate Conduit behavior of invoking subscribers.
     * Directly calls subscriber.accept() for each subscriber, mimicking what Conduit's queue processor does.
     */
    private <E> void notifySource(SourceImpl<E> source, String channelName, E emission) {
        Subject subject = testSubject(channelName);

        // Simulate what ConduitImpl.processEmission() does
        for (Subscriber<E> subscriber : source.getSubscribers()) {
            java.util.List<Pipe<E>> pipes = new CopyOnWriteArrayList<>();

            subscriber.accept(subject, new Registrar<E>() {
                @Override
                public void register(Pipe<E> pipe) {
                    pipes.add(pipe);
                }
            });

            // Emit to all registered pipes
            for (Pipe<E> pipe : pipes) {
                pipe.emit(emission);
            }
        }
    }

    @Test
    void shouldCreateSourceWithDefaultName() {
        SourceImpl<String> source = new SourceImpl<>();

        assertThat((Object) source).isNotNull();
        assertThat((Object) source.subject()).isNotNull();
        assertThat(source.subject().name().part()).isEqualTo("source");
    }

    @Test
    void shouldCreateSourceWithCustomName() {
        Name name = NameImpl.of("custom-source");
        SourceImpl<String> source = new SourceImpl<>(name);

        assertThat((Object) source.subject().name()).isEqualTo(name);
    }

    @Test
    void shouldNotifySubscriberWhenEmissionOccurs() {
        SourceImpl<String> source = new SourceImpl<>();
        AtomicInteger notificationCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> receivedEvents = new CopyOnWriteArrayList<>();

        source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> {
                notificationCount.incrementAndGet();
                receivedEvents.add(emission);
            });
        }));

        notifySource(source, "test-channel", "test-event");

        assertThat(notificationCount.get()).isEqualTo(1);
        assertThat(receivedEvents).containsExactly("test-event");
    }

    @Test
    void shouldNotifyMultipleSubscribers() {
        SourceImpl<String> source = new SourceImpl<>();
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> count1.incrementAndGet());
        }));

        source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> count2.incrementAndGet());
        }));

        notifySource(source, "test-channel", "event");

        assertThat(count1.get()).isEqualTo(1);
        assertThat(count2.get()).isEqualTo(1);
    }

    @Test
    void shouldRemoveSubscriberWhenSubscriptionClosed() {
        SourceImpl<String> source = new SourceImpl<>();
        AtomicInteger count = new AtomicInteger(0);

        Subscription subscription = source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> count.incrementAndGet());
        }));

        notifySource(source, "test-channel", "event1");
        assertThat(count.get()).isEqualTo(1);

        subscription.close();
        notifySource(source, "test-channel", "event2");

        // Should still be 1 (not 2) because subscriber was removed
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void shouldNotNotifyAfterUnsubscribe() {
        SourceImpl<String> source = new SourceImpl<>();
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        Subscription subscription = source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> received.add(emission));
        }));

        notifySource(source, "test-channel", "before");

        subscription.close();
        notifySource(source, "test-channel", "after");

        assertThat(received).containsExactly("before");
    }

    @Test
    void shouldHandleConcurrentSubscribers() throws Exception {
        SourceImpl<String> source = new SourceImpl<>();
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalNotifications = new AtomicInteger(0);

        // Multiple threads subscribe concurrently
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.startVirtualThread(() -> {
                source.subscribe(subscriber((subject, registrar) -> {
                    registrar.register(emission -> totalNotifications.incrementAndGet());
                }));
                latch.countDown();
            });
        }

        // Wait for all subscriptions
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Emit event - all subscribers should be notified
        notifySource(source, "test-channel", "test");

        assertThat(totalNotifications.get()).isEqualTo(threadCount);
    }

    @Test
    void shouldHandleMultipleEmissions() {
        SourceImpl<Integer> source = new SourceImpl<>();
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();

        source.subscribe(subscriber((subject, registrar) -> {
            registrar.register(emission -> received.add(emission));
        }));

        notifySource(source, "test-channel", 1);
        notifySource(source, "test-channel", 2);
        notifySource(source, "test-channel", 3);

        assertThat(received).containsExactly(1, 2, 3);
    }

    @Test
    void shouldNotFailWhenClosingSubscriptionMultipleTimes() {
        SourceImpl<String> source = new SourceImpl<>();

        Subscription subscription = source.subscribe(subscriber((subject, registrar) -> {}));
        subscription.close();
        subscription.close(); // Second close should not fail

        assertThat((Object) subscription).isNotNull();
    }

    @Test
    void shouldRequireNonNullSubscriber() {
        SourceImpl<String> source = new SourceImpl<>();

        assertThatThrownBy(() -> source.subscribe(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Subscriber cannot be null");
    }

    @Test
    void shouldRequireNonNullName() {
        assertThatThrownBy(() -> new SourceImpl<String>(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Source name cannot be null");
    }

    @Test
    void shouldProvideSubscriptionWithSubject() {
        SourceImpl<String> source = new SourceImpl<>();

        Subscription subscription = source.subscribe(subscriber((subject, registrar) -> {}));

        assertThat((Object) subscription.subject()).isNotNull();
        assertThat(subscription.subject().type()).isEqualTo(Subject.Type.SUBSCRIPTION);
    }
}
