package io.fullerstack.kafka.demo.scenarios;

import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import io.fullerstack.kafka.producer.sidecar.InformMessage;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;

import java.time.Instant;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Scenario 2: Notable Event Report (Level 2)
 *
 * Agent makes promise → Agent fulfills → Sends REPORT to central
 * This is the 0.9% case - audit trail for compliance.
 */
public class Scenario2Report {

    public static void main(String[] args) throws java.lang.Exception {
        String sidecarId = "producer-sidecar-1";
        String kafkaBootstrap = "localhost:9092";
        String requestTopic = "observability.speech-acts";

        System.out.println("\n[SIDECAR] Creating circuit...");

        Circuit circuit = cortex().circuit(cortex().name("scenario-2"));

        Conduit<Agents.Agent, Agents.Signal> agents = circuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );

        Conduit<Actors.Actor, Actors.Sign> actors = circuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        System.out.println("[SIDECAR] ✅ Circuit created with Agents and Actors conduits");

        // Subscribe to agent signals
        agents.subscribe(cortex().subscriber(
            cortex().name("observer"),
            (subject, registrar) -> {
                registrar.register(signal -> {
                    String agentName = subject.name().toString();

                    switch (signal.sign()) {
                        case PROMISE -> System.out.println(
                            "[AGENT] 📝 " + agentName + " PROMISED to self-regulate"
                        );
                        case FULFILL -> System.out.println(
                            "[AGENT] ✅ " + agentName + " FULFILLED promise"
                        );
                    }
                });
            }
        ));

        // Subscribe to actor signals
        actors.subscribe(cortex().subscriber(
            cortex().name("actor-observer"),
            (subject, registrar) -> {
                registrar.register(sign -> {
                    String actorName = subject.name().toString();

                    if (sign == Actors.Sign.REPORT) {
                        System.out.println(
                            "[ACTOR] 📊 " + actorName + " sent REPORT to central (audit trail)"
                        );
                    }
                });
            }
        ));

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 1: Agent makes PROMISE and FULFILLS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Agents.Agent bufferAgent = agents.percept(cortex().name("buffer-regulator"));
        bufferAgent.promise(Agents.Dimension.PROMISER);

        circuit.await();
        Thread.sleep(1000);

        System.out.println("\n(Agent performs self-regulation successfully)");
        Thread.sleep(500);

        bufferAgent.fulfill(Agents.Dimension.PROMISER);

        circuit.await();
        Thread.sleep(500);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2: Send REPORT to central (audit trail)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("[SIDECAR] 📊 Notable event detected - sending REPORT");

        // Emit REPORT speech act
        Actors.Actor reporter = actors.percept(cortex().name(sidecarId + ".reporter"));
        reporter.report();

        circuit.await();

        // Send via Kafka
        try (KafkaCentralCommunicator communicator = new KafkaCentralCommunicator(
            kafkaBootstrap, requestTopic
        )) {
            communicator.sendInform(new InformMessage(
                sidecarId,
                "buffer-regulator",
                "Self-regulation successful - buffer pressure resolved (95% → 75%)",
                Instant.now().toEpochMilli()
            ));

            System.out.println("[KAFKA] ✅ REPORT message sent to observability.speech-acts");
        }

        Thread.sleep(1000);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Result: ✅ SUCCESS with Audit Trail");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Network Traffic: 1 REPORT message (~200 bytes)");
        System.out.println("Messages Sent: 1");
        System.out.println("Purpose: Audit trail for compliance");
        System.out.println();
        System.out.println("Central platform will receive REPORT and log it.");
        System.out.println();

        circuit.close();
    }
}
