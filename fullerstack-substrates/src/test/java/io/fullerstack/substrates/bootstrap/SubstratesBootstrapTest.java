package io.fullerstack.substrates.bootstrap;

import io.fullerstack.substrates.bootstrap.SubstratesBootstrap.BootstrapResult;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.fullerstack.substrates.spi.ComposerProvider;
import io.fullerstack.substrates.spi.SensorProvider;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Composer;
import io.humainary.substrates.api.Substrates.Cortex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.humainary.substrates.api.Substrates.Composer.pipe;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SubstratesBootstrap}.
 * <p>
 * Demonstrates convention-based circuit discovery and bootstrap.
 */
class SubstratesBootstrapTest {

    @Test
    void testBootstrap_DiscoversTestCircuits() {
        // Bootstrap discovers config_broker-health.properties and config_partition-flow.properties
        BootstrapResult result = SubstratesBootstrap.bootstrap();

        // Should discover our test circuits
        assertThat(result.getCircuitNames())
            .contains("broker-health", "partition-flow");

        // Cleanup
        try {
            result.close();
        } catch (Exception e) {
            fail("Failed to close bootstrap result", e);
        }
    }

    @Test
    void testBootstrap_WithCallbacks() {
        StringBuilder log = new StringBuilder();

        BootstrapResult result = SubstratesBootstrap.builder()
            .onCircuitCreated((name, circuit) -> {
                log.append("Created: ").append(name).append("\n");
            })
            .onSensorStarted((circuit, sensor) -> {
                log.append("Started sensor: ").append(sensor.name())
                    .append(" for circuit: ").append(circuit).append("\n");
            })
            .bootstrap();

        // Should have logged circuit creation
        assertThat(log.toString()).contains("Created: broker-health");
        assertThat(log.toString()).contains("Created: partition-flow");

        // Cleanup
        try {
            result.close();
        } catch (Exception e) {
            fail("Failed to close bootstrap result", e);
        }
    }

    @Test
    void testBootstrap_GetsCircuitByName() {
        BootstrapResult result = SubstratesBootstrap.bootstrap();

        // Can get circuit by name
        assertThat(result.getCircuit("broker-health")).isNotNull();
        assertThat(result.getCircuit("partition-flow")).isNotNull();
        assertThat(result.getCircuit("nonexistent")).isNull();

        // Cleanup
        try {
            result.close();
        } catch (Exception e) {
            fail("Failed to close bootstrap result", e);
        }
    }

    /**
     * Example ComposerProvider implementation for testing.
     */
    public static class TestComposerProvider implements ComposerProvider {

        @Override
        public Composer<?, ?> getComposer(String circuitName, String componentName, ComponentType type) {
            // Return pipe composer for all components
            return pipe();
        }

        @Override
        public Class<?> getSignalType(String circuitName, String componentName) {
            return String.class;  // Use String as test signal type
        }
    }

    /**
     * Example SensorProvider implementation for testing.
     */
    public static class TestSensorProvider implements SensorProvider {

        @Override
        public List<Sensor> getSensors(
                String circuitName,
                Circuit circuit,
                Cortex cortex,
                HierarchicalConfig config
        ) {
            // No sensors for test circuits
            return List.of();
        }
    }
}
