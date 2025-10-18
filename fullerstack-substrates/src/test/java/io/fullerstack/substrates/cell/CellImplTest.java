package io.fullerstack.substrates.cell;

import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.functional.CellComposer;
import io.fullerstack.substrates.registry.LazyTrieRegistryFactory;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for CellImpl.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Cell creation and hierarchy</li>
 *   <li>Type transformation (I → E)</li>
 *   <li>Parent-to-children broadcast</li>
 *   <li>Child emission flows to parent Source</li>
 *   <li>Hierarchical naming</li>
 *   <li>Subscriber integration</li>
 *   <li>Flow transformations on Cell</li>
 * </ul>
 */
class CellImplTest {

    private Cortex cortex;
    private Circuit circuit;

    @BeforeEach
    void setUp() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit(cortex.name("test-circuit"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (circuit != null) {
            circuit.close();
        }
    }

    // ========== Basic Cell Creation ==========

    @Test
    void testCellCreation() {
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        assertNotNull(cell);
        assertNotNull(cell.subject());
        assertNotNull(cell.source());
        assertEquals(Subject.Type.CELL, cell.subject().type());
    }

    @Test
    void testSameTypeCell() {
        Cell<String, String> cell = circuit.cell(
            CellComposer.sameType(circuit, LazyTrieRegistryFactory.getInstance())
        );

        assertNotNull(cell);
        assertNotNull(cell.source());
    }

    // ========== Child Cell Creation ==========

    @Test
    void testChildCellCreation() {
        Cell<Integer, String> parent = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        Name childName = cortex.name("child-1");
        Cell<Integer, String> child = parent.get(childName);

        assertNotNull(child);
        assertNotNull(child.subject());
        assertEquals("child-1", child.subject().name().path());
    }

    @Test
    void testMultipleChildCells() {
        Cell<Integer, String> parent = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        Cell<Integer, String> child1 = parent.get(cortex.name("child-1"));
        Cell<Integer, String> child2 = parent.get(cortex.name("child-2"));
        Cell<Integer, String> child3 = parent.get(cortex.name("child-3"));

        assertNotNull(child1);
        assertNotNull(child2);
        assertNotNull(child3);
        assertNotSame(child1, child2);
        assertNotSame(child2, child3);
    }

    @Test
    void testChildCellCaching() {
        Cell<Integer, String> parent = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        Name childName = cortex.name("child");
        Cell<Integer, String> child1 = parent.get(childName);
        Cell<Integer, String> child2 = parent.get(childName);

        assertSame(child1, child2, "Same child should be returned for same name");
    }

    // ========== Type Transformation ==========

    @Test
    void testTypeTransformation() throws Exception {
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Number: " + i
            )
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.set(value);
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, String> child = cell.get(cortex.name("child"));
        child.emit(42);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive transformed emission");
        assertEquals("Number: 42", received.get());
    }

