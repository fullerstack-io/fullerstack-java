package io.fullerstack.substrates.benchmark;

import io.fullerstack.substrates.name.SegmentArrayName;
import io.fullerstack.substrates.name.InternedName;
import io.fullerstack.substrates.name.LinkedName;
import io.fullerstack.substrates.name.LRUCachedName;
import io.humainary.substrates.api.Substrates.Name;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark comparing ALL FOUR Name implementations:
 * LinkedName, SegmentArrayName, LRUCachedName, and InternedName
 *
 * Each test is run for all four implementations to ensure fair comparison.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class NameImplementationBenchmark {

    // Test data
    private static final String SIMPLE_PATH = "kafka";
    private static final String DEEP_PATH = "kafka.broker.1.jvm.heap.used";
    private static final String VERY_DEEP_PATH = "kafka.broker.1.partition.2.replica.3.metrics.lag.current";

    // Pre-created instances for warm-path tests
    private LinkedName linkedSimple, linkedDeep, linkedVeryDeep, linkedPrefix;
    private SegmentArrayName segmentSimple, segmentDeep, segmentVeryDeep, segmentPrefix;
    private LRUCachedName lruSimple, lruDeep, lruVeryDeep, lruPrefix;
    private InternedName internedSimple, internedDeep, internedVeryDeep, internedPrefix;

    @Setup
    public void setup() {
        // Pre-create instances
        linkedSimple = new LinkedName(SIMPLE_PATH, null);
        linkedDeep = createLinkedName(DEEP_PATH);
        linkedVeryDeep = createLinkedName(VERY_DEEP_PATH);
        linkedPrefix = createLinkedName("kafka.broker.1");

        segmentSimple = SegmentArrayName.of(SIMPLE_PATH);
        segmentDeep = SegmentArrayName.of(DEEP_PATH);
        segmentVeryDeep = SegmentArrayName.of(VERY_DEEP_PATH);
        segmentPrefix = SegmentArrayName.of("kafka.broker.1");

        lruSimple = LRUCachedName.of(SIMPLE_PATH);
        lruDeep = LRUCachedName.of(DEEP_PATH);
        lruVeryDeep = LRUCachedName.of(VERY_DEEP_PATH);
        lruPrefix = LRUCachedName.of("kafka.broker.1");

        internedSimple = InternedName.of(SIMPLE_PATH);
        internedDeep = InternedName.of(DEEP_PATH);
        internedVeryDeep = InternedName.of(VERY_DEEP_PATH);
        internedPrefix = InternedName.of("kafka.broker.1");
    }

    private LinkedName createLinkedName(String path) {
        String[] parts = path.split("\\.");
        LinkedName current = new LinkedName(parts[0], null);
        for (int i = 1; i < parts.length; i++) {
            current = new LinkedName(parts[i], current);
        }
        return current;
    }

    // ========== CREATION - SIMPLE PATH ==========

    @Benchmark
    public Name benchmark01_LinkedName_createSimple() {
        return new LinkedName(SIMPLE_PATH, null);
    }

    @Benchmark
    public Name benchmark02_SegmentArray_createSimple() {
        return SegmentArrayName.of(SIMPLE_PATH);
    }

    @Benchmark
    public Name benchmark03_LRUCached_createSimple() {
        return LRUCachedName.of(SIMPLE_PATH);
    }

    @Benchmark
    public Name benchmark04_Interned_createSimple() {
        return InternedName.of(SIMPLE_PATH);
    }

    // ========== CREATION - DEEP PATH ==========

    @Benchmark
    public Name benchmark05_LinkedName_createDeep() {
        return createLinkedName(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark06_SegmentArray_createDeep() {
        return SegmentArrayName.of(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark07_LRUCached_createDeep() {
        return LRUCachedName.of(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark08_Interned_createDeep() {
        return InternedName.of(DEEP_PATH);
    }

    // ========== CREATION - VERY DEEP PATH ==========

    @Benchmark
    public Name benchmark09_LinkedName_createVeryDeep() {
        return createLinkedName(VERY_DEEP_PATH);
    }

    @Benchmark
    public Name benchmark10_SegmentArray_createVeryDeep() {
        return SegmentArrayName.of(VERY_DEEP_PATH);
    }

    @Benchmark
    public Name benchmark11_LRUCached_createVeryDeep() {
        return LRUCachedName.of(VERY_DEEP_PATH);
    }

    @Benchmark
    public Name benchmark12_Interned_createVeryDeep() {
        return InternedName.of(VERY_DEEP_PATH);
    }

    // ========== HIERARCHICAL COMPOSITION ==========

    @Benchmark
    public Name benchmark13_LinkedName_hierarchicalCompose() {
        return linkedSimple.name("broker").name("1").name("jvm").name("heap");
    }

    @Benchmark
    public Name benchmark14_SegmentArray_hierarchicalCompose() {
        return segmentSimple.name("broker").name("1").name("jvm").name("heap");
    }

    @Benchmark
    public Name benchmark15_LRUCached_hierarchicalCompose() {
        return lruSimple.name("broker").name("1").name("jvm").name("heap");
    }

    @Benchmark
    public Name benchmark16_Interned_hierarchicalCompose() {
        return internedSimple.name("broker").name("1").name("jvm").name("heap");
    }

    // ========== EQUALS - SAME INSTANCE ==========

    @Benchmark
    public boolean benchmark17_LinkedName_equals_same() {
        return linkedDeep.equals(linkedDeep);
    }

    @Benchmark
    public boolean benchmark18_SegmentArray_equals_same() {
        return segmentDeep.equals(segmentDeep);
    }

    @Benchmark
    public boolean benchmark19_LRUCached_equals_same() {
        return lruDeep.equals(lruDeep);
    }

    @Benchmark
    public boolean benchmark20_Interned_equals_same() {
        return internedDeep.equals(internedDeep);
    }

    // ========== EQUALS - DIFFERENT INSTANCES ==========

    @Benchmark
    public boolean benchmark21_LinkedName_equals_different() {
        return linkedDeep.equals(linkedVeryDeep);
    }

    @Benchmark
    public boolean benchmark22_SegmentArray_equals_different() {
        return segmentDeep.equals(segmentVeryDeep);
    }

    @Benchmark
    public boolean benchmark23_LRUCached_equals_different() {
        return lruDeep.equals(lruVeryDeep);
    }

    @Benchmark
    public boolean benchmark24_Interned_equals_different() {
        return internedDeep.equals(internedVeryDeep);
    }

    // ========== HASHCODE ==========

    @Benchmark
    public int benchmark25_LinkedName_hashCode() {
        return linkedDeep.hashCode();
    }

    @Benchmark
    public int benchmark26_SegmentArray_hashCode() {
        return segmentDeep.hashCode();
    }

    @Benchmark
    public int benchmark27_LRUCached_hashCode() {
        return lruDeep.hashCode();
    }

    @Benchmark
    public int benchmark28_Interned_hashCode() {
        return internedDeep.hashCode();
    }

    // ========== TOSTRING ==========

    @Benchmark
    public String benchmark29_LinkedName_toString() {
        return linkedDeep.toString();
    }

    @Benchmark
    public String benchmark30_SegmentArray_toString() {
        return segmentDeep.toString();
    }

    @Benchmark
    public String benchmark31_LRUCached_toString() {
        return lruDeep.toString();
    }

    @Benchmark
    public String benchmark32_Interned_toString() {
        return internedDeep.toString();
    }

    // ========== PREFIX CHECKS ==========

    @Benchmark
    public boolean benchmark33_LinkedName_prefixCheck() {
        String prefix = linkedPrefix.toString();
        String full = linkedVeryDeep.toString();
        return full.startsWith(prefix);
    }

    @Benchmark
    public boolean benchmark34_SegmentArray_prefixCheck() {
        return segmentVeryDeep.startsWith(segmentPrefix);
    }

    @Benchmark
    public boolean benchmark35_LRUCached_prefixCheck() {
        return lruVeryDeep.startsWith(lruPrefix);
    }

    @Benchmark
    public boolean benchmark36_Interned_prefixCheck() {
        // InternedName doesn't have startsWith, use string comparison
        String prefix = internedPrefix.toString();
        String full = internedVeryDeep.toString();
        return full.startsWith(prefix);
    }

    // ========== DEPTH ==========

    @Benchmark
    public int benchmark37_LinkedName_depth() {
        int depth = 0;
        LinkedName current = linkedVeryDeep;
        while (current != null) {
            depth++;
            current = (LinkedName) current.enclosure().orElse(null);
        }
        return depth;
    }

    @Benchmark
    public int benchmark38_SegmentArray_depth() {
        return segmentVeryDeep.depth();
    }

    @Benchmark
    public int benchmark39_LRUCached_depth() {
        // LRUCachedName doesn't have depth(), calculate manually
        int depth = 0;
        Name current = lruVeryDeep;
        while (current != null) {
            depth++;
            current = current.enclosure().orElse(null);
        }
        return depth;
    }

    @Benchmark
    public int benchmark40_Interned_depth() {
        return internedVeryDeep.depth();
    }

    // ========== PATH ACCESS ==========

    @Benchmark
    public String benchmark41_LinkedName_path() {
        return linkedDeep.toString();
    }

    @Benchmark
    public String benchmark42_SegmentArray_path() {
        return segmentDeep.path();
    }

    @Benchmark
    public String benchmark43_LRUCached_path() {
        return lruDeep.path();
    }

    @Benchmark
    public String benchmark44_Interned_path() {
        return internedDeep.path();
    }

    // ========== PART ACCESS ==========

    @Benchmark
    public String benchmark45_LinkedName_part() {
        CharSequence part = linkedDeep.part();
        return part.toString();
    }

    @Benchmark
    public String benchmark46_SegmentArray_part() {
        return segmentDeep.part();
    }

    @Benchmark
    public String benchmark47_LRUCached_part() {
        return lruDeep.part();
    }

    @Benchmark
    public String benchmark48_Interned_part() {
        return internedDeep.part();
    }

    // ========== ALLOCATION PRESSURE ==========

    @Benchmark
    public void benchmark49_LinkedName_allocation1000(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(createLinkedName("kafka.broker." + (i % 10) + ".metrics.lag"));
        }
    }

    @Benchmark
    public void benchmark50_SegmentArray_allocation1000(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(SegmentArrayName.of("kafka.broker." + (i % 10) + ".metrics.lag"));
        }
    }

    @Benchmark
    public void benchmark51_LRUCached_allocation1000(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(LRUCachedName.of("kafka.broker." + (i % 10) + ".metrics.lag"));
        }
    }

    @Benchmark
    public void benchmark52_Interned_allocation1000(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(InternedName.of("kafka.broker." + (i % 10) + ".metrics.lag"));
        }
    }

    // ========== INTERNING EFFECTIVENESS (REPEATED CREATION) ==========

    @Benchmark
    public Name benchmark53_LinkedName_repeatedCreation() {
        return createLinkedName(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark54_SegmentArray_repeatedCreation() {
        return SegmentArrayName.of(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark55_LRUCached_repeatedCreation() {
        return LRUCachedName.of(DEEP_PATH);
    }

    @Benchmark
    public Name benchmark56_Interned_repeatedCreation() {
        return InternedName.of(DEEP_PATH);
    }
}
