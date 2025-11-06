package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.Counters;
import io.humainary.substrates.ext.serventis.Gauges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for JvmDetailedMonitor covering all 5 metric categories.
 */
@Timeout(10)
class JvmDetailedMonitorTest {

    @Mock
    private MBeanServerConnection mockMbsc;

    private Circuit circuit;
    private Conduit<Gauges.Gauge, Gauges.Sign> gauges;
    private Conduit<Counters.Counter, Counters.Sign> counters;
    private List<Gauges.Sign> gaugeSignals;
    private List<Counters.Sign> counterSignals;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        // Create circuit and subscribe to signals
        circuit = cortex().circuit(cortex().name("test-jvm-detailed"));

        gauges = circuit.conduit(cortex().name("gauges"), Gauges::composer);
        counters = circuit.conduit(cortex().name("counters"), Counters::composer);

        gaugeSignals = new CopyOnWriteArrayList<>();
        counterSignals = new CopyOnWriteArrayList<>();

        gauges.subscribe(cortex().subscriber(cortex().name("gauge-test"),
            (subject, registrar) -> registrar.register(gaugeSignals::add)));

        counters.subscribe(cortex().subscriber(cortex().name("counter-test"),
            (subject, registrar) -> registrar.register(counterSignals::add)));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (circuit != null) {
            circuit.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================================================================
    // THREAD STATE TESTS (Tests 1-7)
    // ========================================================================

    @Test
    void shouldTrackRunnableThreads() throws Exception {
        // Given
        mockThreadStates(10, 0, 0, 0, 0, 0); // 10 RUNNABLE

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT);

        // Cleanup
        monitor.close();
    }

    @Test
    void shouldTrackBlockedThreads() throws Exception {
        // Given
        mockThreadStates(5, 15, 0, 0, 0, 0); // 15 BLOCKED

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT);

