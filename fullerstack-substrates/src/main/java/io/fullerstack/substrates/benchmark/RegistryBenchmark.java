package io.fullerstack.substrates.benchmark;

import io.fullerstack.substrates.name.InternedName;
import io.fullerstack.substrates.registry.BaselineNameRegistry;
import io.fullerstack.substrates.registry.LazyTrieRegistry;
import io.fullerstack.substrates.registry.OptimizedNameRegistry;
import io.fullerstack.substrates.registry.SimpleConcurrentMapRegistry;
import io.humainary.substrates.api.Substrates.Name;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark comparing FOUR registry implementations:
 * 1. SimpleConcurrentMapRegistry - plain ConcurrentHashMap
 * 2. BaselineNameRegistry - eager dual-index with string splitting
 * 3. OptimizedNameRegistry - eager dual-index leveraging InternedName structure
 * 4. LazyTrieRegistry - lazy trie with identity map fast path (RECOMMENDED)
 *
 * <p>Tests:
 *   <li>Direct lookup (get)</li>
 *   <li>Insertion (put)</li>
 *   <li>Get-or-create operations</li>
 *   <li>Subtree queries (hierarchical)</li>
 *   <li>Mixed read/write workloads</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 * mvn clean install && java -jar target/benchmarks.jar Registry
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class RegistryBenchmark {

    // Test data - hierarchical Kafka monitoring paths
    private static final String[] PATHS = {
        "kafka",
        "kafka.broker.1",
        "kafka.broker.1.jvm",
        "kafka.broker.1.jvm.heap",
        "kafka.broker.1.jvm.heap.used",
        "kafka.broker.1.jvm.heap.max",
        "kafka.broker.1.partition.0",
        "kafka.broker.1.partition.0.lag",
        "kafka.broker.1.partition.1",
        "kafka.broker.1.partition.1.lag",
        "kafka.broker.2",
        "kafka.broker.2.jvm",
        "kafka.broker.2.jvm.heap",
        "kafka.broker.2.jvm.heap.used",
        "kafka.consumer.group1",
        "kafka.consumer.group1.lag",
        "kafka.producer.client1",
        "kafka.producer.client1.rate",
    };

    // Pre-created Name instances
    private Name[] names;
    private Name queryPrefix;
    private Name deepQueryPrefix;

    // Registry instances (pre-populated for read tests)
    private SimpleConcurrentMapRegistry<String> simpleRegistryPopulated;
    private BaselineNameRegistry<String> baselineRegistryPopulated;
    private OptimizedNameRegistry<String> optimizedRegistryPopulated;
    private LazyTrieRegistry<String> lazyTrieRegistryPopulated;

    // Empty registries for write tests
    private SimpleConcurrentMapRegistry<String> simpleRegistryEmpty;
    private BaselineNameRegistry<String> baselineRegistryEmpty;
    private OptimizedNameRegistry<String> optimizedRegistryEmpty;
    private LazyTrieRegistry<String> lazyTrieRegistryEmpty;

    @Setup(Level.Trial)
    public void setup() {
        // Create Name instances
        names = new Name[PATHS.length];
        for (int i = 0; i < PATHS.length; i++) {
            names[i] = InternedName.of(PATHS[i]);
        }

        queryPrefix = InternedName.of("kafka.broker.1");
        deepQueryPrefix = InternedName.of("kafka.broker.1.jvm");

        // Populate registries for read tests
        simpleRegistryPopulated = new SimpleConcurrentMapRegistry<>();
        baselineRegistryPopulated = new BaselineNameRegistry<>();
        optimizedRegistryPopulated = new OptimizedNameRegistry<>();
        lazyTrieRegistryPopulated = new LazyTrieRegistry<>();

        for (int i = 0; i < names.length; i++) {
            String value = "value-" + i;
            simpleRegistryPopulated.put(names[i], value);
            baselineRegistryPopulated.put(names[i], value);
            optimizedRegistryPopulated.put(names[i], value);
            lazyTrieRegistryPopulated.put(names[i], value);
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Fresh empty registries for write tests
        simpleRegistryEmpty = new SimpleConcurrentMapRegistry<>();
        baselineRegistryEmpty = new BaselineNameRegistry<>();
        optimizedRegistryEmpty = new OptimizedNameRegistry<>();
        lazyTrieRegistryEmpty = new LazyTrieRegistry<>();
    }

    // ========== DIRECT LOOKUP (GET) ==========

    @Benchmark
    public String benchmark01_SimpleMap_get_shallow() {
        return simpleRegistryPopulated.get(names[0]); // "kafka"
    }

    @Benchmark
    public String benchmark02_Baseline_get_shallow() {
        return baselineRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark03_Optimized_get_shallow() {
        return optimizedRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark04_LazyTrie_get_shallow() {
        return lazyTrieRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark05_SimpleMap_get_deep() {
        return simpleRegistryPopulated.get(names[4]); // "kafka.broker.1.jvm.heap.used"
    }

    @Benchmark
    public String benchmark06_Baseline_get_deep() {
        return baselineRegistryPopulated.get(names[4]);
    }

    @Benchmark
    public String benchmark07_Optimized_get_deep() {
        return optimizedRegistryPopulated.get(names[4]);
    }

    @Benchmark
    public String benchmark08_LazyTrie_get_deep() {
        return lazyTrieRegistryPopulated.get(names[4]);
    }

    // ========== INSERTION (PUT) ==========

    @Benchmark
    public void benchmark09_SimpleMap_put_shallow() {
        simpleRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark10_Baseline_put_shallow() {
        baselineRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark11_Optimized_put_shallow() {
        optimizedRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark12_LazyTrie_put_shallow() {
        lazyTrieRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark13_SimpleMap_put_deep() {
        simpleRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark14_Baseline_put_deep() {
        baselineRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark15_Optimized_put_deep() {
        optimizedRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark16_LazyTrie_put_deep() {
        lazyTrieRegistryEmpty.put(names[4], "value");
    }

    // ========== BULK INSERT ==========

    @Benchmark
    public void benchmark17_SimpleMap_bulkInsert() {
        SimpleConcurrentMapRegistry<String> reg = new SimpleConcurrentMapRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    @Benchmark
    public void benchmark18_Baseline_bulkInsert() {
        BaselineNameRegistry<String> reg = new BaselineNameRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    @Benchmark
    public void benchmark19_Optimized_bulkInsert() {
        OptimizedNameRegistry<String> reg = new OptimizedNameRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    @Benchmark
    public void benchmark20_LazyTrie_bulkInsert() {
        LazyTrieRegistry<String> reg = new LazyTrieRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    // ========== GET-OR-CREATE ==========

    @Benchmark
    public String benchmark21_SimpleMap_getOrCreate_hit() {
        return simpleRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark22_Baseline_getOrCreate_hit() {
        return baselineRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark23_Optimized_getOrCreate_hit() {
        return optimizedRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark24_LazyTrie_getOrCreate_hit() {
        return lazyTrieRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark25_SimpleMap_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return simpleRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark26_Baseline_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return baselineRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark27_Optimized_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return optimizedRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark28_LazyTrie_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return lazyTrieRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    // ========== SUBTREE QUERIES ==========

    @Benchmark
    public Map<Name, String> benchmark29_SimpleMap_subtree_shallow() {
        return simpleRegistryPopulated.subtree(queryPrefix); // "kafka.broker.1" -> 8 results
    }

    @Benchmark
    public Map<Name, String> benchmark30_Baseline_subtree_shallow() {
        return baselineRegistryPopulated.subtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark31_Optimized_subtree_shallow() {
        return optimizedRegistryPopulated.subtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark32_LazyTrie_subtree_shallow() {
        return lazyTrieRegistryPopulated.getSubtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark33_SimpleMap_subtree_deep() {
        return simpleRegistryPopulated.subtree(deepQueryPrefix); // "kafka.broker.1.jvm" -> 4 results
    }

    @Benchmark
    public Map<Name, String> benchmark34_Baseline_subtree_deep() {
        return baselineRegistryPopulated.subtree(deepQueryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark35_Optimized_subtree_deep() {
        return optimizedRegistryPopulated.subtree(deepQueryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark36_LazyTrie_subtree_deep() {
        return lazyTrieRegistryPopulated.getSubtree(deepQueryPrefix);
    }

    // ========== MIXED WORKLOAD (80% read, 20% write) ==========

    @Benchmark
    public void benchmark37_SimpleMap_mixedWorkload(Blackhole bh) {
        SimpleConcurrentMapRegistry<String> reg = new SimpleConcurrentMapRegistry<>();

        // Insert base data
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }

        // Mixed workload: 80 reads, 20 writes
        for (int i = 0; i < 80; i++) {
            bh.consume(reg.get(names[i % names.length]));
        }
        for (int i = 0; i < 20; i++) {
            reg.put(names[i % names.length], "updated-" + i);
        }
    }

    @Benchmark
    public void benchmark38_Baseline_mixedWorkload(Blackhole bh) {
        BaselineNameRegistry<String> reg = new BaselineNameRegistry<>();

        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }

        for (int i = 0; i < 80; i++) {
            bh.consume(reg.get(names[i % names.length]));
        }
        for (int i = 0; i < 20; i++) {
            reg.put(names[i % names.length], "updated-" + i);
        }
    }

    @Benchmark
    public void benchmark39_Optimized_mixedWorkload(Blackhole bh) {
        OptimizedNameRegistry<String> reg = new OptimizedNameRegistry<>();

        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }

        for (int i = 0; i < 80; i++) {
            bh.consume(reg.get(names[i % names.length]));
        }
        for (int i = 0; i < 20; i++) {
            reg.put(names[i % names.length], "updated-" + i);
        }
    }

    @Benchmark
    public void benchmark40_LazyTrie_mixedWorkload(Blackhole bh) {
        LazyTrieRegistry<String> reg = new LazyTrieRegistry<>();

        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }

        for (int i = 0; i < 80; i++) {
            bh.consume(reg.get(names[i % names.length]));
        }
        for (int i = 0; i < 20; i++) {
            reg.put(names[i % names.length], "updated-" + i);
        }
    }

    // ========== CONTAINS CHECK ==========

    @Benchmark
    public boolean benchmark41_SimpleMap_contains() {
        return simpleRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark42_Baseline_contains() {
        return baselineRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark43_Optimized_contains() {
        return optimizedRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark44_LazyTrie_contains() {
        return lazyTrieRegistryPopulated.containsKey(names[4]);
    }

    // ========== SIZE OPERATION ==========

    @Benchmark
    public int benchmark45_SimpleMap_size() {
        return simpleRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark46_Baseline_size() {
        return baselineRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark47_Optimized_size() {
        return optimizedRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark48_LazyTrie_size() {
        return lazyTrieRegistryPopulated.size();
    }
}
