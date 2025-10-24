package io.fullerstack.substrates.clock;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.subscription.CallbackSubscription;
import io.fullerstack.substrates.name.HierarchicalName;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of Substrates.Clock for event timing.
 *
 * <p>Provides cyclic event emission based on Clock.Cycle intervals.
 *
 * <p>Features:
 * <ul>
 *   <li>Event source for Instant emissions</li>
 *   <li>Periodic emission via consume() with Clock.Cycle</li>
 *   <li>Shared scheduler from Circuit (no per-clock thread pools)</li>
 * </ul>
 *
 * @see Clock
 */
public class ScheduledClock implements Clock {
    private final Subject clockSubject;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    // Direct subscriber management for Instant (Clock IS-A Source<Instant>)
    private final List<Subscriber<Instant>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Creates a clock with the specified name and shared scheduler.
     *
     * @param name clock name
     * @param scheduler shared ScheduledExecutorService from Circuit
     */
    public ScheduledClock(Name name, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(name, "Clock name cannot be null");
        Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        Id id = UuidIdentifier.generate();
        this.clockSubject = new HierarchicalSubject<>(
            id,
            name,
            LinkedState.empty(),
            Clock.class
        );
        this.scheduler = scheduler;
    }

    /**
     * Creates a clock with default name and shared scheduler.
     *
     * @param scheduler shared ScheduledExecutorService from Circuit
     */
    public ScheduledClock(ScheduledExecutorService scheduler) {
        this(HierarchicalName.of("clock"), scheduler);
    }

    @Override
    public Subject subject() {
        return clockSubject;
    }

    @Override
    public Subscription subscribe(Subscriber<Instant> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        subscribers.add(subscriber);
        return new CallbackSubscription(() -> subscribers.remove(subscriber), clockSubject);
    }

    @Override
    public Subscription consume(Name name, Clock.Cycle cycle, Pipe<Instant> pipe) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(cycle, "Cycle cannot be null");
        Objects.requireNonNull(pipe, "Pipe cannot be null");

        if (closed) {
            throw new IllegalStateException("Clock is closed");
        }

        // Schedule periodic emission based on cycle
        long periodMillis = cycle.units();
        var future = scheduler.scheduleAtFixedRate(
            () -> {
                if (!closed) {
                    pipe.emit(Instant.now());
                }
            },
            periodMillis,
            periodMillis,
            TimeUnit.MILLISECONDS
        );

        // Return subscription that cancels the scheduled task
        // Each subscription has unique ID and stable Subject
        return new Subscription() {
            private volatile boolean subscriptionClosed = false;
            private final Id subscriptionId = UuidIdentifier.generate();
            private final Subject subscriptionSubject = new HierarchicalSubject<>(
                subscriptionId,
                name.name(subscriptionId.toString()),
                LinkedState.empty(),
                Subscription.class
            );

            @Override
            public Subject subject() {
                return subscriptionSubject;
            }

            @Override
            public void close() {
                if (!subscriptionClosed) {
                    subscriptionClosed = true;
                    future.cancel(false);
                }
            }
        };
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // Note: Clock no longer owns the scheduler - Circuit manages its lifecycle
            // Just mark closed so consume() stops scheduling new tasks
        }
    }
}
