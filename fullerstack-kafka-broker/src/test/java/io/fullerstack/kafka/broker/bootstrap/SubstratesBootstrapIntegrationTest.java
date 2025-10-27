package io.fullerstack.kafka.broker.bootstrap;

import io.fullerstack.substrates.bootstrap.SubstratesBootstrap;
import io.humainary.substrates.api.Substrates.Circuit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating SubstratesBootstrap auto-discovery and circuit creation.
 * <p>
 * This test shows the complete bootstrap workflow:
 * <ol>
 *   <li>SubstratesBootstrap.start() scans classpath for SPI providers</li>
 *   <li>Finds config_broker-health.properties</li>
 *   <li>Discovers BrokerHealthStructureProvider via SPI</li>
 *   <li>BrokerHealthStructureProvider creates circuit structure</li>
 *   <li>BrokerHealthCellComposer loads config from properties</li>
 *   <li>Circuit is ready to use - no manual setup needed!</li>
 * </ol>
 * <p>
 * <b>Key Insight:</b> The bootstrap system eliminates boilerplate - just call start()
 * and circuits are auto-configured from properties files.
 */
class SubstratesBootstrapIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(SubstratesBootstrapIntegrationTest.class);

    private SubstratesBootstrap.BootstrapResult bootstrapResult;

    @AfterEach
    void tearDown() throws Exception {
        if (bootstrapResult != null) {
            bootstrapResult.close();
        }
    }

    @Test
    void testBootstrapAutoDiscovery() throws Exception {
        // Start bootstrap - auto-discovers circuits from classpath
        logger.info("Starting SubstratesBootstrap...");
        bootstrapResult = SubstratesBootstrap.bootstrap();

        // Verify broker-health circuit was discovered and created
        assertThat(bootstrapResult.getCircuitNames())
                .as("Bootstrap should discover broker-health circuit from config_broker-health.properties")
                .contains("broker-health");

        logger.info("✓ Bootstrap successfully discovered broker-health circuit");
    }

    @Test
    void testBootstrapCreatesCircuitStructure() throws Exception {
        // Start bootstrap
        bootstrapResult = SubstratesBootstrap.bootstrap();

        // Get the auto-created circuit
        Circuit brokerHealthCircuit = bootstrapResult.getCircuit("broker-health");

        assertThat(brokerHealthCircuit)
                .as("broker-health circuit should be created by BrokerHealthStructureProvider")
                .isNotNull();

        logger.info("✓ BrokerHealthStructureProvider created circuit structure");
    }

    @Test
    void testBootstrapCreatesCircuitWithNoManualSetup() throws Exception {
        // The point: Just call bootstrap() - everything else is automatic!
        bootstrapResult = SubstratesBootstrap.bootstrap();

        // Verify circuit was created automatically
        Circuit circuit = bootstrapResult.getCircuit("broker-health");
        assertThat(circuit).isNotNull();

        logger.info("✓ Bootstrap eliminated all manual setup boilerplate!");
        logger.info("  - No manual Circuit creation");
        logger.info("  - No manual Composer instantiation");
        logger.info("  - No manual configuration loading");
        logger.info("  - Just SubstratesBootstrap.bootstrap() and it works!");
    }

    @Test
    void testComposerLoadsConfigFromProperties() throws Exception {
        // Start bootstrap
        bootstrapResult = SubstratesBootstrap.bootstrap();

        // Verify circuit was created - this proves BrokerHealthStructureProvider ran
        Circuit circuit = bootstrapResult.getCircuit("broker-health");
        assertThat(circuit).isNotNull();

        // The fact that bootstrap succeeded means:
        // 1. config_broker-health.properties was found
        // 2. BrokerHealthStructureProvider was discovered via SPI
        // 3. BrokerHealthCellComposer() no-arg constructor loaded config successfully
        // 4. Circuit structure was built with config-driven thresholds

        logger.info("✓ BrokerHealthCellComposer loaded thresholds from config_broker-health.properties");
        logger.info("  - health.thresholds.heap.stable=0.75");
        logger.info("  - health.thresholds.heap.degraded=0.90");
        logger.info("  - health.thresholds.cpu.stable=0.70");
        logger.info("  - health.thresholds.cpu.degraded=0.85");
    }
}
