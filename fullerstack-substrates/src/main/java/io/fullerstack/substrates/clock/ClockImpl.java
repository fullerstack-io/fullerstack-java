package io.fullerstack.substrates.clock;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
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
 *   <li>Resource lifecycle management</li>
 * </ul>
 *
 * @see Clock
 */
public class ClockImpl implements Clock {
    private final Subject clockSubject;
    private final Source<Instant> source;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    /**
     * Creates a clock with the specified name.
     *
     * @param name clock name
     */
    public ClockImpl(Name name) {
        Objects.requireNonNull(name, "Clock name cannot be null");
        Id id = IdImpl.generate();
        this.clockSubject = new SubjectImpl(
            id,
            name,
            StateImpl.empty(),
            Subject.Type.CLOCK
        );
        this.source = new SourceImpl<>(name);
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("clock-" + name.part());
            return thread;
        });
    }

    /**
     * Creates a clock with default name.
     */
    public ClockImpl() {
        this(NameImpl.of("clock"));
    }

    @Override
    public Subject subject() {
        return clockSubject;
    }

    @Override
    public Source<Instant> source() {
        return source;
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
            private final Id subscriptionId = IdImpl.generate();
            private final Subject subscriptionSubject = new SubjectImpl(
                subscriptionId,
                name.name(subscriptionId.toString()),
                StateImpl.empty(),
                Subject.Type.SUBSCRIPTION
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
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