    @Test
    void testMultipleTransformations() throws Exception {
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + (i * 2)
            )
        );

        CountDownLatch latch = new CountDownLatch(3);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.add(value);
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, String> child = cell.get(cortex.name("child"));
        child.emit(1);
        child.emit(2);
        child.emit(3);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive all emissions");
        assertTrue(received.contains("Value: 2"));
        assertTrue(received.contains("Value: 4"));
        assertTrue(received.contains("Value: 6"));
    }

    // ========== Hierarchical Emission ==========

    @Test
    void testParentBroadcastToChildren() throws Exception {
        Cell<Integer, String> parent = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Broadcast: " + i
            )
        );

        CountDownLatch latch = new CountDownLatch(3);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.add(value);
                latch.countDown();
            })
        );

        parent.source().subscribe(subscriber);

        // Create child Cells BEFORE emitting
        Cell<Integer, String> child1 = parent.get(cortex.name("child-1"));
        Cell<Integer, String> child2 = parent.get(cortex.name("child-2"));
        Cell<Integer, String> child3 = parent.get(cortex.name("child-3"));

        // Emit to parent - should broadcast to all children
        parent.emit(100);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "All children should receive broadcast");
        assertEquals(3, received.size());
        assertTrue(received.stream().allMatch(s -> s.equals("Broadcast: 100")));
    }

    @Test
    void testDeepHierarchy() throws Exception {
        Cell<Integer, String> root = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Level: " + i
            )
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.set(value);
                latch.countDown();
            })
        );

        root.source().subscribe(subscriber);

        // Create 3-level hierarchy: root → level1 → level2
        Cell<Integer, String> level1 = root.get(cortex.name("level-1"));
        Cell<Integer, String> level2 = level1.get(cortex.name("level-2"));

        // Emit at deepest level
        level2.emit(42);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive emission from deep child");
        assertEquals("Level: 42", received.get());
    }

    @Test
    void testKafkaLikeHierarchy() throws Exception {
        // Simulate: cluster → broker → partition
        Cell<Integer, String> cluster = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                metric -> "Metric: " + metric
            )
        );

        CountDownLatch latch = new CountDownLatch(4);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("cluster-monitor"),
            (subject, registrar) -> registrar.register(value -> {
                received.add(value);
                latch.countDown();
            })
        );

        cluster.source().subscribe(subscriber);

        // Create broker Cells
        Cell<Integer, String> broker1 = cluster.get(cortex.name("broker-1"));
        Cell<Integer, String> broker2 = cluster.get(cortex.name("broker-2"));

        // Create partition Cells under broker1
        Cell<Integer, String> broker1Partition0 = broker1.get(cortex.name("partition-0"));
        Cell<Integer, String> broker1Partition1 = broker1.get(cortex.name("partition-1"));

        // Emit metrics from different partitions
        broker1Partition0.emit(100);
        broker1Partition1.emit(200);

        // Emit to broker2 directly
        broker2.emit(300);

        // Emit to cluster (broadcasts to all brokers)
        cluster.emit(999);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive all emissions");
        assertTrue(received.contains("Metric: 100"));
        assertTrue(received.contains("Metric: 200"));
        assertTrue(received.contains("Metric: 300"));
    }

    // ========== Flow Transformations ==========

    @Test
    void testCellWithFlowGuard() throws Exception {
        Cell<Integer, Integer> cell = circuit.cell(
            CellComposer.sameType(circuit, LazyTrieRegistryFactory.getInstance()),
            flow -> flow.guard(x -> x > 50)  // Only emit values > 50
        );

        CountDownLatch latch = new CountDownLatch(2);
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();

        Subscriber<Integer> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.add(value);
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, Integer> child = cell.get(cortex.name("child"));
        child.emit(10);  // Filtered out
        child.emit(60);  // Passes
        child.emit(30);  // Filtered out
        child.emit(100); // Passes

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive filtered values");
        assertEquals(2, received.size());
        assertTrue(received.contains(60));
        assertTrue(received.contains(100));
    }

    @Test
    void testCellWithFlowReplace() throws Exception {
        Cell<Integer, Integer> cell = circuit.cell(
            CellComposer.sameType(circuit, LazyTrieRegistryFactory.getInstance()),
            flow -> flow.replace(x -> x * 10)  // Multiply by 10
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> received = new AtomicReference<>();

        Subscriber<Integer> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.set(value);
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, Integer> child = cell.get(cortex.name("child"));
        child.emit(5);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive transformed value");
        assertEquals(50, received.get());
    }

    @Test
    void testCellWithFlowLimit() throws Exception {
        Cell<Integer, Integer> cell = circuit.cell(
            CellComposer.sameType(circuit, LazyTrieRegistryFactory.getInstance()),
            flow -> flow.limit(2)  // Only emit first 2 values
        );

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger count = new AtomicInteger(0);

        Subscriber<Integer> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                count.incrementAndGet();
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, Integer> child = cell.get(cortex.name("child"));
        child.emit(1);
        child.emit(2);
        child.emit(3);  // Should be limited
        child.emit(4);  // Should be limited

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive limited values");
        Thread.sleep(100); // Give time for any extra emissions
        assertEquals(2, count.get(), "Should only receive first 2 emissions");
    }

    // ========== Null Safety ==========

    @Test
    void testNullEmissionThrows() {
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        Cell<Integer, String> child = cell.get(cortex.name("child"));
        assertThrows(NullPointerException.class, () -> child.emit(null));
    }

    @Test
    void testNullTransformationFiltered() throws Exception {
        // Transformer that returns null should filter out emission
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> i > 50 ? "High: " + i : null  // Return null for low values
            )
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        Subscriber<String> subscriber = cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) -> registrar.register(value -> {
                received.set(value);
                latch.countDown();
            })
        );

        cell.source().subscribe(subscriber);

        Cell<Integer, String> child = cell.get(cortex.name("child"));
        child.emit(10);  // null transformation - filtered
        child.emit(100); // Valid transformation

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive non-null transformation");
        assertEquals("High: 100", received.get());
    }

    // ========== Subject Type ==========

    @Test
    void testCellSubjectType() {
        Cell<Integer, String> cell = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        assertEquals(Subject.Type.CELL, cell.subject().type());
    }

    // ========== Concurrent Access ==========

    @Test
    void testConcurrentChildCreation() throws Exception {
        Cell<Integer, String> parent = circuit.cell(
            CellComposer.fromCircuit(
                circuit,
                LazyTrieRegistryFactory.getInstance(),
                i -> "Value: " + i
            )
        );

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<Cell<Integer, String>> cells = new CopyOnWriteArrayList<>();

        Name sharedName = cortex.name("shared-child");

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Cell<Integer, String> child = parent.get(sharedName);
                    cells.add(child);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");

        // All threads should get the same child instance
        Cell<Integer, String> first = cells.get(0);
        assertTrue(cells.stream().allMatch(c -> c == first), "All should be same instance");
    }
}
