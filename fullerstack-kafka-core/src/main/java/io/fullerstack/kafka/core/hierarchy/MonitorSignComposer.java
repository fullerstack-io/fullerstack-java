package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Composer;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.ext.serventis.ext.Monitors;

/**
 * Egress composer for Cell&lt;Monitors.Sign, Monitors.Sign&gt; hierarchy.
 *
 * <p>In Substrates 1.0.0-PREVIEW, Cell egress composers simply return the channel's pipe
 * for identity passthrough. The standard pattern is to use identity composers and implement
 * aggregation logic in subscribers.
 *
 * <p>This composer returns {@code channel.pipe()} which allows Monitors.Sign emissions
 * from child cells to flow upward to the parent cell unchanged. Subscribers listening to
 * the parent cell receive all child emissions and can apply aggregation rules there.
 *
 * <p>For worst-case aggregation (DOWN > DEFECTIVE > DEGRADED > ERRATIC > DIVERGING >
 * CONVERGING > STABLE), implement a subscriber on the parent cell that tracks child
 * emissions and selects the worst sign.
 *
 * @since 1.0.0
 * @see HierarchyManager
 */
public class MonitorSignComposer implements Composer<Monitors.Sign, Pipe<Monitors.Sign>> {

    /**
     * Returns the channel's pipe for identity passthrough of Monitors.Sign emissions.
     *
     * <p>Child cell emissions flow through this pipe to parent cells.
     *
     * @param channel the channel for which to create the egress pipe
     * @return the channel's pipe for Monitors.Sign emissions
     */
    @Override
    public Pipe<Monitors.Sign> compose(Channel<Monitors.Sign> channel) {
        return channel.pipe();
    }
}
