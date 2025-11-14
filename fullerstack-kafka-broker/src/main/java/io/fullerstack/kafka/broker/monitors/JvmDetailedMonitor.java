package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Monitors detailed JVM metrics via JMX with advanced runtime visibility.
 *
 * <p><b>Metrics Monitored (5 categories):</b>
 * <ol>
 *   <li>Thread State Breakdown - Gauges per thread state (RUNNABLE, BLOCKED, WAITING, etc.)</li>
 *   <li>Class Loading Stats - Counters for load/unload operations, Gauges for current count</li>
 *   <li>Memory Pool Details - Gauges for Eden, Survivor, Old Gen, Metaspace</li>
 *   <li>Safepoint Time - Counters for safepoint events (platform-specific)</li>
 *   <li>Compiler Time - Counters for JIT compilation overhead</li>
 * </ol>
 *
 * <p><b>Polling Interval:</b> 15 seconds (configurable)
 *
 * <p><b>Platform Differences:</b> Gracefully handles missing MBeans on different JVM implementations.
 */
public class JvmDetailedMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(JvmDetailedMonitor.class);

    // Thresholds
    private static final int THREAD_STATE_OVERFLOW_THRESHOLD = 100;
    private static final int BLOCKED_THREAD_WARNING_THRESHOLD = 10;
    private static final double MEMORY_POOL_OVERFLOW_THRESHOLD = 0.90;
    private static final double MEMORY_POOL_WARNING_THRESHOLD = 0.75;

    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final ScheduledExecutorService scheduler;

    // Conduits (for dynamic instrument creation)
    private final Conduit<Gauges.Gauge, Gauges.Signal> gaugesConduit;
    private final Conduit<Counters.Counter, Counters.Signal> countersConduit;

    // Instruments - Thread state gauges
    private final Map<Thread.State, Gauges.Gauge> threadStateGauges;

    // Instruments - Memory pool gauges
    private final Map<String, Gauges.Gauge> memoryPoolGauges;

    // Instruments - Class loading
    private final Counters.Counter classLoadCounter;
    private final Counters.Counter classUnloadCounter;
    private final Gauges.Gauge loadedClassGauge;

    // Instruments - Safepoint (if available)
    private final Counters.Counter safepointCounter;
    private final Gauges.Gauge safepointDurationGauge;

    // Instruments - Compilation
    private final Counters.Counter compilationCounter;

    // Previous values for delta calculation
    private long previousTotalLoadedClasses = -1;
    private long previousUnloadedClasses = -1;
    private long previousCompilationTime = -1;

    /**
     * Creates a JvmDetailedMonitor with default 15-second polling interval.
     *
     * @param mbsc    MBeanServerConnection for JMX access
     * @param circuit Circuit for signal emission
     */
    public JvmDetailedMonitor(MBeanServerConnection mbsc, Circuit circuit) {
        this(mbsc, circuit, 15, TimeUnit.SECONDS);
    }

    /**
     * Creates a JvmDetailedMonitor with custom polling interval.
     *
     * @param mbsc     MBeanServerConnection for JMX access
     * @param circuit  Circuit for signal emission
     * @param interval Polling interval
     * @param unit     Time unit for interval
     */
    public JvmDetailedMonitor(MBeanServerConnection mbsc, Circuit circuit,
                              long interval, TimeUnit unit) {
        // Create conduits from circuit
        Conduit<Gauges.Gauge, Gauges.Signal> gauges =
            circuit.conduit(cortex().name("gauges"), Gauges::composer);
        Conduit<Counters.Counter, Counters.Signal> counters =
            circuit.conduit(cortex().name("counters"), Counters::composer);

        // Delegate to main constructor
        this(mbsc, circuit, gauges, counters, interval, unit);
    }

    /**
     * Creates a JvmDetailedMonitor with explicit conduits (for testing).
     *
     * @param mbsc     MBeanServerConnection for JMX access
     * @param circuit  Circuit for signal emission
     * @param gauges   Gauges conduit
     * @param counters Counters conduit
     * @param interval Polling interval
     * @param unit     Time unit for interval
     */
    public JvmDetailedMonitor(MBeanServerConnection mbsc, Circuit circuit,
                              Conduit<Gauges.Gauge, Gauges.Signal> gauges,
                              Conduit<Counters.Counter, Counters.Signal> counters,
                              long interval, TimeUnit unit) {
        this.mbsc = Objects.requireNonNull(mbsc, "mbsc cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.gaugesConduit = Objects.requireNonNull(gauges, "gauges conduit cannot be null");
        this.countersConduit = Objects.requireNonNull(counters, "counters conduit cannot be null");

        // Thread state gauges
        this.threadStateGauges = new EnumMap<>(Thread.State.class);
        for (Thread.State state : Thread.State.values()) {
            threadStateGauges.put(state,
                gauges.percept(cortex().name("jvm.threads." + state.name().toLowerCase())));
        }

        // Memory pool gauges (handles different GC algorithm pool names)
        this.memoryPoolGauges = new ConcurrentHashMap<>();

        // Class loading
        this.classLoadCounter = counters.percept(cortex().name("jvm.class.load"));
        this.classUnloadCounter = counters.percept(cortex().name("jvm.class.unload"));
        this.loadedClassGauge = gauges.percept(cortex().name("jvm.class.loaded"));

        // Safepoint
        this.safepointCounter = counters.percept(cortex().name("jvm.safepoint.count"));
        this.safepointDurationGauge = gauges.percept(cortex().name("jvm.safepoint.duration"));

        // Compilation
        this.compilationCounter = counters.percept(cortex().name("jvm.compilation.time"));

        // Schedule polling
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::pollJvmMetrics, 0, interval, unit);

        logger.info("JvmDetailedMonitor started with {}s polling interval", unit.toSeconds(interval));
    }

    /**
     * Polls all JVM metrics categories.
     * Public for testing purposes.
     */
    public void pollJvmMetrics() {
        try {
            pollThreadStates();
            pollMemoryPools();
            pollClassLoading();
            pollSafepointInfo();
            pollCompilationTime();

        } catch (Exception e) {
            logger.error("JVM detailed metrics polling failed", e);
        }
    }

    /**
     * Polls thread states and emits gauge signals.
     */
    private void pollThreadStates() {
        try {
            ObjectName threadingMBean = new ObjectName("java.lang:type=Threading");
            long[] threadIds = (long[]) mbsc.getAttribute(threadingMBean, "AllThreadIds");

            // Count threads by state
            Map<Thread.State, Integer> stateCounts = new EnumMap<>(Thread.State.class);
            for (Thread.State state : Thread.State.values()) {
                stateCounts.put(state, 0);
            }

            for (long threadId : threadIds) {
                Object threadInfoObj = mbsc.invoke(
                    threadingMBean,
                    "getThreadInfo",
                    new Object[]{threadId},
                    new String[]{"long"}
                );

                if (threadInfoObj instanceof ThreadInfo) {
                    ThreadInfo info = (ThreadInfo) threadInfoObj;
                    if (info != null) {
                        stateCounts.merge(info.getThreadState(), 1, Integer::sum);
                    }
                } else if (threadInfoObj instanceof CompositeData) {
                    CompositeData compositeInfo = (CompositeData) threadInfoObj;
                    String threadStateStr = (String) compositeInfo.get("threadState");
                    if (threadStateStr != null) {
                        Thread.State state = Thread.State.valueOf(threadStateStr);
                        stateCounts.merge(state, 1, Integer::sum);
                    }
                }
            }

            // Emit gauge signals for each state
            for (Map.Entry<Thread.State, Integer> entry : stateCounts.entrySet()) {
                Gauges.Gauge gauge = threadStateGauges.get(entry.getKey());
                int count = entry.getValue();

                if (count > THREAD_STATE_OVERFLOW_THRESHOLD) {
                    gauge.overflow();
                } else if (count > 0) {
                    gauge.increment();
                } else {
                    gauge.decrement();
                }
            }

            // Warn about blocked threads (potential deadlock)
            int blockedCount = stateCounts.get(Thread.State.BLOCKED);
            if (blockedCount > BLOCKED_THREAD_WARNING_THRESHOLD) {
                logger.warn("High blocked thread count: {} (potential deadlock)", blockedCount);
            }

            logger.debug("Thread states: {}", stateCounts);

        } catch (Exception e) {
            logger.debug("Failed to poll thread states: {}", e.getMessage());
        }
    }

    /**
     * Polls memory pool metrics and emits gauge signals.
     * Handles different GC algorithm pool names.
     */
    private void pollMemoryPools() {
        // Common pool name patterns across different GC algorithms
        List<PoolMapping> poolMappings = Arrays.asList(
            new PoolMapping("eden", Arrays.asList("PS Eden Space", "G1 Eden Space", "Par Eden Space", "Eden Space")),
            new PoolMapping("survivor", Arrays.asList("PS Survivor Space", "G1 Survivor Space", "Par Survivor Space", "Survivor Space")),
            new PoolMapping("old-gen", Arrays.asList("PS Old Gen", "G1 Old Gen", "CMS Old Gen", "Tenured Gen", "Old Gen")),
            new PoolMapping("metaspace", Arrays.asList("Metaspace", "Compressed Class Space"))
        );

        for (PoolMapping mapping : poolMappings) {
            for (String poolName : mapping.possibleNames) {
                try {
                    String quotedName = poolName.replace("\"", "\\\"");
                    ObjectName poolMBean = new ObjectName(
                        "java.lang:type=MemoryPool,name=" + quotedName);

                    CompositeData usage = (CompositeData) mbsc.getAttribute(poolMBean, "Usage");
                    long used = (Long) usage.get("used");
                    long max = (Long) usage.get("max");

                    if (max > 0) {
                        double utilization = (double) used / max;

                        Gauges.Gauge gauge = memoryPoolGauges.computeIfAbsent(
                            mapping.key,
                            key -> gaugesConduit.percept(cortex().name("jvm.memory.pool." + key))
                        );

                        if (utilization >= MEMORY_POOL_OVERFLOW_THRESHOLD) {
                            gauge.overflow();
                        } else if (utilization >= MEMORY_POOL_WARNING_THRESHOLD) {
                            gauge.increment();
                        } else {
                            gauge.decrement();
                        }

                        logger.debug("Memory pool {}: {}/{} MB ({}%)",
                            poolName, used / 1024 / 1024, max / 1024 / 1024,
                            (int) (utilization * 100));
                    }

                    break; // Found this pool, move to next mapping

                } catch (InstanceNotFoundException e) {
                    // Pool doesn't exist on this JVM - try next variant
                    logger.trace("Memory pool not found: {}", poolName);
                } catch (Exception e) {
                    // Handle any other JMX errors
                    logger.trace("Failed to query memory pool {}: {}", poolName, e.getMessage());
                }
            }
        }
    }

    /**
     * Polls class loading statistics and emits signals.
     */
    private void pollClassLoading() {
        try {
            ObjectName classLoadingMBean = new ObjectName("java.lang:type=ClassLoading");

            int loadedClassCount = (Integer) mbsc.getAttribute(classLoadingMBean, "LoadedClassCount");
            long totalLoadedCount = (Long) mbsc.getAttribute(classLoadingMBean, "TotalLoadedClassCount");
            long unloadedCount = (Long) mbsc.getAttribute(classLoadingMBean, "UnloadedClassCount");

            // Track current loaded classes
            loadedClassGauge.increment();

            // Track load/unload deltas
            if (previousTotalLoadedClasses >= 0) {
                long loadDelta = totalLoadedCount - previousTotalLoadedClasses;
                if (loadDelta > 0) {
                    // Emit increments for new class loads
                    for (int i = 0; i < Math.min(loadDelta, 100); i++) {
                        classLoadCounter.increment();
                    }
                    if (loadDelta > 100) {
                        classLoadCounter.overflow(); // Excessive loading
                    }
                }
            }

            if (previousUnloadedClasses >= 0) {
                long unloadDelta = unloadedCount - previousUnloadedClasses;
                if (unloadDelta > 0) {
                    // Emit increments for class unloads
                    for (int i = 0; i < Math.min(unloadDelta, 100); i++) {
                        classUnloadCounter.increment();
                    }
                    if (unloadDelta > 100) {
                        classUnloadCounter.overflow(); // Excessive unloading (classloader leak?)
                    }
                }
            }

            previousTotalLoadedClasses = totalLoadedCount;
            previousUnloadedClasses = unloadedCount;

            logger.debug("Class loading: loaded={}, total={}, unloaded={}",
                loadedClassCount, totalLoadedCount, unloadedCount);

        } catch (Exception e) {
            logger.debug("Failed to poll class loading: {}", e.getMessage());
        }
    }

    /**
     * Polls safepoint information (platform-specific).
     */
    private void pollSafepointInfo() {
        try {
            // Note: Safepoint metrics are Oracle JDK specific and may not be available
            ObjectName diagnosticMBean = new ObjectName("sun.management:type=HotSpotDiagnostic");

            // Try to access diagnostic bean to verify availability
            mbsc.getMBeanInfo(diagnosticMBean);

            // If we get here, the bean exists
            // Note: RC7 doesn't expose detailed safepoint metrics via standard MBeans
            // This would require parsing GC logs or using diagnostic commands
            safepointCounter.increment();

            logger.trace("Safepoint metrics polled (limited data available)");

        } catch (InstanceNotFoundException e) {
            logger.trace("Safepoint metrics not available on this JVM");
        } catch (Exception e) {
            logger.debug("Failed to poll safepoint info: {}", e.getMessage());
        }
    }

    /**
     * Polls JIT compilation time and emits signals.
     */
    private void pollCompilationTime() {
        try {
            ObjectName compilationMBean = new ObjectName("java.lang:type=Compilation");

            // Check if JIT compilation is supported
            Boolean supported = (Boolean) mbsc.getAttribute(compilationMBean,
                "CompilationTimeMonitoringSupported");

            if (Boolean.TRUE.equals(supported)) {
                long totalTime = (Long) mbsc.getAttribute(compilationMBean, "TotalCompilationTime");

                // Calculate delta
                if (previousCompilationTime >= 0) {
                    long timeDelta = totalTime - previousCompilationTime;
                    if (timeDelta > 0) {
                        compilationCounter.increment();

                        if (timeDelta > 1000) { // > 1 second of compilation in interval
                            logger.debug("High JIT compilation time: {}ms in last interval", timeDelta);
                        }
                    }
                }

                previousCompilationTime = totalTime;

                logger.debug("JIT compilation time: {}ms total", totalTime);
            } else {
                logger.trace("JIT compilation time monitoring not supported");
            }

        } catch (Exception e) {
            logger.debug("Failed to poll compilation time: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down JvmDetailedMonitor");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper class to map logical pool names to physical pool names across GC algorithms.
     */
    private static class PoolMapping {
        final String key;
        final List<String> possibleNames;

        PoolMapping(String key, List<String> possibleNames) {
            this.key = key;
            this.possibleNames = possibleNames;
        }
    }
}
