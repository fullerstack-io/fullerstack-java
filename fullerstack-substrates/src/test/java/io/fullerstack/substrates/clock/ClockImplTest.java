package io.fullerstack.substrates.clock;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.NameNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockImplTest {
    private ClockImpl clock;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setup() {
        scheduler = Executors.newScheduledThreadPool(1);
    }

    @AfterEach
    void cleanup() {
        if (clock != null) {
            clock.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldCreateClockWithDefaultName() {
        clock = new ClockImpl(scheduler);

        assertThat((Object) clock).isNotNull();
        assertThat((Object) clock.subject()).isNotNull();
        assertThat(clock.subject().type()).isEqualTo(Clock.class);
    }

    @Test
    void shouldCreateClockWithCustomName() {
        Name name = NameNode.of("custom-clock");
        clock = new ClockImpl(name, scheduler);

        assertThat((Object) clock.subject().name()).isEqualTo(name);
    }

    @Test
    void shouldEmitPeriodicEventsOnMillisecondCycle() throws Exception {
        clock = new ClockImpl(scheduler);
        CopyOnWriteArrayList<Instant> emissions = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        Subscription subscription = clock.consume(
            NameNode.of("test"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                emissions.add(instant);
                latch.countDown();
            }
        );

        // Wait for at least 3 emissions
        assertThat(latch.await(200, TimeUnit.MILLISECONDS)).isTrue();

        subscription.close();

        assertThat(emissions).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldStopEmissionsWhenSubscriptionClosed() throws Exception {
        clock = new ClockImpl(scheduler);
        AtomicInteger count = new AtomicInteger(0);

        Subscription subscription = clock.consume(
            NameNode.of("test"),
            Clock.Cycle.MILLISECOND,
            instant -> count.incrementAndGet()
        );

        Thread.sleep(10);
        int countBeforeClose = count.get();
        subscription.close();
        Thread.sleep(20);
        int countAfterClose = count.get();

        // Count should not increase significantly after close
        assertThat(countAfterClose).isLessThanOrEqualTo(countBeforeClose + 2);
    }

    @Test
    void shouldHandleMultipleSubscriptions() throws Exception {
        clock = new ClockImpl(scheduler);
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(6);

        Subscription sub1 = clock.consume(
            NameNode.of("sub1"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                count1.incrementAndGet();
                latch.countDown();
            }
        );

        Subscription sub2 = clock.consume(
            NameNode.of("sub2"),
            Clock.Cycle.MILLISECOND,
            instant -> {
                count2.incrementAndGet();
                latch.countDown();
            }
        );

        assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        sub1.close();
        sub2.close();

        assertThat(count1.get()).isGreaterThanOrEqualTo(2);
        assertThat(count2.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldRequireNonNullName() {
        assertThatThrownBy(() -> new ClockImpl(null, scheduler))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Clock name cannot be null");
    }

    @Test
    void shouldRequireNonNullScheduler() {
        assertThatThrownBy(() -> new ClockImpl(NameNode.of("test"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Scheduler cannot be null");
    }

    @Test
    void shouldRequireNonNullParameters() {
        clock = new ClockImpl(scheduler);

        assertThatThrownBy(() -> clock.consume(null, Clock.Cycle.SECOND, instant -> {}))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> clock.consume(NameNode.of("test"), null, instant -> {}))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> clock.consume(NameNode.of("test"), Clock.Cycle.SECOND, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPreventConsumeAfterClose() {
        clock = new ClockImpl(scheduler);
        clock.close();

        assertThatThrownBy(() -> clock.consume(
            NameNode.of("test"),
            Clock.Cycle.SECOND,
            instant -> {}
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Clock is closed");
    }

    @Test
    void shouldAllowMultipleCloses() {
        clock = new ClockImpl(scheduler);

        clock.close();
        clock.close(); // Should not throw

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldProvideSubscriptionWithSubject() {
        clock = new ClockImpl(scheduler);

        Subscription subscription = clock.consume(
            NameNode.of("test"),
            Clock.Cycle.SECOND,
            instant -> {}
        );

        assertThat((Object) subscription.subject()).isNotNull();
        assertThat(subscription.subject().type()).isEqualTo(Subscription.class);

        subscription.close();
    }

    @Test
    void shouldHandleSecondCycle() throws Exception {
        clock = new ClockImpl(scheduler);
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        Subscription subscription = clock.consume(
            NameNode.of("test"),
            Clock.Cycle.SECOND,
            instant -> {
                count.incrementAndGet();
                latch.countDown();
            }
        );

        // Wait up to 3 seconds for 2 emissions
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        subscription.close();

        assertThat(count.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldCleanupSchedulerOnClose() throws Exception {
        clock = new ClockImpl(scheduler);
        AtomicInteger count = new AtomicInteger(0);

        clock.consume(
            NameNode.of("test"),
            Clock.Cycle.MILLISECOND,
            instant -> count.incrementAndGet()
        );

        Thread.sleep(20);
        int countBeforeClose = count.get();

        clock.close();
        Thread.sleep(20);
        int countAfterClose = count.get();

        // Emissions should stop after close
        assertThat(countAfterClose).isLessThanOrEqualTo(countBeforeClose + 2);
    }
}
