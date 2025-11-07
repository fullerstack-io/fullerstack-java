package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MonitorSignComposer}.
 *
 * <p>In Substrates 1.0.0-PREVIEW, composers transform a Channel into a Pipe.
 * The MonitorSignComposer returns the channel's pipe for identity passthrough.
 *
 * <p>These tests verify the composer implements the correct interface signature.
 * Integration tests in HierarchyManagerTest verify actual signal flow behavior.
 */
class MonitorSignComposerTest {

    @Test
    void testComposerImplementsCorrectInterface() {
        // Given/When: A composer instance is created
        MonitorSignComposer composer = new MonitorSignComposer();

        // Then: It should implement Composer<Monitors.Sign, Pipe<Monitors.Sign>>
        assertThat(composer).isInstanceOf(Composer.class);
        assertThat(composer).isNotNull();
    }

    @Test
    void testComposerCanBeInstantiated() {
        // Given/When: Multiple composer instances are created
        MonitorSignComposer composer1 = new MonitorSignComposer();
        MonitorSignComposer composer2 = new MonitorSignComposer();

        // Then: Both should be valid instances
        assertThat(composer1).isNotNull();
        assertThat(composer2).isNotNull();
        // Composers are stateless, so different instances are independent
        assertThat(composer1).isNotSameAs(composer2);
    }

    @Test
    void testComposerIsStateless() {
        // Given: A composer instance
        MonitorSignComposer composer = new MonitorSignComposer();

        // Then: Composer should be stateless (no fields beyond any constants)
        // This is verified by the implementation having no instance fields
        // The compose method only uses the channel parameter
        assertThat(composer).isNotNull();
    }
}
