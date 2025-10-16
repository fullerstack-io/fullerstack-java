package io.fullerstack.substrates.pool;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pool;
import io.fullerstack.substrates.name.LinkedName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PoolImplTest {

    @Test
    void shouldReturnSameInstanceForSameName() {
        Pool<String> pool = new PoolImpl<>(name -> "value-" + name.value());

        Name name = new LinkedName("test", null);
        String value1 = pool.get(name);
        String value2 = pool.get(name);

        assertThat(value1).isSameAs(value2);
    }

    @Test
    void shouldReturnDifferentInstancesForDifferentNames() {
        Pool<String> pool = new PoolImpl<>(name -> "value-" + name.value());

        String value1 = pool.get(new LinkedName("test1", null));
        String value2 = pool.get(new LinkedName("test2", null));

        assertThat(value1).isNotEqualTo(value2);
        assertThat(value1).isEqualTo("value-test1");
        assertThat(value2).isEqualTo("value-test2");
    }

    @Test
    void shouldCallFactoryOnlyOnce() {
        AtomicInteger factoryCalls = new AtomicInteger(0);
        Pool<String> pool = new PoolImpl<>(name -> {
            factoryCalls.incrementAndGet();
            return "value";
        });

        Name name = new LinkedName("test", null);
        pool.get(name);
        pool.get(name);
        pool.get(name);

        assertThat(factoryCalls.get()).isEqualTo(1);
    }

    @Test
    void shouldSupportComplexObjects() {
        Pool<ComplexObject> pool = new PoolImpl<>(name -> new ComplexObject(name.value()));

        Name name = new LinkedName("1", new LinkedName("broker", new LinkedName("kafka", null)));
        ComplexObject obj = pool.get(name);

        assertThat(obj.value).isEqualTo("kafka.broker.1");
    }

    @Test
    void shouldHandleNullFactory() {
        Pool<String> pool = new PoolImpl<>(name -> null);

        String value = pool.get(new LinkedName("test", null));

        assertThat(value).isNull();
    }

    @Test
    void shouldSupportConcurrentAccess() throws Exception {
        Pool<String> pool = new PoolImpl<>(name -> "value-" + name.value());
        Name name = new LinkedName("concurrent", null);

        Thread[] threads = new Thread[10];
        String[] results = new String[10];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = pool.get(name);
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // All threads should get the same instance
        for (int i = 1; i < results.length; i++) {
            assertThat(results[i]).isSameAs(results[0]);
        }
    }

    private static class ComplexObject {
        final String value;

        ComplexObject(String value) {
            this.value = value;
        }
    }
}
