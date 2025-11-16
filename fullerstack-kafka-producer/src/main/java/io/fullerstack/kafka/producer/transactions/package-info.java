/**
 * Transaction observers for Kafka producer exactly-once semantics.
 * <p>
 * This package implements distributed transaction observability for Kafka producer
 * using the Transactions API (Serventis PREVIEW). Transactions track the lifecycle
 * of exactly-once producer operations including initialization, commit, and abort patterns.
 * <p>
 * <b>Observers in this package:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.transactions.ProducerTransactionObserver} -
 *       Tracks producer transaction lifecycle for exactly-once semantics</li>
 * </ul>
 *
 * <h3>Transaction Model:</h3>
 * <pre>
 * Dual-Dimension:
 * - COORDINATOR: Kafka broker perspective (transaction coordinator)
 * - PARTICIPANT: Producer perspective (application client)
 *
 * Lifecycle (Exactly-Once Semantics):
 * Initiation:  START (participant) [producer.beginTransaction()]
 *                  ↓
 * Writing:     [producer.send(...)] (multiple records)
 *                  ↓
 * Prepare:     PREPARE (participant) [producer.flush()]
 *                  ↓
 * Commit:      COMMIT (participant) [producer.commitTransaction()]
 *          OR: ABORT (participant)  [producer.abortTransaction()]
 *
 * Error Conditions:
 * - EXPIRE: transaction.timeout.ms exceeded
 * - CONFLICT: Fenced by newer producer instance
 * - ABORT: Explicit abort or error during transaction
 * </pre>
 *
 * <h3>Pure Reactive Design:</h3>
 * <pre>
 * Observers emit signals in response to Kafka producer API calls:
 * - producer.beginTransaction() → START
 * - producer.send(...) → (records buffered)
 * - producer.flush() → PREPARE
 * - producer.commitTransaction() → COMMIT
 * - producer.abortTransaction() → ABORT
 * - (timeout or fence) → EXPIRE/CONFLICT
 *
 * NO schedulers or timers - Kafka producer handles transaction state internally.
 * Observers simply report what Kafka does using transaction semantics.
 * </pre>
 *
 * <h3>Integration with OODA Loop:</h3>
 * <pre>
 * Layer 1 (OBSERVE):  Probes → Track producer send operations
 * Layer 2 (ORIENT):   Monitors → Assess DEGRADED when txn fails
 * Layer 2.5:          Transactions → Track txn lifecycle (NEW)
 * Layer 4 (ACT):      Agents → Restart producer, fence old instance
 * </pre>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li><b>Exactly-Once Tracking</b>: Which transactions succeeded vs. aborted?</li>
 *   <li><b>Timeout Detection</b>: How many transactions exceeded transaction.timeout.ms?</li>
 *   <li><b>Fencing Observability</b>: When did producer get fenced by newer instance?</li>
 *   <li><b>Commit Latency</b>: How long does commitTransaction() take?</li>
 * </ul>
 *
 * @author Fullerstack
 * @since 1.0.0
 */
package io.fullerstack.kafka.producer.transactions;
