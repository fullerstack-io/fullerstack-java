package io.fullerstack.serventis.signals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VectorClockManager.
 */
class VectorClockManagerTest {

    private VectorClockManager manager;

    @BeforeEach
    void setUp() {
        manager = new VectorClockManager();
    }

    @Test
    void testIncrement() {
        manager.increment("broker-1");
        assertEquals(1L, manager.get("broker-1"));

        manager.increment("broker-1");
        assertEquals(2L, manager.get("broker-1"));
    }

    @Test
    void testIncrementMultipleEntities() {
        manager.increment("broker-1");
        manager.increment("broker-2");
        manager.increment("broker-1");

        assertEquals(2L, manager.get("broker-1"));
        assertEquals(1L, manager.get("broker-2"));
    }

    @Test
    void testGet_NonExistentEntity() {
        assertEquals(0L, manager.get("broker-999"));
    }

    @Test
    void testSnapshot() {
        manager.increment("broker-1");
        manager.increment("broker-2");
        manager.increment("broker-1");

        VectorClock snapshot = manager.snapshot();

        assertNotNull(snapshot);
        Map<String, Long> clocks = snapshot.clocks();
        assertEquals(2L, clocks.get("broker-1"));
        assertEquals(1L, clocks.get("broker-2"));
    }

    @Test
    void testSnapshot_EmptyClock() {
        VectorClock snapshot = manager.snapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.clocks().isEmpty());
    }

    @Test
    void testMerge() {
        manager.increment("broker-1");  // broker-1: 1
        manager.increment("broker-1");  // broker-1: 2

        VectorClock incoming = new VectorClock(Map.of(
                "broker-1", 1L,  // Lower than ours
                "broker-2", 3L   // New entity
        ));

        manager.merge(incoming);

        assertEquals(2L, manager.get("broker-1"));  // Max(2, 1) = 2
        assertEquals(3L, manager.get("broker-2"));  // New: 3
    }

    @Test
    void testMerge_TakesMax() {
        manager.increment("broker-1");  // broker-1: 1

        VectorClock incoming = new VectorClock(Map.of("broker-1", 5L));

        manager.merge(incoming);

        assertEquals(5L, manager.get("broker-1"));  // Max(1, 5) = 5
    }

    @Test
    void testMerge_NullClock() {
        manager.increment("broker-1");

        manager.merge(null);  // Should not throw

        assertEquals(1L, manager.get("broker-1"));  // Unchanged
    }

    @Test
    void testReset() {
        manager.increment("broker-1");
        manager.increment("broker-2");

        manager.reset();

        assertEquals(0L, manager.get("broker-1"));
        assertEquals(0L, manager.get("broker-2"));
    }

    @Test
    void testHappensBefore() {
        manager.increment("broker-1");
        VectorClock vc1 = manager.snapshot();  // {broker-1: 1}

        manager.increment("broker-1");
        VectorClock vc2 = manager.snapshot();  // {broker-1: 2}

        assertTrue(vc1.happenedBefore(vc2));
        assertFalse(vc2.happenedBefore(vc1));
    }

    @Test
    void testConcurrent() {
        manager.increment("broker-1");
        VectorClock vc1 = manager.snapshot();  // {broker-1: 1}

        manager.reset();
        manager.increment("broker-2");
        VectorClock vc2 = manager.snapshot();  // {broker-2: 1}

        assertTrue(vc1.concurrent(vc2));
        assertTrue(vc2.concurrent(vc1));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    manager.increment("broker-1");
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals((long) (threadCount * incrementsPerThread), manager.get("broker-1"));
    }
}
