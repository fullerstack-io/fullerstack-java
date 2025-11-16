# Serventis API Migration Guide

## Overview

This document details the breaking changes in Serventis API and migration steps for fullerstack-kafka.

**Previous Version**: 1.0.0-PREVIEW (311e2cd)
**New Version**: 1.0.0-PREVIEW (ecb5f7d)
**Update Date**: 2025-11-15

### What Changed

✅ **Substrates Core API**: **NO CHANGES**
- `Substrates.java` - No modifications
- `CortexProvider.java` - No modifications
- Circuit/Conduit/Channel hierarchy - Unchanged
- All core patterns remain stable

✅ **Serventis Extension**: **UPDATES ONLY**
- 3 new instruments added (Breakers, Leases, Transactions)
- 3 existing instruments updated (Services, Probes, Agents)
- All changes are in the Serventis extension layer
- Core Substrates patterns unaffected

---

## Summary of Changes

### 1. New Instruments (3)

✨ **Breakers** - Circuit breaker state machine observation
- Signs: CLOSE, OPEN, HALF_OPEN, TRIP, PROBE, RESET
- Purpose: Observing resilience patterns and cascading failure prevention

✨ **Leases** - Time-bounded resource ownership coordination
- Signs: ACQUIRE, GRANT, DENY, RENEW, EXTEND, RELEASE, EXPIRE, REVOKE, PROBE
- Dimensions: LESSOR (authority), LESSEE (client)
- Purpose: Leader election, distributed locking, resource reservation

✨ **Transactions** - Distributed transaction coordination observation
- Signs: START, PREPARE, COMMIT, ROLLBACK, ABORT, EXPIRE, CONFLICT, COMPENSATE
- Dimensions: COORDINATOR (transaction manager), PARTICIPANT (cohort)
- Purpose: 2PC/3PC protocols, saga patterns, distributed consistency
- **Note**: Minor changes only (added `@NotNull` annotations)

### 2. Breaking API Changes (4 instruments)

#### Services API
**BREAKING**: Dimension renamed `RELEASE/RECEIPT` → `CALLER/CALLEE`

```java
// ❌ OLD
service.call(RELEASE);      // I am calling
service.success(RELEASE);   // I succeeded
service.fail(RECEIPT);      // Remote service failed

// ✅ NEW
service.call(CALLER);       // I am calling
service.success(CALLER);    // My call succeeded
service.fail(CALLEE);       // Serving request failed
```

**Rationale**: Clearer client/server perspective (caller vs callee) instead of abstract "release vs receipt"

#### Probes API
**BREAKING**: Multiple changes
1. Dimension renamed `RELEASE/RECEIPT` → `OUTBOUND/INBOUND`
2. Signs merged: `TRANSMIT` + `RECEIVE` → `TRANSFER`

```java
// ❌ OLD
probe.connect(RELEASE);     // I am connecting
probe.transmit(RELEASE);    // I am transmitting
probe.receive(RECEIPT);     // It received
probe.disconnect(RECEIPT);  // It disconnected

// ✅ NEW
probe.connect(OUTBOUND);    // Outbound connection
probe.transfer(OUTBOUND);   // Outbound data transfer
probe.transfer(INBOUND);    // Inbound data transfer
probe.disconnect(INBOUND);  // Inbound disconnection
```

**Rationale**: Direction-based model (outbound/inbound) instead of perspective, unified data transfer sign

#### Agents API
**BREAKING**: Removed convenience methods, dimension now required

```java
// ❌ OLD
agent.promise();            // I promise (implicit PROMISER)
agent.promised();           // They promised (implicit PROMISEE)
agent.fulfill();            // I fulfilled (implicit PROMISER)
agent.fulfilled();          // They fulfilled (implicit PROMISEE)

// ✅ NEW
agent.promise(PROMISER);    // I promise (explicit dimension)
agent.promise(PROMISEE);    // I observed their promise (explicit dimension)
agent.fulfill(PROMISER);    // I fulfilled (explicit dimension)
agent.fulfill(PROMISEE);    // I observed their fulfillment (explicit dimension)
```

**Rationale**: Consistent API across all dimensional instruments, explicit perspective specification

#### Transactions API
**Changes**: Dimension clarified as COORDINATOR/PARTICIPANT (was already explicit)

---

## Migration Impact Assessment

### fullerstack-kafka Usage

#### Current Usage in Codebase

**Services API** - Used in:
- ✅ `ProducerSendService.java` - Self-monitoring service calls
- ✅ `ConsumerPollService.java` - Self-monitoring poll operations

**Probes API** - **NOT CURRENTLY USED** ✅
- No impact

