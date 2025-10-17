package io.fullerstack.substrates.benchmark;

import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.name.LinkedName;
import io.humainary.substrates.api.Substrates.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH Benchmark Suite for Fullerstack-Substrates Performance Analysis
 *
 * <p>This benchmark suite measures:
 * <ul>
 *   <li>Cortex creation overhead</li>
 *   <li>Circuit creation and caching performance</li>
 *   <li>Conduit creation and caching performance</li>
 *   <li>Pipe emission latency (hot path)</li>
 *   <li>Channel emission with transformation pipeline</li>
 *   <li>Subscriber callback overhead</li>
 *   <li>Multi-threaded contention</li>
 *   <li>Container management overhead</li>
 * </ul>
 *
 * <p><b>How to Run</b>:
 * <pre>
 * mvn clean test-compile
 * mvn exec:java -Dexec.mainClass="io.fullerstack.substrates.benchmark.SubstratesLoadBenchmark"
 * </pre>
 *
 * <p><b>JMH Configuration</b>:
 * - Warmup: 3 iterations × 2 seconds each
 * - Measurement: 5 iterations × 3 seconds each
 * - Fork: 2 JVM forks for statistical accuracy
 * - Threads: Varies by benchmark
 *
 * @author Winston (Architect)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms512M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=10"})
public class SubstratesLoadBenchmark {

    // ========== Benchmark State ==========

    private Cortex cortex;
    private Circuit cachedCircuit;
    private Conduit<Pipe<Long>, Long> cachedConduit;
    private Pipe<Long> cachedPipe;
    private Name circuitName;
    private Name conduitName;
    private Name channelName;
    private AtomicLong counter;

    /**
     * Setup: Create reusable instances for caching benchmarks.
     */
    @Setup(Level.Trial)
    public void setupTrial() {
        cortex = new CortexRuntime();
        circuitName = cortex.name("benchmark-circuit");
        conduitName = cortex.name("benchmark-conduit");
        channelName = cortex.name("channel-1");
        counter = new AtomicLong(0);

        // Pre-create cached instances
        cachedCircuit = cortex.circuit(circuitName);
        cachedConduit = cachedCircuit.conduit(conduitName, Composer.pipe());
        cachedPipe = cachedConduit.get(channelName);
    }