        monitor.close();
    }

    @Test
    void shouldEmitOverflowForExcessiveThreadsInState() throws Exception {
        // Given - 150 RUNNABLE threads (> 100 threshold)
        mockThreadStates(150, 0, 0, 0, 0, 0);

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.OVERFLOW);

        monitor.close();
    }

    @Test
    void shouldTrackWaitingThreads() throws Exception {
        // Given
        mockThreadStates(5, 0, 20, 0, 0, 0); // 20 WAITING

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT);

        monitor.close();
    }

    @Test
    void shouldTrackTimedWaitingThreads() throws Exception {
        // Given
        mockThreadStates(5, 0, 0, 10, 0, 0); // 10 TIMED_WAITING

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT);

        monitor.close();
    }

    @Test
    void shouldEmitDecrementForZeroThreadsInState() throws Exception {
        // Given - no threads in most states
        mockThreadStates(5, 0, 0, 0, 0, 0);

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - DECREMENT signals for states with 0 threads
        assertThat(gaugeSignals).contains(Gauges.Sign.DECREMENT);

        monitor.close();
    }

    @Test
    void shouldHandleThreadInfoAsCompositeData() throws Exception {
        // Given - JMX returns CompositeData instead of ThreadInfo
        ObjectName threadingMBean = new ObjectName("java.lang:type=Threading");
        when(mockMbsc.getAttribute(threadingMBean, "AllThreadIds"))
            .thenReturn(new long[]{1L, 2L});

        CompositeData threadInfo = createThreadInfoCompositeData("RUNNABLE");
        when(mockMbsc.invoke(eq(threadingMBean), eq("getThreadInfo"), any(), any()))
            .thenReturn(threadInfo);

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).isNotEmpty();

        monitor.close();
    }

    // ========================================================================
    // MEMORY POOL TESTS (Tests 8-13)
    // ========================================================================

    @Test
    void shouldTrackEdenSpaceUtilization() throws Exception {
        // Given
        mockMemoryPool("PS Eden Space", 900_000_000L, 1_000_000_000L); // 90%

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.OVERFLOW);

        monitor.close();
    }

    @Test
    void shouldTrackG1EdenSpace() throws Exception {
        // Given - G1GC uses different pool names
        mockMemoryPool("G1 Eden Space", 500_000_000L, 1_000_000_000L); // 50%

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.DECREMENT);

        monitor.close();
    }

    @Test
    void shouldTrackOldGenUtilization() throws Exception {
        // Given
        mockMemoryPool("PS Old Gen", 800_000_000L, 1_000_000_000L); // 80%

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT);

        monitor.close();
    }

    @Test
    void shouldTrackMetaspaceUtilization() throws Exception {
        // Given
        mockMemoryPool("Metaspace", 950_000_000L, 1_000_000_000L); // 95%

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.OVERFLOW);

        monitor.close();
    }

    @Test
    void shouldHandleMissingMemoryPools() throws Exception {
        // Given - InstanceNotFoundException for missing pool
        ObjectName poolMBean = new ObjectName("java.lang:type=MemoryPool,name=\"PS Eden Space\"");
        when(mockMbsc.getAttribute(poolMBean, "Usage"))
            .thenThrow(new InstanceNotFoundException("Pool not found"));

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - Should not throw, gracefully handle missing pool
        // No assertion needed - test passes if no exception

        monitor.close();
    }

    @Test
    void shouldHandleDifferentGCAlgorithms() throws Exception {
        // Given - CMS GC pool names
        mockMemoryPool("CMS Old Gen", 700_000_000L, 1_000_000_000L); // 70%

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).isNotEmpty();

        monitor.close();
    }

    // ========================================================================
    // CLASS LOADING TESTS (Tests 14-18)
    // ========================================================================

    @Test
    void shouldTrackClassLoading() throws Exception {
        // Given
        mockClassLoading(1000, 5000L, 100L); // loaded, total, unloaded

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(gaugeSignals).contains(Gauges.Sign.INCREMENT); // loadedClassGauge

        monitor.close();
    }

    @Test
    void shouldTrackClassLoadDeltas() throws Exception {
        // Given
        ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");

        // First poll: 1000 total loaded
        when(mockMbsc.getAttribute(classLoadingMBean, "LoadedClassCount")).thenReturn(1000);
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5000L);
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(100L);

        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // First poll
        circuit.await();

        counterSignals.clear();

        // Second poll: 50 more classes loaded
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5050L);

        monitor.pollJvmMetrics(); // Second poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.INCREMENT); // classLoadCounter

        monitor.close();
    }

    @Test
    void shouldEmitOverflowForExcessiveClassLoading() throws Exception {
        // Given
        ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");
        when(mockMbsc.getAttribute(classLoadingMBean, "LoadedClassCount")).thenReturn(1000);
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5000L);
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(100L);

        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        counterSignals.clear();

        // Second poll: 150 more classes loaded (> 100 threshold)
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5150L);

        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.OVERFLOW);

        monitor.close();
    }

    @Test
    void shouldTrackClassUnloading() throws Exception {
        // Given
        ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");
        when(mockMbsc.getAttribute(classLoadingMBean, "LoadedClassCount")).thenReturn(1000);
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5000L);
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(100L);

        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        counterSignals.clear();

        // Second poll: 20 classes unloaded
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(120L);

        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.INCREMENT); // classUnloadCounter

        monitor.close();
    }

    @Test
    void shouldDetectClassloaderLeaks() throws Exception {
        // Given
        ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");
        when(mockMbsc.getAttribute(classLoadingMBean, "LoadedClassCount")).thenReturn(1000);
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(5000L);
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(100L);

        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        counterSignals.clear();

        // Second poll: 200 classes unloaded (> 100 threshold - potential leak)
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(300L);

        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.OVERFLOW);

        monitor.close();
    }

    // ========================================================================
    // SAFEPOINT TESTS (Tests 19-20)
    // ========================================================================

    @Test
    void shouldHandleMissingSafepointMetrics() throws Exception {
        // Given - Safepoint MBean not available
        ObjectName diagnosticMBean = new ObjectName("sun.management:type=HotSpotDiagnostic");
        when(mockMbsc.getMBeanInfo(diagnosticMBean))
            .thenThrow(new InstanceNotFoundException("Diagnostic MBean not found"));

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - Should not throw, gracefully handle missing MBean
        // No assertion needed - test passes if no exception

        monitor.close();
    }

    @Test
    void shouldTrackSafepointWhenAvailable() throws Exception {
        // Given - Diagnostic MBean exists
        ObjectName diagnosticMBean = new ObjectName("sun.management:type=HotSpotDiagnostic");
        when(mockMbsc.getMBeanInfo(diagnosticMBean)).thenReturn(mock(javax.management.MBeanInfo.class));

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.INCREMENT); // safepointCounter

        monitor.close();
    }

    // ========================================================================
    // COMPILATION TESTS (Tests 21-24)
    // ========================================================================

    @Test
    void shouldTrackJitCompilationTime() throws Exception {
        // Given
        mockCompilation(true, 10000L); // 10 seconds total compilation

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - First poll doesn't emit delta
        // Just verifies no exception

        monitor.close();
    }

    @Test
    void shouldTrackCompilationTimeDelta() throws Exception {
        // Given
        ObjectName compilationMBean = new ObjectName("java.lang:type=Compilation");
        when(mockMbsc.getAttribute(compilationMBean, "CompilationTimeMonitoringSupported"))
            .thenReturn(true);
        when(mockMbsc.getAttribute(compilationMBean, "TotalCompilationTime"))
            .thenReturn(10000L);

        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        counterSignals.clear();

        // Second poll: 500ms more compilation
        when(mockMbsc.getAttribute(compilationMBean, "TotalCompilationTime")).thenReturn(10500L);

        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then
        assertThat(counterSignals).contains(Counters.Sign.INCREMENT);

        monitor.close();
    }

    @Test
    void shouldHandleCompilationNotSupported() throws Exception {
        // Given
        mockCompilation(false, 0L); // Compilation monitoring not supported

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - Should not throw
        // No assertion needed - test passes if no exception

        monitor.close();
    }

    @Test
    void shouldHandleNullCompilationSupport() throws Exception {
        // Given
        ObjectName compilationMBean = new ObjectName("java.lang:type=Compilation");
        when(mockMbsc.getAttribute(compilationMBean, "CompilationTimeMonitoringSupported"))
            .thenReturn(null); // Null response

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - Should handle gracefully
        // No assertion needed - test passes if no exception

        monitor.close();
    }

    // ========================================================================
    // INTEGRATION TESTS (Tests 25-28)
    // ========================================================================

    @Test
    void shouldPollAtConfiguredInterval() throws Exception {
        // Given
        mockThreadStates(10, 0, 0, 0, 0, 0);

        // When - 50ms interval, let it actually poll automatically
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            50, TimeUnit.MILLISECONDS);

        Thread.sleep(120); // Wait for 2-3 automatic polls
        circuit.await();

        // Then - Multiple polls occurred
        verify(mockMbsc, atLeast(2)).getAttribute(
            eq(new ObjectName("java.lang:type=Threading")),
            eq("AllThreadIds"));

        monitor.close();
    }

    @Test
    void shouldHandleJmxExceptionGracefully() throws Exception {
        // Given - JMX throws exception
        ObjectName threadingMBean = new ObjectName("java.lang:type=Threading");
        when(mockMbsc.getAttribute(threadingMBean, "AllThreadIds"))
            .thenThrow(new RuntimeException("JMX error"));

        // When
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS); // Long interval - we'll call manually
        monitor.pollJvmMetrics(); // Manual poll
        circuit.await();

        // Then - Should not propagate exception
        // No assertion needed - test passes if no exception

        monitor.close();
    }

    @Test
    void shouldCloseCleanly() throws Exception {
        // Given
        mockThreadStates(10, 0, 0, 0, 0, 0);
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);

        monitor.pollJvmMetrics(); // Manual poll

        // When
        monitor.close();

        // Then - Should shut down without hanging
        // Test will timeout if close() hangs
    }

    @Test
    void shouldHandleInterruptedClose() throws Exception {
        // Given
        mockThreadStates(10, 0, 0, 0, 0, 0);
        JvmDetailedMonitor monitor = new JvmDetailedMonitor(mockMbsc, circuit, gauges, counters,
            1000, TimeUnit.SECONDS);

        // When - Interrupt thread during close
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            monitor.close();
        });
        closer.start();
        closer.join(1000);

        // Then - Should handle interruption gracefully
        assertThat(closer.isAlive()).isFalse();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void mockThreadStates(int runnable, int blocked, int waiting,
                                   int timedWaiting, int newState, int terminated) throws Exception {
        ObjectName threadingMBean = new ObjectName("java.lang:type=Threading");

        List<Long> threadIds = new ArrayList<>();
        List<ThreadInfo> threadInfos = new ArrayList<>();

        int id = 1;

        // Add threads in each state
        for (int i = 0; i < runnable; i++) {
            threadIds.add((long) id++);
        }
        for (int i = 0; i < blocked; i++) {
            threadIds.add((long) id++);
        }
        for (int i = 0; i < waiting; i++) {
            threadIds.add((long) id++);
        }
        for (int i = 0; i < timedWaiting; i++) {
            threadIds.add((long) id++);
        }
        for (int i = 0; i < newState; i++) {
            threadIds.add((long) id++);
        }
        for (int i = 0; i < terminated; i++) {
            threadIds.add((long) id++);
        }

        when(mockMbsc.getAttribute(threadingMBean, "AllThreadIds"))
            .thenReturn(threadIds.stream().mapToLong(Long::longValue).toArray());

        // Mock thread info responses
        id = 1;
        for (int i = 0; i < runnable; i++) {
            mockThreadInfo(id++, Thread.State.RUNNABLE);
        }
        for (int i = 0; i < blocked; i++) {
            mockThreadInfo(id++, Thread.State.BLOCKED);
        }
        for (int i = 0; i < waiting; i++) {
            mockThreadInfo(id++, Thread.State.WAITING);
        }
        for (int i = 0; i < timedWaiting; i++) {
            mockThreadInfo(id++, Thread.State.TIMED_WAITING);
        }
        for (int i = 0; i < newState; i++) {
            mockThreadInfo(id++, Thread.State.NEW);
        }
        for (int i = 0; i < terminated; i++) {
            mockThreadInfo(id++, Thread.State.TERMINATED);
        }
    }

    private void mockThreadInfo(long threadId, Thread.State state) throws Exception {
        ObjectName threadingMBean = new ObjectName("java.lang:type=Threading");
        ThreadInfo threadInfo = mock(ThreadInfo.class);
        when(threadInfo.getThreadState()).thenReturn(state);

        when(mockMbsc.invoke(
            eq(threadingMBean),
            eq("getThreadInfo"),
            eq(new Object[]{threadId}),
            eq(new String[]{"long"})
        )).thenReturn(threadInfo);
    }

    private CompositeData createThreadInfoCompositeData(String threadState) throws Exception {
        String[] itemNames = {"threadId", "threadName", "threadState"};
        Object[] itemValues = {1L, "TestThread", threadState};
        OpenType<?>[] itemTypes = {SimpleType.LONG, SimpleType.STRING, SimpleType.STRING};

        CompositeType compositeType = new CompositeType(
            "ThreadInfo",
            "Thread Information",
            itemNames,
            itemNames,
            itemTypes
        );

        return new CompositeDataSupport(compositeType, itemNames, itemValues);
    }

    private void mockMemoryPool(String poolName, long used, long max) throws Exception {
        // Note: ObjectName doesn't include quotes around the name value
        // The JMX implementation handles quoting internally
        ObjectName poolMBean = new ObjectName(
            "java.lang:type=MemoryPool,name=" + poolName);

        CompositeData usage = createMemoryUsage(used, max, max);
        when(mockMbsc.getAttribute(poolMBean, "Usage")).thenReturn(usage);
    }

    private CompositeData createMemoryUsage(long used, long max, long committed) throws Exception {
        String[] itemNames = {"init", "used", "committed", "max"};
        Object[] itemValues = {0L, used, committed, max};
        OpenType<?>[] itemTypes = {
            SimpleType.LONG, SimpleType.LONG, SimpleType.LONG, SimpleType.LONG
        };

        CompositeType compositeType = new CompositeType(
            "MemoryUsage",
            "Memory Usage",
            itemNames,
            itemNames,
            itemTypes
        );

        return new CompositeDataSupport(compositeType, itemNames, itemValues);
    }

    private void mockClassLoading(int loaded, long total, long unloaded) throws Exception {
        ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");
        when(mockMbsc.getAttribute(classLoadingMBean, "LoadedClassCount")).thenReturn(loaded);
        when(mockMbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount")).thenReturn(total);
        when(mockMbsc.getAttribute(classLoadingMBean, "UnloadedClassCount")).thenReturn(unloaded);
    }

    private void mockCompilation(boolean supported, long totalTime) throws Exception {
        ObjectName compilationMBean = new ObjectName("java.lang:type=Compilation");
        when(mockMbsc.getAttribute(compilationMBean, "CompilationTimeMonitoringSupported"))
            .thenReturn(supported);
        when(mockMbsc.getAttribute(compilationMBean, "TotalCompilationTime"))
            .thenReturn(totalTime);
    }
}
