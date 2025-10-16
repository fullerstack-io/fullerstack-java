package io.fullerstack.substrates.benchmark;

import io.fullerstack.substrates.name.InternedName;
import io.fullerstack.substrates.registry.StringSplitTrieRegistry;
import io.fullerstack.substrates.registry.LazyTrieRegistry;
import io.fullerstack.substrates.registry.EagerTrieRegistry;
import io.fullerstack.substrates.registry.FlatMapRegistry;
import io.humainary.substrates.api.Substrates.Name;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark comparing FOUR registry implementations:
 * 1. FlatMapRegistry - plain ConcurrentHashMap (no hierarchy support)
 * 2. StringSplitTrieRegistry - eager trie with string splitting
 * 3. EagerTrieRegistry - eager trie leveraging InternedName parent chain
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
    private FlatMapRegistry<String> flatMapRegistryPopulated;
    private StringSplitTrieRegistry<String> stringSplitRegistryPopulated;
    private EagerTrieRegistry<String> eagerTrieRegistryPopulated;
    private LazyTrieRegistry<String> lazyTrieRegistryPopulated;

    // Empty registries for write tests
    private FlatMapRegistry<String> flatMapRegistryEmpty;
    private StringSplitTrieRegistry<String> stringSplitRegistryEmpty;
    private EagerTrieRegistry<String> eagerTrieRegistryEmpty;
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
        flatMapRegistryPopulated = new FlatMapRegistry<>();
        stringSplitRegistryPopulated = new StringSplitTrieRegistry<>();
        eagerTrieRegistryPopulated = new EagerTrieRegistry<>();
        lazyTrieRegistryPopulated = new LazyTrieRegistry<>();

        for (int i = 0; i < names.length; i++) {
            String value = "value-" + i;
            flatMapRegistryPopulated.put(names[i], value);
            stringSplitRegistryPopulated.put(names[i], value);
            eagerTrieRegistryPopulated.put(names[i], value);
            lazyTrieRegistryPopulated.put(names[i], value);
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        // Fresh empty registries for write tests
        flatMapRegistryEmpty = new FlatMapRegistry<>();
        stringSplitRegistryEmpty = new StringSplitTrieRegistry<>();
        eagerTrieRegistryEmpty = new EagerTrieRegistry<>();
        lazyTrieRegistryEmpty = new LazyTrieRegistry<>();
    }

    // ========== DIRECT LOOKUP (GET) ==========

    @Benchmark
    public String benchmark01_FlatMap_get_shallow() {
        return flatMapRegistryPopulated.get(names[0]); // "kafka"
    }

    @Benchmark
    public String benchmark02_StringSplit_get_shallow() {
        return stringSplitRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark03_Eager_get_shallow() {
        return eagerTrieRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark04_LazyTrie_get_shallow() {
        return lazyTrieRegistryPopulated.get(names[0]);
    }

    @Benchmark
    public String benchmark05_FlatMap_get_deep() {
        return flatMapRegistryPopulated.get(names[4]); // "kafka.broker.1.jvm.heap.used"
    }

    @Benchmark
    public String benchmark06_StringSplit_get_deep() {
        return stringSplitRegistryPopulated.get(names[4]);
    }

    @Benchmark
    public String benchmark07_Eager_get_deep() {
        return eagerTrieRegistryPopulated.get(names[4]);
    }

    @Benchmark
    public String benchmark08_LazyTrie_get_deep() {
        return lazyTrieRegistryPopulated.get(names[4]);
    }

    // ========== INSERTION (PUT) ==========

    @Benchmark
    public void benchmark09_FlatMap_put_shallow() {
        flatMapRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark10_StringSplit_put_shallow() {
        stringSplitRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark11_Eager_put_shallow() {
        eagerTrieRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark12_LazyTrie_put_shallow() {
        lazyTrieRegistryEmpty.put(names[0], "value");
    }

    @Benchmark
    public void benchmark13_FlatMap_put_deep() {
        flatMapRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark14_StringSplit_put_deep() {
        stringSplitRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark15_Eager_put_deep() {
        eagerTrieRegistryEmpty.put(names[4], "value");
    }

    @Benchmark
    public void benchmark16_LazyTrie_put_deep() {
        lazyTrieRegistryEmpty.put(names[4], "value");
    }

    // ========== BULK INSERT ==========

    @Benchmark
    public void benchmark17_FlatMap_bulkInsert() {
        FlatMapRegistry<String> reg = new FlatMapRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    @Benchmark
    public void benchmark18_StringSplit_bulkInsert() {
        StringSplitTrieRegistry<String> reg = new StringSplitTrieRegistry<>();
        for (int i = 0; i < names.length; i++) {
            reg.put(names[i], "value-" + i);
        }
    }

    @Benchmark
    public void benchmark19_Eager_bulkInsert() {
        EagerTrieRegistry<String> reg = new EagerTrieRegistry<>();
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
    public String benchmark21_FlatMap_getOrCreate_hit() {
        return flatMapRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark22_StringSplit_getOrCreate_hit() {
        return stringSplitRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark23_Eager_getOrCreate_hit() {
        return eagerTrieRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark24_LazyTrie_getOrCreate_hit() {
        return lazyTrieRegistryPopulated.getOrCreate(names[4], () -> "new-value");
    }

    @Benchmark
    public String benchmark25_FlatMap_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return flatMapRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark26_StringSplit_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return stringSplitRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark27_Eager_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return eagerTrieRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    @Benchmark
    public String benchmark28_LazyTrie_getOrCreate_miss() {
        Name newName = InternedName.of("kafka.broker.99.new");
        return lazyTrieRegistryEmpty.getOrCreate(newName, () -> "new-value");
    }

    // ========== SUBTREE QUERIES ==========

    @Benchmark
    public Map<Name, String> benchmark29_FlatMap_subtree_shallow() {
        return flatMapRegistryPopulated.subtree(queryPrefix); // "kafka.broker.1" -> 8 results
    }

    @Benchmark
    public Map<Name, String> benchmark30_StringSplit_subtree_shallow() {
        return stringSplitRegistryPopulated.subtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark31_Eager_subtree_shallow() {
        return eagerTrieRegistryPopulated.subtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark32_LazyTrie_subtree_shallow() {
        return lazyTrieRegistryPopulated.getSubtree(queryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark33_FlatMap_subtree_deep() {
        return flatMapRegistryPopulated.subtree(deepQueryPrefix); // "kafka.broker.1.jvm" -> 4 results
    }

    @Benchmark
    public Map<Name, String> benchmark34_StringSplit_subtree_deep() {
        return stringSplitRegistryPopulated.subtree(deepQueryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark35_Eager_subtree_deep() {
        return eagerTrieRegistryPopulated.subtree(deepQueryPrefix);
    }

    @Benchmark
    public Map<Name, String> benchmark36_LazyTrie_subtree_deep() {
        return lazyTrieRegistryPopulated.getSubtree(deepQueryPrefix);
    }

    // ========== MIXED WORKLOAD (80% read, 20% write) ==========

    @Benchmark
    public void benchmark37_FlatMap_mixedWorkload(Blackhole bh) {
        FlatMapRegistry<String> reg = new FlatMapRegistry<>();

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
    public void benchmark38_StringSplit_mixedWorkload(Blackhole bh) {
        StringSplitTrieRegistry<String> reg = new StringSplitTrieRegistry<>();

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
    public void benchmark39_Eager_mixedWorkload(Blackhole bh) {
        EagerTrieRegistry<String> reg = new EagerTrieRegistry<>();

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
    public boolean benchmark41_FlatMap_contains() {
        return flatMapRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark42_StringSplit_contains() {
        return stringSplitRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark43_Eager_contains() {
        return eagerTrieRegistryPopulated.contains(names[4]);
    }

    @Benchmark
    public boolean benchmark44_LazyTrie_contains() {
        return lazyTrieRegistryPopulated.containsKey(names[4]);
    }

    // ========== SIZE OPERATION ==========

    @Benchmark
    public int benchmark45_FlatMap_size() {
        return flatMapRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark46_StringSplit_size() {
        return stringSplitRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark47_Eager_size() {
        return eagerTrieRegistryPopulated.size();
    }

    @Benchmark
    public int benchmark48_LazyTrie_size() {
        return lazyTrieRegistryPopulated.size();
    }
}
