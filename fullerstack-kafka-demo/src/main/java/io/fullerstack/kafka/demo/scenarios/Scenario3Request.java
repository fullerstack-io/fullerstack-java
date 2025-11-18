package io.fullerstack.kafka.demo.scenarios;

import io.fullerstack.kafka.producer.sidecar.AgentCoordinationBridge;
import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import io.fullerstack.kafka.producer.sidecar.SidecarResponseListener;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Scenario 3: Request Help from Central (Level 3)
 *
 * Agent makes promise → Agent BREACHES → Full conversation with central
 * This is the 0.1% case - full Speech Act Theory coordination.
 *
 * Flow: REQUEST → ACKNOWLEDGE → PROMISE/DENY → DELIVER
 */
public class Scenario3Request {

    public static void main(String[] args) throws java.lang.Exception {
        String sidecarId = "producer-sidecar-1";
        String kafkaBootstrap = "localhost:9092";
        String requestTopic = "observability.speech-acts";
        String responseTopic = "observability.responses";

        System.out.println("\n[SIDECAR] Creating circuit and conduits...");

        Circuit circuit = cortex().circuit(cortex().name("scenario-3"));

        Conduit<Agents.Agent, Agents.Signal> agents = circuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );

        Conduit<Actors.Actor, Actors.Sign> actors = circuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        System.out.println("[SIDECAR] ✅ Circuit created");

        // Create central communicator
        KafkaCentralCommunicator communicator = new KafkaCentralCommunicator(
            kafkaBootstrap, requestTopic
        );

        // Create coordination bridge (this handles the Promise Theory + Speech Act Theory)
        AgentCoordinationBridge bridge = new AgentCoordinationBridge(
            agents, actors, communicator, sidecarId
        );

        // Create response listener
        SidecarResponseListener responseListener = new SidecarResponseListener(
            kafkaBootstrap, responseTopic, actors, sidecarId
        );

        Thread responseThread = Thread.ofVirtual()
            .name("response-listener")
            .start(responseListener);

        System.out.println("[SIDECAR] ✅ Coordination bridge and response listener created");

        Thread.sleep(2000);  // Let response listener start

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 1: Agent makes PROMISE");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Agents.Agent bufferAgent = agents.percept(cortex().name("buffer-regulator"));
        bufferAgent.promise(Agents.Dimension.PROMISER);

        circuit.await();
        Thread.sleep(1000);

        System.out.println("\n(Agent attempts local throttling...)");
        System.out.println("(Reducing max.in.flight.requests from 5 → 2)");
        Thread.sleep(1000);
        System.out.println("(⚠️  Buffer still at 95% - throttling didn't work!)");
        Thread.sleep(1000);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2: Agent BREACHES promise");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("[AGENT] ❌ Local self-regulation FAILED");

        bufferAgent.breach(Agents.Dimension.PROMISER);

        circuit.await();

        System.out.println("\n[BRIDGE] Agent breached → Triggering REQUEST to central");

        Thread.sleep(2000);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 3: Waiting for central response...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        System.out.println("Expected flow:");
        System.out.println("  1. Central receives REQUEST");
        System.out.println("  2. Central sends ACKNOWLEDGE");
        System.out.println("  3. Central processes request");
        System.out.println("  4. Central sends PROMISE or DENY");
        System.out.println("  5. Central takes action (if approved)");
        System.out.println("  6. Central sends DELIVER");
        System.out.println();

        // Wait for responses
        System.out.println("Waiting 10 seconds for conversation to complete...\n");
        Thread.sleep(10000);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Result: Conversation Complete");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Check the logs above for:");
        System.out.println("  - [ACTOR] Response from central: ACKNOWLEDGE");
        System.out.println("  - [ACTOR] Response from central: PROMISE/DENY");
        System.out.println("  - [ACTOR] Response from central: DELIVER (if successful)");
        System.out.println();
        System.out.println("This demonstrates full Speech Act Theory coordination!");
        System.out.println();

        // Cleanup
        responseListener.close();
        responseThread.interrupt();
        bridge.close();
        communicator.close();
        circuit.close();
    }
}
