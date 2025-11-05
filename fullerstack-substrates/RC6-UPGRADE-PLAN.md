# Substrates RC6 Upgrade Plan

**Status:** Planning
**Target:** Humainary Substrates API 1.0.0-RC6
**Current:** Humainary Substrates API 1.0.0-RC5

## Executive Summary

Upgrade fullerstack-substrates to support Humainary Substrates RC6 with comprehensive compliance testing and demonstration examples for all 12 Serventis instrument APIs.

---

## New Serventis APIs (RC6)

Three new instrument APIs added in RC6:

### 1. Agents API (Promise Theory)
**Purpose:** Autonomous agent coordination via promises (not commands)
**Signs (20):** OFFER/OFFERED, PROMISE/PROMISED, ACCEPT/ACCEPTED, FULFILL/FULFILLED, BREACH/BREACHED, RETRACT/RETRACTED, INQUIRE/INQUIRED, OBSERVE/OBSERVED, DEPEND/DEPENDED, VALIDATE/VALIDATED
**Key Concept:** Voluntary cooperation, agents promise their own behavior
**OODA Phase:** ACT (autonomous coordination)

**Example:**
```java
Agent scaler = agents.get(cortex().name("auto-scaler"));
scaler.offer();      // OUTBOUND: Advertise scaling capability
scaler.promise();    // OUTBOUND: Commit to scaling
scaler.fulfill();    // OUTBOUND: Keep promise
```

### 2. Actors API (Speech Acts)
**Purpose:** Conversational agent communication observability
**Signs (11):** ASK, ASSERT, EXPLAIN, REPORT, REQUEST, COMMAND, ACKNOWLEDGE, DENY, CLARIFY, PROMISE, DELIVER
**Key Concept:** Speech act theory for human/AI dialogue
**OODA Phase:** ACT (communicative coordination)

**Example:**
```java
Actor human = actors.get(cortex().name("user.william"));
Actor ai = actors.get(cortex().name("assistant.claude"));
human.ask();         // Question
ai.explain();        // Response
human.acknowledge(); // Confirmation
```

### 3. Routers API (Packet Routing)
**Purpose:** Network packet routing observability
**Signs (9):** SEND, RECEIVE, FORWARD, ROUTE, DROP, FRAGMENT, REASSEMBLE, CORRUPT, REORDER
**Key Concept:** Discrete routing events for traffic analysis
**OODA Phase:** OBSERVE (network traffic patterns)

**Example:**
```java
Router router = routers.get(cortex().name("router-1"));
router.receive();    // Packet arrived
router.route();      // Routing decision made
router.forward();    // Packet forwarded to next hop
```

---

## Complete Serventis API Suite (12 Total)

### OBSERVE Phase (6 APIs)
1. **Probes** - Communication outcomes (CONNECT, SEND, RECEIVE, PROCESS, CLOSE)
2. **Services** - Interaction lifecycle (CALL, SUCCESS, FAIL, RETRY, TIMEOUT, etc.)
3. **Queues** - Flow control (ENQUEUE, DEQUEUE, OVERFLOW, UNDERFLOW)
4. **Gauges** - Bidirectional metrics (INCREMENT, DECREMENT, OVERFLOW, UNDERFLOW, RESET)
5. **Counters** - Monotonic metrics (INCREMENT, OVERFLOW, UNDERFLOW, RESET)
6. **Caches** - Hit/miss tracking (LOOKUP, HIT, MISS, STORE, EVICT, EXPIRE, REMOVE)
7. **Routers** - Packet routing (SEND, RECEIVE, FORWARD, ROUTE, DROP, etc.) ← NEW

### ORIENT Phase (2 APIs)
8. **Monitors** - Condition assessment (STABLE, DEGRADED, DEFECTIVE, DOWN, etc.)
9. **Resources** - Capacity tracking (ATTEMPT, ACQUIRE, GRANT, DENY, TIMEOUT, RELEASE)

### DECIDE Phase (1 API)
10. **Reporters** - Situation urgency (NORMAL, WARNING, CRITICAL)

### ACT Phase (2 APIs) ← NEW
11. **Agents** - Promise-based coordination (OFFER, PROMISE, FULFILL, etc.) ← NEW
12. **Actors** - Speech act communication (ASK, EXPLAIN, REQUEST, DELIVER, etc.) ← NEW

---

## Upgrade Process

### Phase 1: Dependency Update

1. Update `pom.xml` to RC6:
   ```xml
   <substrates.api.version>1.0.0-RC6</substrates.api.version>
   <serventis.api.version>1.0.0-RC6</serventis.api.version>
   ```

2. Verify no breaking API changes in core Substrates
3. Run existing test suite to catch regressions

### Phase 2: Substrates RC6 Compliance Tests

Location: `src/test/java/io/fullerstack/substrates/compliance/`

