package io.fullerstack.kafka.demo;

import io.fullerstack.kafka.coordination.central.RequestHandler;
import io.fullerstack.kafka.coordination.central.ResponseSender;
import io.fullerstack.kafka.coordination.central.SidecarRegistry;
import io.fullerstack.kafka.coordination.central.SpeechActListener;
import io.fullerstack.kafka.producer.sidecar.AgentCoordinationBridge;
import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import io.fullerstack.kafka.producer.sidecar.SidecarResponseListener;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for distributed Speech Act coordination.
 * <p>
 * Tests the complete flow:
 * <pre>
 * 1. Sidecar Agent breaches promise (cannot self-regulate)
 * 2. Sidecar sends REQUEST speech act to central via Kafka
 * 3. Central platform receives REQUEST
 * 4. Central responds: ACKNOWLEDGE → PROMISE → DELIVER
 * 5. Sidecar receives responses via SidecarResponseListener
 * 6. AgentCoordinationBridge handles central responses
 * </pre>
 *
 * @since 1.0.0
 */
@Testcontainers
class SpeechActCoordinationIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(SpeechActCoordinationIntegrationTest.class);

    private static final String REQUEST_TOPIC = "test.speech-acts";
    private static final String RESPONSE_TOPIC = "test.responses";
    private static final String SIDECAR_ID = "test-sidecar-1";

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    // Central infrastructure
    private Circuit centralCircuit;
    private Conduit<Agents.Agent, Agents.Signal> centralAgents;
    private Conduit<Actors.Actor, Actors.Sign> centralActors;
    private SpeechActListener centralListener;
    private Thread centralListenerThread;

    // Sidecar infrastructure
    private Circuit sidecarCircuit;
    private Conduit<Agents.Agent, Agents.Signal> sidecarAgents;
    private Conduit<Actors.Actor, Actors.Sign> sidecarActors;
    private AgentCoordinationBridge coordinationBridge;
    private SidecarResponseListener responseListener;
    private Thread responseListenerThread;
    private KafkaCentralCommunicator communicator;

    @BeforeEach
    void setUp() throws java.lang.Exception {
        String bootstrapServers = kafka.getBootstrapServers();

        // Create topics
        createTopics(bootstrapServers);

        // Set up central platform
        setupCentralPlatform(bootstrapServers);

        // Set up sidecar
        setupSidecar(bootstrapServers);

        // Give Kafka consumers time to stabilize
        Thread.sleep(2000);

        logger.info("✅ Test infrastructure ready");
    }

    @AfterEach
    void tearDown() throws java.lang.Exception {
        // Shutdown in reverse order
        if (coordinationBridge != null) coordinationBridge.close();
        if (responseListener != null) responseListener.close();
        if (responseListenerThread != null) {
            responseListenerThread.interrupt();
            responseListenerThread.join(1000);
        }
        if (centralListener != null) centralListener.close();
        if (centralListenerThread != null) {
            centralListenerThread.interrupt();
            centralListenerThread.join(1000);
        }
        if (communicator != null) communicator.close();
        if (sidecarCircuit != null) sidecarCircuit.close();
        if (centralCircuit != null) centralCircuit.close();

        logger.info("✅ Test infrastructure shutdown complete");
    }

    /**
     * Test the complete speech act coordination flow.
     */
    @Test
    void testSpeechActCoordinationFlow() throws java.lang.Exception {
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║  Testing Complete Speech Act Coordination Flow            ║");
        logger.info("╚════════════════════════════════════════════════════════════╝");

        // Track speech acts received
        CountDownLatch acknowledgeReceived = new CountDownLatch(1);
        CountDownLatch promiseReceived = new CountDownLatch(1);
        CountDownLatch deliverReceived = new CountDownLatch(1);
        AtomicBoolean requestSent = new AtomicBoolean(false);

        // Subscribe to sidecar actor signals to verify responses
        sidecarActors.subscribe(cortex().subscriber(
            cortex().name("test-observer"),
            (subject, registrar) -> {
                registrar.register(sign -> {
                    String actorName = subject.name().toString();
                    logger.info("[TEST] Observed actor signal: {} -> {}", actorName, sign);

                    if (actorName.startsWith("central.")) {
                        // Response from central
                        switch (sign) {
                            case Actors.Sign.ACKNOWLEDGE -> acknowledgeReceived.countDown();
                            case Actors.Sign.PROMISE -> promiseReceived.countDown();
                            case Actors.Sign.DELIVER -> deliverReceived.countDown();
                        }
                    } else if (sign == Actors.Sign.REQUEST) {
                        // Request from sidecar
                        requestSent.set(true);
                    }
                });
            }
        ));

        // PHASE 1: Simulate agent promise breach
        logger.info("\n[PHASE 1] Simulating agent promise breach...");
        Agents.Agent testAgent = sidecarAgents.percept(cortex().name("test-agent.buffer-throttle"));
        testAgent.promise(Agents.Dimension.PROMISER);
        testAgent.breach(Agents.Dimension.PROMISER);  // ← Triggers REQUEST via AgentCoordinationBridge

        // Wait for circuit to process
        sidecarCircuit.await();

        // Verify REQUEST was sent
        assertTrue(requestSent.get(), "Sidecar should have sent REQUEST speech act");
        logger.info("✅ REQUEST speech act sent");

        // PHASE 2: Wait for central to process and respond
        logger.info("\n[PHASE 2] Waiting for central platform responses...");

        // Wait for ACKNOWLEDGE
        assertTrue(acknowledgeReceived.await(10, TimeUnit.SECONDS),
            "Should receive ACKNOWLEDGE from central within 10s");
        logger.info("✅ ACKNOWLEDGE received from central");

        // Wait for PROMISE
        assertTrue(promiseReceived.await(10, TimeUnit.SECONDS),
            "Should receive PROMISE from central within 10s");
        logger.info("✅ PROMISE received from central");

        // Wait for DELIVER
        assertTrue(deliverReceived.await(10, TimeUnit.SECONDS),
            "Should receive DELIVER from central within 10s");
        logger.info("✅ DELIVER received from central");

        logger.info("\n╔════════════════════════════════════════════════════════════╗");
        logger.info("║  ✅ Complete Speech Act Flow Verified                      ║");
        logger.info("╚════════════════════════════════════════════════════════════╝");
    }

    private void createTopics(String bootstrapServers) throws java.lang.Exception {
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            adminClient.createTopics(List.of(
                new NewTopic(REQUEST_TOPIC, 1, (short) 1),
                new NewTopic(RESPONSE_TOPIC, 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);

            logger.info("✅ Created Kafka topics: {}, {}", REQUEST_TOPIC, RESPONSE_TOPIC);
        }
    }

    private void setupCentralPlatform(String bootstrapServers) throws java.lang.Exception {
        // Create central circuit
        centralCircuit = cortex().circuit(cortex().name("central-test"));

        // Create central conduits
        centralAgents = centralCircuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );
        centralActors = centralCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        // Create Kafka admin client
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);
        AdminClient adminClient = AdminClient.create(adminProps);

        // Create response sender
        ResponseSender responseSender = new ResponseSender(bootstrapServers, RESPONSE_TOPIC);

        // Create request handler
        RequestHandler requestHandler = new RequestHandler(
            centralAgents, centralActors, adminClient, responseSender
        );

        // Create registry and listener
        SidecarRegistry registry = new SidecarRegistry();
        centralListener = new SpeechActListener(bootstrapServers, REQUEST_TOPIC, requestHandler, registry);
        centralListenerThread = Thread.ofVirtual()
            .name("central-listener-test")
            .start(centralListener);

        logger.info("✅ Central platform infrastructure ready");
    }

    private void setupSidecar(String bootstrapServers) {
        // Create sidecar circuit
        sidecarCircuit = cortex().circuit(cortex().name("sidecar-test"));

        // Create sidecar conduits
        sidecarAgents = sidecarCircuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );
        sidecarActors = sidecarCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        // Create central communicator
        communicator = new KafkaCentralCommunicator(bootstrapServers, REQUEST_TOPIC);

        // Create coordination bridge
        coordinationBridge = new AgentCoordinationBridge(
            sidecarAgents, sidecarActors, communicator, SIDECAR_ID
        );

        // Create and start response listener
        responseListener = new SidecarResponseListener(
            bootstrapServers, RESPONSE_TOPIC, sidecarActors, SIDECAR_ID
        );
        responseListenerThread = Thread.ofVirtual()
            .name("response-listener-test")
            .start(responseListener);

        logger.info("✅ Sidecar infrastructure ready");
    }
}
