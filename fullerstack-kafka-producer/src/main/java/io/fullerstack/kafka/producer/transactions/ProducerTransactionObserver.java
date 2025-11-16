package io.fullerstack.kafka.producer.transactions;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Transactions;
import io.humainary.substrates.ext.serventis.ext.Transactions.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Observes Kafka producer transaction lifecycle for exactly-once semantics.
 * <p>
 * This observer tracks producer transaction operations using the Transactions API
 * (Serventis PREVIEW). Each producer transaction is modeled as a distributed transaction where:
 * <ul>
 *   <li>The producer is the PARTICIPANT (transaction client)</li>
 *   <li>The Kafka broker transaction coordinator is the COORDINATOR (transaction authority)</li>
 * </ul>
 *
 * <h3>Producer Transaction Lifecycle:</h3>
 * <pre>
 * Initiation:  Producer → START (participant) [beginTransaction()]
 *                    ↓
 * Writing:     Producer → [send() multiple records to buffer]
 *                    ↓
 * Prepare:     Producer → PREPARE (participant) [flush() - durably write]
 *                    ↓
 * Commit:      Producer → COMMIT (participant) [commitTransaction()]
 *                    ↓
 * Resolution:  Transaction successfully committed
 *
 * Alternative Termination:
 *   Abort:       Producer → ABORT (participant) [abortTransaction()]
 *   Timeout:     Producer → EXPIRE (participant) [transaction.timeout.ms]
 *   Fence:       Producer → CONFLICT (participant) [newer instance fenced this one]
 * </pre>
 *
 * <h3>Dual-Dimension Model:</h3>
 * <pre>
 * COORDINATOR (Kafka Broker):  (Not directly observed by producer)
 * PARTICIPANT (Producer):      START, PREPARE, COMMIT, ABORT, EXPIRE, CONFLICT
 * </pre>
 * <p>
 * Note: Producers emit signals from PARTICIPANT perspective only. Coordinator signals
 * would be emitted by Kafka broker internals if instrumented.
 *
 * <h3>Integration with Kafka Producer API:</h3>
 * <pre>{@code
 * // In your producer code:
 * ProducerTransactionObserver observer = new ProducerTransactionObserver(
 *     circuit,
 *     "producer-1"
 * );
 *
 * try {
 *     producer.beginTransaction();
 *     observer.onTransactionBegin();
 *
 *     // Send records
 *     producer.send(record1);
 *     producer.send(record2);
 *
 *     // Flush to ensure durability before commit
 *     producer.flush();
 *     observer.onTransactionPrepare();
 *
 *     // Commit transaction
 *     producer.commitTransaction();
 *     observer.onTransactionCommit();
 *
 * } catch (ProducerFencedException e) {
 *     observer.onTransactionFenced();
 *     producer.close();
 *
 * } catch (TimeoutException e) {
 *     observer.onTransactionExpired();
 *     producer.abortTransaction();
 *     observer.onTransactionAbort();
 *
 * } catch (Exception e) {
 *     producer.abortTransaction();
 *     observer.onTransactionAbort();
 * }
 * }</pre>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("producer.monitoring"));
 *
 * ProducerTransactionObserver observer = new ProducerTransactionObserver(
 *     circuit,
 *     "producer-1"
 * );
 *
 * // Transactions signals now emitted for producer transaction lifecycle
 * Conduit<Transaction, Transactions.Signal> transactions = observer.transactions();
 * transactions.subscribe(...);
 *
 * // Later...
 * observer.close();
 * }</pre>
 *
 * @author Fullerstack
 * @see Transactions
 */
