/**
 * Configuration and setup for producer monitoring infrastructure.
 * <p>
 * <b>Key Component:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.config.ProducerCircuitSetup} - Manages Circuit and dynamic Cell hierarchy for producers</li>
 * </ul>
 * <p>
 * <b>Cell Hierarchy:</b>
 * <pre>
 * cluster.producers (root Cell)
 * ├── producer-1 (dynamic Cell)
 * ├── producer-2 (dynamic Cell)
 * └── producer-3 (dynamic Cell)
 * </pre>
 * <p>
 * Unlike brokers which are known upfront, producers are discovered dynamically.
 * Each producer gets its own Cell created on-demand via {@code getProducerCell(producerId)}.
 * <p>
 * <b>Hierarchical Subscriptions:</b>
 * Subscribe to the root Cell to receive signals from ALL producers:
 * <pre>{@code
 * setup.getProducerRootCell().subscribe(subscriber);
 * }</pre>
 *
 * @see io.fullerstack.kafka.producer.config.ProducerCircuitSetup
 */
package io.fullerstack.kafka.producer.config;