    /**
     * Teardown: Clean up resources.
     */
    @TearDown(Level.Trial)
    public void teardownTrial() {
        try {
            if (cachedCircuit != null) {
                cachedCircuit.close();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    // ========== Benchmark 1: Cortex Creation ==========

    /**
     * Measures overhead of creating a new Cortex runtime instance.
     * Target: < 1ms
     */
    @Benchmark
    public Cortex benchmark01_cortexCreation() {
        return new CortexRuntime();
    }

    // ========== Benchmark 2: Circuit Creation (Uncached) ==========

    /**
     * Measures circuit creation with unique names (no caching).
     * Target: < 1µs
     */
    @Benchmark
    public Circuit benchmark02_circuitCreation_uncached() {
        // Use counter to generate unique names
        return cortex.circuit(cortex.name("circuit-" + counter.incrementAndGet()));
    }

    // ========== Benchmark 3: Circuit Lookup (Cached) ==========

    /**
     * Measures circuit lookup when cached (same name).
     * Target: < 100ns
     */
    @Benchmark
    public Circuit benchmark03_circuitLookup_cached() {
        return cortex.circuit(circuitName);
    }

    // ========== Benchmark 4: Conduit Creation (Uncached) ==========

    /**
     * Measures conduit creation with unique names.
     * Target: < 10µs
     */
    @Benchmark
    public Conduit<Pipe<Long>, Long> benchmark04_conduitCreation_uncached() {
        return cachedCircuit.conduit(
            cortex.name("conduit-" + counter.incrementAndGet()),
            Composer.pipe()
        );
    }

    // ========== Benchmark 5: Conduit Lookup (Cached) ==========

    /**
     * Measures conduit lookup when cached.
     * Target: < 100ns
     */
    @Benchmark
    public Conduit<Pipe<Long>, Long> benchmark05_conduitLookup_cached() {
        return cachedCircuit.conduit(conduitName, Composer.pipe());
    }

    // ========== Benchmark 6: Pipe Lookup (Cached) ==========

    /**
     * Measures pipe/channel lookup from conduit.
     * Target: < 100ns
     */
    @Benchmark
    public Pipe<Long> benchmark06_pipeLookup_cached() {
        return cachedConduit.get(channelName);
    }

    // ========== Benchmark 7: Pipe Emission (Hot Path) ==========

    /**
     * CRITICAL PATH: Measures emission latency through pipe.
     * This is the hot path for signal emission.
     * Target: < 500ns
     */
    @Benchmark
    public void benchmark07_pipeEmission_hotPath(Blackhole bh) {
        cachedPipe.emit(counter.incrementAndGet());
        bh.consume(counter.get());
    }

    // ========== Benchmark 8: Full Path (Lookup + Emit) ==========

    /**
     * Measures full emission path: circuit → conduit → pipe → emit.
     * Simulates real-world sensor emission pattern.
     * Target: < 1µs
     */
    @Benchmark
    public void benchmark08_fullPath_lookupAndEmit(Blackhole bh) {
        cortex.circuit(circuitName)
              .conduit(conduitName, Composer.pipe())
              .get(channelName)
              .emit(counter.incrementAndGet());
        bh.consume(counter.get());
    }

    // ========== Benchmark 9: Container Operations ==========

    /**
     * Measures container.get() overhead for dynamic entity management.
     * Target: < 200ns
     */
    @Benchmark
    public Pool<Pipe<Long>> benchmark09_containerGet() {
        Container<Pool<Pipe<Long>>, Source<Long>> container =
            cachedCircuit.container(cortex.name("container"), Composer.pipe());
        return container.get(cortex.name("entity-" + (counter.incrementAndGet() % 100)));
    }

    // ========== Benchmark 10: Subscriber Callback Overhead ==========

    /**
     * Measures subscriber registration and callback invocation overhead.
     * Target: < 1µs
     */
    @Benchmark
    public void benchmark10_subscriberCallback(Blackhole bh) {
        Conduit<Pipe<Long>, Long> conduit = cachedCircuit.conduit(
            cortex.name("sub-test"),
            Composer.pipe()
        );

        AtomicLong received = new AtomicLong(0);

        // Create subscriber
        Subscriber<Long> subscriber = cortex.subscriber(
            cortex.name("sub"),
            (subject, registrar) -> registrar.register(received::set)
        );

        conduit.source().subscribe(subscriber);
        conduit.get(cortex.name("ch")).emit(42L);

        bh.consume(received.get());
    }

    // ========== Benchmark 11: Multi-Threaded Contention ==========

    /**
     * Measures contention when multiple threads emit concurrently.
     * Target: Linear scaling (no lock contention)
     */
    @Benchmark
    @Threads(4)
    public void benchmark11_multiThreaded_contention(Blackhole bh) {
        cachedPipe.emit(counter.incrementAndGet());
        bh.consume(counter.get());
    }

    // ========== Benchmark 12: Transformation Pipeline Overhead ==========

    /**
     * Measures overhead of transformation pipeline (map, filter, reduce).
     * Target: < 1µs per transformation
     */
    @Benchmark
    public void benchmark12_transformationPipeline(Blackhole bh) {
        Conduit<Pipe<Long>, Long> transformConduit = cachedCircuit.conduit(
            cortex.name("transform"),
            Composer.pipe(path ->
                path.replace(x -> x * 2)
                    .guard(x -> x > 10)
                    .limit(100)
            )
        );

        transformConduit.get(cortex.name("ch")).emit(counter.incrementAndGet());
        bh.consume(counter.get());
    }

    // ========== Benchmark 13: Name Creation Overhead ==========

    /**
     * Measures overhead of Name creation (cortex.name()).
     * Target: < 50ns
     */
    @Benchmark
    public Name benchmark13_nameCreation() {
        return cortex.name("test-name-" + counter.incrementAndGet());
    }

    // ========== Benchmark 14: Hierarchical Name Creation ==========

    /**
     * Measures overhead of hierarchical names (broker.1.partition.0).
     * Target: < 200ns
     */
    @Benchmark
    public Name benchmark14_hierarchicalNameCreation() {
        return cortex.name("broker." + counter.incrementAndGet() + ".partition.0");
    }

    // ========== Benchmark 15: Conduit Slot - Single Composer (Hot) ==========

    /**
     * Measures conduit lookup with single composer (95% case) - HOT path (cached).
     * This tests the optimized ConduitSlot fast path with identity map.
     * Target: < 10ns (15× faster than old composite key approach)
     */
    @Benchmark
    public Conduit<Pipe<Long>, Long> benchmark15_conduitSlot_singleComposer_hot() {
        // Same name, same composer - hits primary slot (FAST PATH)
        return cachedCircuit.conduit(conduitName, Composer.pipe());
    }

    // ========== Benchmark 16: Conduit Slot - Single Composer (Cold) ==========

    /**
     * Measures conduit creation with single composer - COLD path (first access).
     * Target: < 70µs
     */
    @Benchmark
    public Conduit<Pipe<Long>, Long> benchmark16_conduitSlot_singleComposer_cold() {
        // Unique name each time - creates new conduit with primary slot
        return cachedCircuit.conduit(
            cortex.name("single-slot-" + counter.incrementAndGet()),
            Composer.pipe()
        );
    }

    // ========== Benchmark 17: Conduit Slot - Dual Composer (Hot) ==========

    /**
     * Measures conduit lookup with TWO composers (5% case) - HOT path.
     * First lookup hits primary slot, second hits overflow map.
     * Target: Primary ~5ns, Overflow ~15ns
     */
    @Benchmark
    public void benchmark17_conduitSlot_dualComposer_hot(Blackhole bh) {
        Name sharedName = cortex.name("dual-composer");

        // First composer - primary slot (FAST)
        Conduit<Pipe<Long>, Long> pipes = cachedCircuit.conduit(sharedName, Composer.pipe());
        bh.consume(pipes);

        // Second composer - overflow map (SLOWER but still fast)
        Conduit<Channel<Long>, Long> channels = cachedCircuit.conduit(sharedName, Composer.channel());
        bh.consume(channels);
    }

    // ========== Benchmark 18: Conduit Slot - Dual Composer (Cold) ==========

    /**
     * Measures creating TWO conduits for same name - COLD path.
     * First creates primary slot, second adds to overflow map.
     * Target: < 140µs total (2× single composer)
     */
    @Benchmark
    public void benchmark18_conduitSlot_dualComposer_cold(Blackhole bh) {
        Name uniqueName = cortex.name("dual-cold-" + counter.incrementAndGet());

        // First composer - creates primary slot
        Conduit<Pipe<Long>, Long> pipes = cachedCircuit.conduit(uniqueName, Composer.pipe());
        bh.consume(pipes);

        // Second composer - adds overflow map
        Conduit<Channel<Long>, Long> channels = cachedCircuit.conduit(uniqueName, Composer.channel());
        bh.consume(channels);
    }

    // ========== Benchmark 19: Full Path - Single Slot Optimization ==========

    /**
     * Measures full emission path with optimized conduit slot lookup.
     * Compare with benchmark08 to see improvement from slot optimization.
     * Target: < 35ns (was ~101ns before optimization)
     */
    @Benchmark
    public void benchmark19_fullPath_optimized_singleSlot(Blackhole bh) {
        cortex.circuit(circuitName)
              .conduit(conduitName, Composer.pipe())  // Should be ~5ns now (was ~79ns)
              .get(channelName)
              .emit(counter.incrementAndGet());
        bh.consume(counter.get());
    }

    // ========== Main Entry Point for Manual Execution ==========

    /**
     * Run all benchmarks with JMH.
     *
     * <p>Command line:
     * <pre>
     * mvn exec:java -Dexec.mainClass="io.fullerstack.substrates.benchmark.SubstratesLoadBenchmark"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        // Quick run: 1 fork, 2 warmup, 3 measurement for faster results
        Options opt = new OptionsBuilder()
            .include(SubstratesLoadBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(2)
            .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(2))
            .threads(1)
            .jvmArgs("-Xms512M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=10")
            .shouldFailOnError(true)
            .build();

        new Runner(opt).run();
    }
}
