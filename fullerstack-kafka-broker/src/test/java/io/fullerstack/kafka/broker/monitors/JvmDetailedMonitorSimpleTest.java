package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import java.lang.management.ManagementFactory;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple integration test for JvmDetailedMonitor using real JMX.
 */
@Timeout(10)
class JvmDetailedMonitorSimpleTest {

    @Test
    void shouldPollJvmMetricsWithoutErrors() throws Exception {
        // Given - Use local JMX
        MBeanServerConnection mbsc = ManagementFactory.getPlatformMBeanServer();
        Circuit circuit = cortex().circuit(cortex().name("test"));

        // When
        try (JvmDetailedMonitor monitor = new JvmDetailedMonitor(mbsc, circuit, 1000, java.util.concurrent.TimeUnit.SECONDS)) {
            // Manually poll
            monitor.pollJvmMetrics();
            circuit.await();

            // Then - Should complete without exceptions
            assertThat(true).isTrue();
        }
    }

    @Test
    void shouldHandleMissingMBeansGracefully() throws Exception {
        // Given - Mock MBean server that throws exceptions
        MBeanServerConnection mockMbsc = org.mockito.Mockito.mock(MBeanServerConnection.class);
        org.mockito.Mockito.when(mockMbsc.getAttribute(org.mockito.Mockito.any(ObjectName.class), org.mockito.Mockito.anyString()))
            .thenThrow(new javax.management.InstanceNotFoundException("Not found"));

        Circuit circuit = cortex().circuit(cortex().name("test"));

        // When
        try (JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, 1000, java.util.concurrent.TimeUnit.SECONDS)) {
            monitor.pollJvmMetrics();
            circuit.await();

            // Then - Should handle gracefully without throwing
            assertThat(true).isTrue();
        }
    }
}