**Agents API** - Used in:
- ✅ `ProducerSelfRegulator.java` - Promise-based throttling
- ✅ `ConsumerSelfRegulator.java` - Promise-based pausing
- ✅ `OscillationDetector.java` - Promise breach detection

**New Instruments** - Not yet used:
- ⚠️ **Breakers** - Could be useful for Kafka client resilience
- ⚠️ **Leases** - Could be useful for consumer group coordination

---

## Migration Steps

### Step 1: Services API Migration

#### Files to Update
- `fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/services/ProducerSendService.java`
- `fullerstack-kafka-consumer/src/main/java/io/fullerstack/kafka/consumer/services/ConsumerPollService.java`

#### Changes Required

**ProducerSendService.java**:
```java
// OLD
public void send(ProducerRecord<K, V> record, Callback userCallback) {
    sendService.call();  // Implicit RELEASE
    try {
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                sendService.succeeded();  // Implicit RELEASE
            } else {
                sendService.failed();     // Implicit RELEASE
            }
            if (userCallback != null) userCallback.onCompletion(metadata, exception);
        });
    } catch (Exception e) {
        sendService.failed();
        throw e;
    }
}

// NEW - No changes needed! Methods without dimensions still work (default CALLER)
// But for clarity, we SHOULD add explicit dimensions:
public void send(ProducerRecord<K, V> record, Callback userCallback) {
    sendService.call(CALLER);  // Explicit: I am calling broker
    try {
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                sendService.succeeded(CALLER);  // My call succeeded
            } else {
                sendService.failed(CALLER);     // My call failed
            }
            if (userCallback != null) userCallback.onCompletion(metadata, exception);
        });
    } catch (Exception e) {
        sendService.failed(CALLER);
        throw e;
    }
}
```

### Step 2: Agents API Migration

#### Files to Update
- `fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/agents/ProducerSelfRegulator.java`
- `fullerstack-kafka-consumer/src/main/java/io/fullerstack/kafka/consumer/agents/ConsumerSelfRegulator.java`
- `fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/core/meta/OscillationDetector.java`

#### Changes Required

**ProducerSelfRegulator.java**:
```java
// OLD
private void throttleProducer(int targetMaxInFlight, String reason) {
    Agent agent = agents.channel(cortex.name("agent.producer.throttle"));

    agent.promise();  // I promise to throttle
    try {
        int previousLimit = currentMaxInFlight.getAndSet(targetMaxInFlight);
        throttled.set(true);
        agent.fulfill();  // I kept my promise
    } catch (Exception e) {
        agent.breach();   // I failed my promise
        throw e;
    }
}

// NEW
private void throttleProducer(int targetMaxInFlight, String reason) {
    Agent agent = agents.percept(cortex().name("agent.producer.throttle"));

    agent.promise(PROMISER);  // Explicit: I promise to throttle
    try {
        int previousLimit = currentMaxInFlight.getAndSet(targetMaxInFlight);
        throttled.set(true);
        agent.fulfill(PROMISER);  // Explicit: I kept my promise
    } catch (Exception e) {
        agent.breach(PROMISER);   // Explicit: I failed my promise
        throw e;
    }
}
```

### Step 3: Rebuild and Test

1. Update Substrates API dependency
2. Rebuild fullerstack-substrates implementation
3. Update fullerstack-kafka dependencies
4. Fix compilation errors
5. Run full test suite (281 tests)
6. Verify signal flow correctness

---

## New Opportunities

### Breakers API Integration

**Use Case**: Kafka Producer Circuit Breaker

```java
// When broker connection fails repeatedly
public class BrokerCircuitBreaker {
    private final Breakers.Breaker breaker;

    public void onConnectionFailure() {
        if (consecutiveFailures >= threshold) {
            breaker.trip();   // Circuit breaking now
            breaker.open();   // Block further attempts
        }
    }

    public void onRecoveryAttempt() {
        breaker.halfOpen();  // Testing recovery
        breaker.probe();     // Send test request
    }

    public void onRecoverySuccess() {
        breaker.close();     // Resume normal operation
    }
}
```

### Leases API Integration

**Use Case**: Consumer Group Coordinator Lease

```java
// Leader election via lease
public class ConsumerGroupCoordinator {
    private final Leases.Lease coordinatorLease;

    public void electLeader() {
        coordinatorLease.acquire(LESSEE);   // Request leadership
        // If granted by broker:
        coordinatorLease.grant(LESSOR);     // Became leader

        // Maintain leadership
        coordinatorLease.renew(LESSEE);     // Heartbeat
        coordinatorLease.extend(LESSOR);    // TTL extended
    }

    public void onRebalance() {
        coordinatorLease.release(LESSEE);   // Step down voluntarily
    }

    public void onSessionTimeout() {
        coordinatorLease.expire(LESSOR);    // Lease expired
    }
}
```