**Existing RC5 Compliance Tests:**
- ✅ `CellBehaviorComplianceTest.java` - Cell API compliance
- ✅ `ConduitBehaviorComplianceTest.java` - Conduit API compliance
- ✅ `FlowBehaviorComplianceTest.java` - Flow API compliance
- ✅ `PoolBehaviorComplianceTest.java` - Pool API compliance
- ✅ `StateSlotBehaviorComplianceTest.java` - State Slot API compliance

**New RC6 Compliance Tests Needed:**
- `CircuitAwaitComplianceTest.java` - Verify circuit.await() semantics
- `SubscriberRegistrarComplianceTest.java` - Subscriber/Registrar patterns
- `ComposerComplianceTest.java` - Composer transformation compliance
- `ScopeResourceManagementComplianceTest.java` - Scope lifecycle compliance

### Phase 3: Serventis API Demonstration Tests

Location: `src/test/java/io/fullerstack/substrates/serventis/`

**Purpose:**
- Demonstrate correct usage of each Serventis API
- Show idiomatic patterns for each instrument
- Serve as living documentation

**Individual API Tests (12 files):**

1. `ProbesApiDemoTest.java`
   - Communication outcome reporting
   - Client/server perspective patterns
   - Success/failure observation

2. `ServicesApiDemoTest.java`
   - Service lifecycle (CALL → SUCCESS/FAIL → STOP)
   - Execute/dispatch convenience methods
   - Retry and timeout patterns

3. `QueuesApiDemoTest.java`
   - Producer/consumer flow control
   - Overflow/underflow detection
   - Backpressure patterns

4. `GaugesApiDemoTest.java`
   - Bidirectional metric tracking
   - Overflow/underflow boundary events
   - Reset semantics

5. `CountersApiDemoTest.java`
   - Monotonic increment patterns
   - Overflow wrapping detection
   - Reset vs. natural rollover

6. `CachesApiDemoTest.java`
   - Hit/miss lifecycle
   - Eviction vs. expiration vs. removal
   - Lookup-store patterns

7. `RoutersApiDemoTest.java` ← NEW
   - Packet routing lifecycle
   - Send/receive/forward patterns
   - Drop/corrupt/fragment scenarios

8. `MonitorsApiDemoTest.java`
   - Condition assessment patterns
   - Confidence levels (TENTATIVE, MEASURED, CONFIRMED)
   - All 7 conditions (STABLE, CONVERGING, DIVERGING, ERRATIC, DEGRADED, DEFECTIVE, DOWN)

9. `ResourcesApiDemoTest.java`
   - Attempt vs. acquire semantics
   - Grant/deny capacity patterns
   - Timeout handling

10. `ReportersApiDemoTest.java`
    - Situation urgency assessment
    - Normal/warning/critical escalation
    - Aggregation from multiple monitors

11. `AgentsApiDemoTest.java` ← NEW
    - Promise lifecycle (OFFER → PROMISE → ACCEPT → FULFILL)
    - Breach and retraction patterns
    - Outbound vs. inbound perspectives
    - Dependency management

12. `ActorsApiDemoTest.java` ← NEW
    - Speech act patterns
    - Question-answer cycles
    - Request-delivery workflows
    - Correction and clarification

**Test Structure Pattern:**
```java
@Test
@DisplayName("Queue overflow signals backpressure")
void queueOverflow() {
    Circuit circuit = cortex().circuit(cortex().name("test"));

    Conduit<Queue, Queues.Signal> queues = circuit.conduit(
        cortex().name("queues"),
        Queues::composer
    );

    Queue producerBuffer = queues.get(cortex().name("producer-1.buffer"));

    AtomicReference<Queues.Signal> captured = new AtomicReference<>();
    queues.subscribe(cortex().subscriber(
        cortex().name("observer"),
        (subject, registrar) -> registrar.register(captured::set)
    ));

    // ACT
    producerBuffer.overflow();
    circuit.await();

    // ASSERT
    assertThat(captured.get()).isEqualTo(Queues.Signal.OVERFLOW);
}
```

### Phase 4: OODA Loop Integration Tests

Location: `src/test/java/io/fullerstack/substrates/integration/`

**Complete OODA Cycle Test:**
`OODALoopIntegrationTest.java`

Demonstrates signal flow across all phases:

```
OBSERVE → ORIENT → DECIDE → ACT
```

**Scenario: Kafka Consumer Lag Remediation**

