package io.fullerstack.kafka.demo.scenarios;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Scenario 1: Silent Self-Regulation (Level 1)
 *
 * Agent makes promise → Agent fulfills → No communication
 * This is the 99% case - everything handled locally.
 */
public class Scenario1Silent {

    public static void main(String[] args) throws java.lang.Exception {
        System.out.println("\n[SIDECAR] Creating circuit and agents conduit...");

        Circuit circuit = cortex().circuit(cortex().name("scenario-1"));
        Conduit<Agents.Agent, Agents.Signal> agents = circuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );

        System.out.println("[SIDECAR] ✅ Circuit and conduit created");

        // Subscribe to agent signals to show what's happening
        agents.subscribe(cortex().subscriber(
            cortex().name("observer"),
            (subject, registrar) -> {
                registrar.register(signal -> {
                    String agentName = subject.name().toString();

                    switch (signal.sign()) {
                        case PROMISE -> System.out.println(
                            "[AGENT] 📝 " + agentName + " PROMISED to self-regulate buffer"
                        );
                        case FULFILL -> System.out.println(
                            "[AGENT] ✅ " + agentName + " FULFILLED promise - Silent success!"
                        );
                        case BREACH -> System.out.println(
                            "[AGENT] ❌ " + agentName + " BREACHED promise"
                        );
                    }
                });
            }
        ));

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 1: Agent makes PROMISE");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Agents.Agent bufferAgent = agents.percept(cortex().name("buffer-regulator"));
        bufferAgent.promise(Agents.Dimension.PROMISER);

        circuit.await();  // Wait for signal to propagate
        Thread.sleep(1000);

        System.out.println("\n(Agent attempts local throttling...)");
        System.out.println("(Reducing max.in.flight.requests from 5 → 2)");
        System.out.println("(Buffer utilization: 95% → 75% ✅)");
        Thread.sleep(1000);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2: Agent FULFILLS promise");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        bufferAgent.fulfill(Agents.Dimension.PROMISER);

        circuit.await();
        Thread.sleep(500);

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Result: ✅ SUCCESS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Network Traffic: ZERO bytes");
        System.out.println("Messages Sent: 0");
        System.out.println("Agent handled everything locally!");
        System.out.println();

        circuit.close();
    }
}
