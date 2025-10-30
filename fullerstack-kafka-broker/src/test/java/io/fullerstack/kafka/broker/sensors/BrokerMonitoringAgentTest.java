package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.kafka.broker.baseline.SimpleBaselineService;
import io.fullerstack.kafka.core.config.ClusterConfig;
import io.fullerstack.kafka.broker.sensors.BrokerMonitoringAgent.BrokerEndpoint;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BrokerMonitoringAgent.
 * <p>
 * Tests updated for signal-first architecture:
 * - Agent now requires BaselineService
 * - Emits MonitorSignal instead of BrokerMetrics
 */
class BrokerMonitoringAgentTest {

    private ClusterConfig config;
    private BaselineService baselineService;
    private BiConsumer<Name, MonitorSignal> mockEmitter;
    private BrokerMonitoringAgent agent;

    @BeforeEach
    void setUp() {
        config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        baselineService = new SimpleBaselineService();
        mockEmitter = mock(BiConsumer.class);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.shutdown();
        }
    }

    @Test
    void testConstructor() {
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        assertNotNull(agent);
        assertNotNull(agent.getVectorClock());
        assertNotNull(agent.getBrokerEndpoints());
    }

    @Test
    void testConstructor_NullConfig() {
        assertThrows(NullPointerException.class, () ->
                new BrokerMonitoringAgent(null, baselineService, mockEmitter)
        );
    }

    @Test
    void testConstructor_NullBaselineService() {
        assertThrows(NullPointerException.class, () ->
                new BrokerMonitoringAgent(config, null, mockEmitter)
        );
    }

    @Test
    void testConstructor_NullEmitter() {
        assertThrows(NullPointerException.class, () ->
                new BrokerMonitoringAgent(config, baselineService, null)
        );
    }

    @Test
    void testParseBrokerEndpoints_SingleBroker() {
        config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        assertEquals(1, endpoints.size());
        assertEquals("localhost", endpoints.get(0).brokerId());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi", endpoints.get(0).jmxUrl());
    }

    @Test
    void testParseBrokerEndpoints_MultipleBrokers() {
        config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092,b-3.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001,b-2.kafka.us-east-1.amazonaws.com:11001,b-3.kafka.us-east-1.amazonaws.com:11001"
        );
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        assertEquals(3, endpoints.size());
        assertEquals("b-1", endpoints.get(0).brokerId());
        assertEquals("b-2", endpoints.get(1).brokerId());
        assertEquals("b-3", endpoints.get(2).brokerId());

        assertEquals("service:jmx:rmi:///jndi/rmi://b-1.kafka.us-east-1.amazonaws.com:11001/jmxrmi",
                endpoints.get(0).jmxUrl());
        assertEquals("service:jmx:rmi:///jndi/rmi://b-2.kafka.us-east-1.amazonaws.com:11001/jmxrmi",
                endpoints.get(1).jmxUrl());
        assertEquals("service:jmx:rmi:///jndi/rmi://b-3.kafka.us-east-1.amazonaws.com:11001/jmxrmi",
                endpoints.get(2).jmxUrl());
    }

    @Test
    void testParseBrokerEndpoints_MskHostnames() {
        config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092,b-2.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001,b-2.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000,
                null
        );
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        assertEquals(2, endpoints.size());
        assertEquals("b-1", endpoints.get(0).brokerId());
        assertEquals("b-2", endpoints.get(1).brokerId());
    }

    @Test
    void testGetBrokerEndpoints_DefensiveCopy() {
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints1 = agent.getBrokerEndpoints();
        List<BrokerEndpoint> endpoints2 = agent.getBrokerEndpoints();

        // Different list instances (defensive copy)
        assertNotSame(endpoints1, endpoints2);

        // Same content
        assertEquals(endpoints1, endpoints2);
    }

    @Test
    void testBrokerEndpoint_Record() {
        BrokerEndpoint endpoint = new BrokerEndpoint("b-1", "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi");

        assertEquals("b-1", endpoint.brokerId());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi", endpoint.jmxUrl());
    }

    @Test
    void testBrokerEndpoint_NullBrokerId() {
        assertThrows(NullPointerException.class, () ->
                new BrokerEndpoint(null, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
        );
    }

    @Test
    void testBrokerEndpoint_NullJmxUrl() {
        assertThrows(NullPointerException.class, () ->
                new BrokerEndpoint("b-1", null)
        );
    }

    @Test
    void testVectorClock_InitiallyEmpty() {
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        assertEquals(0L, agent.getVectorClock().get("broker-1"));
        assertEquals(0L, agent.getVectorClock().get("broker-2"));
    }

    @Test
    void testShutdown_GracefulTermination() throws InterruptedException {
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);
        agent.start();

        // Give scheduler time to start
        Thread.sleep(100);

        // Shutdown should complete within 5 seconds
        long start = System.currentTimeMillis();
        agent.shutdown();
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration < 5_000, "Shutdown took " + duration + "ms, expected < 5000ms");
    }

    @Test
    void testShutdown_Idempotent() {
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);
        agent.start();

        agent.shutdown();
        agent.shutdown();  // Should not throw

        // Verify no exceptions thrown
    }

    @Test
    void testParseBrokerEndpoints_MismatchedLengths() {
        // More Kafka endpoints than JMX endpoints
        config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092,b-3.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001,b-2.kafka.us-east-1.amazonaws.com:11001"
        );
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        // Should use minimum count (2)
        assertEquals(2, endpoints.size());
    }

    @Test
    void testParseBrokerEndpoints_SingleCharBootstrap() {
        // Test minimal valid bootstrap server config
        config = new ClusterConfig(
                "test-account",
                "us-east-1",
                "test-cluster",
                "localhost:9092",
                "localhost:11001",
                30_000,
                null
        );
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        assertEquals(1, endpoints.size());
        assertEquals("localhost", endpoints.get(0).brokerId());
    }

    @Test
    void testJmxUrlFormat() {
        config = ClusterConfig.withDefaults(
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );
        agent = new BrokerMonitoringAgent(config, baselineService, mockEmitter);

        List<BrokerEndpoint> endpoints = agent.getBrokerEndpoints();

        assertEquals(1, endpoints.size());
        String jmxUrl = endpoints.get(0).jmxUrl();

        // Verify JMX URL format
        assertTrue(jmxUrl.startsWith("service:jmx:rmi:///jndi/rmi://"));
        assertTrue(jmxUrl.endsWith("/jmxrmi"));
        assertTrue(jmxUrl.contains("b-1.trans.kafka.us-east-1.amazonaws.com:11001"));
    }
}