```java
@Test
@DisplayName("Complete OODA loop: Detect lag → Assess → Escalate → Auto-scale")
void kafkaConsumerLagRemediation() {
    // OBSERVE: Queue overflow detected
    Queue consumerLag = queues.get(cortex().name("consumer-1.lag"));
    consumerLag.overflow();  // Raw signal

    // ORIENT: Monitor assesses condition
    // (via subscriber) → monitor.defective(HIGH)

    // DECIDE: Reporter escalates urgency
    // (via subscriber) → reporter.critical()

    // ACT: Agent auto-scales
    // (via subscriber) → scaler.promise() → scaler.fulfill()

    circuit.await();

    // Verify signal progression through all layers
    assertThat(observedSignals).containsSequence(
        "Queue.OVERFLOW",
        "Monitor.DEFECTIVE",
        "Reporter.CRITICAL",
        "Agent.PROMISE",
        "Agent.FULFILL"
    );
}
```

**Additional Integration Tests:**

1. `SemioticObservabilityIntegrationTest.java`
   - Context creates meaning (same signal, different interpretations)
   - Producer overflow (DEGRADED) vs. consumer overflow (DEFECTIVE)

2. `MultiLayerAggregationTest.java`
   - Multiple raw signals → single condition
   - Multiple conditions → single situation
   - Demonstrates fan-in aggregation

3. `PromiseNetworkCoordinationTest.java` ← NEW
   - Agent promise cycles
   - Multi-agent coordination
   - Breach handling and retraction

4. `ConversationalAgentTest.java` ← NEW
   - Human-AI dialogue patterns
   - Speech act sequences
   - Acknowledgment and clarification cycles

---

## Update humainary-api-compliance Skill

Location: `.claude/skills/humainary-api-compliance/`

**Updates Needed:**

1. Update API version references:
   - RC5 → RC6 throughout

2. Add 3 new Serventis APIs:
   - Agents API patterns and usage
   - Actors API patterns and usage
   - Routers API patterns and usage

3. Update OODA phase mapping:
   - Add ACT phase with Agents and Actors

4. Update compliance checks:
   - 12 Serventis APIs (was 9)
   - New RC6 Substrates patterns

5. Update examples:
   - Promise-based coordination
   - Speech act observability
   - Packet routing patterns

---

## Documentation Updates

### Files to Update:

1. **README.md**
   - Update to 12 Serventis APIs
   - Add Agents, Actors, Routers to OODA mapping
   - Update examples

2. **docs/ARCHITECTURE.md**
   - Add 3 new API sections
   - Update Serventis Integration section
   - Update "The Nine Instrument APIs" → "The Twelve Instrument APIs"

3. **docs/DEVELOPER-GUIDE.md**
   - Add Pattern 5: ACT Phase (Agent coordination)
   - Add Pattern 6: ACT Phase (Actor communication)
   - Add Router patterns to OBSERVE phase

4. **docs/examples/05-SemioticObservability.md**
   - Extend to include ACT phase
   - Show complete OBSERVE → ORIENT → DECIDE → ACT

5. **NEW: docs/examples/06-PromiseCoordination.md**
   - Agents API demonstration
   - Promise lifecycle
   - Multi-agent coordination

6. **NEW: docs/examples/07-ConversationalAgents.md**
   - Actors API demonstration
   - Speech act patterns
   - Human-AI dialogue

---

## Success Criteria

### Compliance
- ✅ All RC5 compliance tests pass with RC6
- ✅ All new RC6 compliance tests pass
- ✅ All 12 Serventis API demo tests pass
- ✅ OODA loop integration test passes

### Documentation
- ✅ All docs updated for RC6
- ✅ All 12 Serventis APIs documented
- ✅ New examples created (Promise + Actors)
- ✅ OODA mapping complete (4 phases, 12 APIs)

### Skill Updates
- ✅ humainary-api-compliance skill updated
- ✅ RC6 patterns documented
- ✅ 12-API compliance checks

### Build & Test
- ✅ `mvn clean test` passes
- ✅ No deprecation warnings
- ✅ Test coverage ≥ current level

---

## Implementation Order

1. ✅ Fetch RC6 API specifications
2. ✅ Analyze new APIs (Agents, Actors, Routers)
3. **Next:** Update POM to RC6 dependencies
4. Run existing tests (verify no regressions)
5. Create new Substrates RC6 compliance tests
6. Create 12 Serventis API demonstration tests
7. Create OODA loop integration tests
8. Update humainary-api-compliance skill
9. Update all documentation
10. Final validation and commit

---

## Notes

- **Promise Theory (Agents)**: Based on Mark Burgess's work, emphasizes voluntary cooperation over command-control
- **Speech Act Theory (Actors)**: Practical subset of speech act taxonomy for real-world dialogue
- **High-Frequency Routers**: May need sampling for >1M packets/second
- **OODA Extension**: ACT phase now has dedicated instrument APIs (was implicit before)

---

## Questions for Resolution

1. Are there any RC5 → RC6 breaking changes in core Substrates API?
2. Should we maintain RC5 compatibility or fully migrate?
3. What test coverage target for new APIs?
4. Should integration tests use real Kafka or test doubles?

---

**Created:** 2025-11-05
**Author:** Claude Code Assistant