### Transactions API Integration

**Use Case**: Kafka Transactions (Exactly-Once Semantics)

```java
// Observing Kafka transactional producer
public class TransactionalProducerObserver {
    private final Transactions.Transaction transaction;

    public void beginTransaction(String transactionId) {
        transaction.start(COORDINATOR);     // Producer starts transaction
    }

    public void sendMessages(List<ProducerRecord> records) {
        // Sending messages within transaction
        // (transaction in progress)
    }

    public void commitTransaction() {
        transaction.prepare(COORDINATOR);   // Prepare phase
        transaction.commit(COORDINATOR);    // Commit all messages atomically
    }

    public void abortTransaction(Exception error) {
        transaction.abort(COORDINATOR);     // Rollback on error
        transaction.rollback(COORDINATOR);  // Transaction rolled back
    }

    public void onTransactionTimeout() {
        transaction.expire(COORDINATOR);    // Transaction timed out
        transaction.rollback(COORDINATOR);  // Auto-rollback
    }
}
```

**Use Case**: Consumer Group Rebalance Coordination (2PC pattern)

```java
// Group coordinator orchestrating rebalance
public class RebalanceCoordinator {
    private final Transactions.Transaction rebalanceTxn;

    public void initiateRebalance() {
        rebalanceTxn.start(COORDINATOR);        // Start rebalance transaction
    }

    public void preparePhase() {
        // Ask all consumers: can you stop processing?
        rebalanceTxn.prepare(COORDINATOR);

        // Consumers vote
        consumer1.prepared(PARTICIPANT);        // Consumer 1: yes
        consumer2.prepared(PARTICIPANT);        // Consumer 2: yes
    }

    public void commitPhase() {
        // All consumers ready
        rebalanceTxn.commit(COORDINATOR);       // Commit partition reassignment

        // Consumers apply new assignment
        consumer1.committed(PARTICIPANT);
        consumer2.committed(PARTICIPANT);
    }

    public void handleTimeout() {
        rebalanceTxn.expire(COORDINATOR);       // Consumer didn't respond
        rebalanceTxn.rollback(COORDINATOR);     // Abort rebalance
    }
}
```

---

## Migration Checklist

### Phase 1: Dependency Updates
- [ ] Pull latest substrates-api-java (ecb5f7d)
- [ ] Rebuild substrates-api-java: `mvn clean install`
- [ ] Pull latest fullerstack-substrates
- [ ] Rebuild fullerstack-substrates: `mvn clean install`

### Phase 2: Code Updates
- [ ] Update Services API calls (ProducerSendService, ConsumerPollService)
- [ ] Update Agents API calls (ProducerSelfRegulator, ConsumerSelfRegulator, OscillationDetector)
- [ ] Add import statements for CALLER, PROMISER, PROMISEE dimensions
- [ ] Update javadoc comments to reflect new semantics

### Phase 3: Testing
- [ ] Compile fullerstack-kafka: `mvn clean compile`
- [ ] Run unit tests: `mvn test`
- [ ] Verify 281/281 tests pass
- [ ] Manual testing of signal flow

### Phase 4: Optional Enhancements
- [ ] Consider Breakers API for producer resilience
- [ ] Consider Leases API for consumer group coordination
- [ ] Update architecture docs with new capabilities

---

## Risk Assessment

### Low Risk Changes
✅ **Services API**: Dimension rename is straightforward, semantic equivalence maintained
✅ **Agents API**: Explicit dimensions add clarity, no semantic change

### Medium Risk Changes
⚠️ **Probes API**: Not currently used, but TRANSMIT/RECEIVE → TRANSFER is semantic merge

### High Risk Changes
❌ **None** - All changes are additive or clarifying

### Overall Risk: **LOW** ✅

---

## Timeline Estimate

- **Phase 1** (Dependencies): 15-30 minutes
- **Phase 2** (Code Updates): 1-2 hours
- **Phase 3** (Testing): 30-60 minutes
- **Phase 4** (Optional): 2-4 hours

**Total**: 4-7 hours for complete migration

---

## References

- Substrates API: `/workspaces/substrates-api-java`
- fullerstack-substrates: `/workspaces/fullerstack-humainary/fullerstack-substrates`
- Serventis README: `/workspaces/substrates-api-java/ext/serventis/README.md`
- Migration commit: ecb5f7d