public class ProducerTransactionObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerTransactionObserver.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final String producerId;
    private final Conduit<Transaction, Transactions.Signal> transactions;
    private final Transaction producerTransaction;

    /**
     * Creates a new producer transaction observer.
     *
     * @param circuit     Circuit for creating transactions conduit
     * @param producerId  Producer identifier (e.g., "producer-1")
     */
    public ProducerTransactionObserver(
        Circuit circuit,
        String producerId
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.producerId = Objects.requireNonNull(producerId, "producerId cannot be null");

        // Create Transactions conduit
        this.transactions = circuit.conduit(
            cortex.name("transactions"),
            Transactions::composer
        );

        // Get Transaction for this producer
        this.producerTransaction = transactions.percept(cortex.name(producerId + ".transaction"));

        logger.info("[TRANSACTIONS] ProducerTransactionObserver created for {} (pure reactive)",
            producerId);
    }

    /**
     * Called when producer begins a new transaction.
     * <p>
     * Triggered by: producer.beginTransaction()
     */
    public void onTransactionBegin() {
        // PARTICIPANT perspective: Producer starting transaction
        producerTransaction.start(Transactions.Dimension.PARTICIPANT);

        logger.info("[TRANSACTIONS] {} began transaction (START/PARTICIPANT)", producerId);
    }

    /**
     * Called when producer prepares transaction for commit (flush).
     * <p>
     * Triggered by: producer.flush() before commitTransaction()
     * <p>
     * In Kafka, flush() ensures all buffered records are durably written to the broker
     * before committing the transaction. This is analogous to the PREPARE phase in 2PC.
     */
    public void onTransactionPrepare() {
        // PARTICIPANT perspective: Producer flushing records (voting yes for commit)
        producerTransaction.prepare(Transactions.Dimension.PARTICIPANT);

        logger.info("[TRANSACTIONS] {} prepared transaction (PREPARE/PARTICIPANT)", producerId);
    }

    /**
     * Called when producer commits the transaction.
     * <p>
     * Triggered by: producer.commitTransaction()
     */
    public void onTransactionCommit() {
        // PARTICIPANT perspective: Producer committing transaction
        producerTransaction.commit(Transactions.Dimension.PARTICIPANT);

        logger.info("[TRANSACTIONS] {} committed transaction (COMMIT/PARTICIPANT)", producerId);
    }

    /**
     * Called when producer aborts the transaction.
     * <p>
     * Triggered by: producer.abortTransaction()
     */
    public void onTransactionAbort() {
        // PARTICIPANT perspective: Producer aborting transaction
        producerTransaction.abort(Transactions.Dimension.PARTICIPANT);

        logger.warn("[TRANSACTIONS] {} aborted transaction (ABORT/PARTICIPANT)", producerId);
    }

    /**
     * Called when transaction expires (transaction.timeout.ms exceeded).
     * <p>
     * Triggered by: Kafka broker detects transaction timeout
     * <p>
     * This is called externally when your monitoring detects transaction expiration
     * (e.g., TimeoutException from commitTransaction()).
     */
    public void onTransactionExpired() {
        // PARTICIPANT perspective: Producer detected transaction expiration
        producerTransaction.expire(Transactions.Dimension.PARTICIPANT);

        logger.error("[TRANSACTIONS] {} transaction expired (EXPIRE/PARTICIPANT)", producerId);
    }

    /**
     * Called when producer is fenced by a newer instance.
     * <p>
     * Triggered by: ProducerFencedException during transaction operations
     * <p>
     * In Kafka exactly-once semantics, only one producer instance with a given
     * transactional.id can be active. A newer instance fences the old one.
     */
    public void onTransactionFenced() {
        // PARTICIPANT perspective: Producer detected fencing (conflict with newer instance)
        producerTransaction.conflict(Transactions.Dimension.PARTICIPANT);

        logger.error("[TRANSACTIONS] {} fenced by newer instance (CONFLICT/PARTICIPANT)", producerId);
    }

    /**
     * Called when transaction encounters a compensating action (saga pattern).
     * <p>
     * Triggered by: Application-level compensation logic
     * <p>
     * This is less common in Kafka producer transactions (which use 2PC), but included
     * for saga-pattern integration where producer transactions are part of a larger saga.
     */
    public void onTransactionCompensate() {
        // PARTICIPANT perspective: Producer executing compensating action
        producerTransaction.compensate(Transactions.Dimension.PARTICIPANT);

        logger.warn("[TRANSACTIONS] {} compensating transaction (COMPENSATE/PARTICIPANT)", producerId);
    }

    /**
     * Gets the transactions conduit for subscribing to transaction signals.
     *
     * @return Transactions conduit
     */
    public Conduit<Transaction, Transactions.Signal> transactions() {
        return transactions;
    }

    @Override
    public void close() {
        logger.info("[TRANSACTIONS] Shutting down ProducerTransactionObserver for {}", producerId);

        // No scheduler to shutdown - pure reactive observer

        logger.info("[TRANSACTIONS] ProducerTransactionObserver stopped for {}", producerId);
    }
}