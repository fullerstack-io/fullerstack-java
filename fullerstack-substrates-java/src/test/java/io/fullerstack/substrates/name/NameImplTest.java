package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameImplTest {

    @Test
    void shouldCreateSimpleName() {
        Name name = NameImpl.of("test");

        assertThat(name.part()).isEqualTo("test");
        assertThat(name.value()).isEqualTo("test");
    }

    @Test
    void shouldSupportHierarchicalNames() {
        Name name = NameImpl.of("kafka", "broker", "1", "jvm", "heap");

        assertThat(name.part()).isEqualTo("heap");
        assertThat(name.value()).isEqualTo("kafka.broker.1.jvm.heap");
    }

    @Test
    void shouldSupportIterableConstruction() {
        List<String> parts = List.of("kafka", "broker", "1");
        Name name = NameImpl.of(parts);

        assertThat(name.value()).isEqualTo("kafka.broker.1");
    }

    @Test
    void shouldSupportMappedConstruction() {
        List<Integer> numbers = List.of(1, 2, 3);
        Name name = NameImpl.of(numbers, Object::toString);

        assertThat(name.value()).isEqualTo("1.2.3");
    }

    @Test
    void shouldSupportEqualityBasedOnPath() {
        Name name1 = NameImpl.of("kafka", "broker");
        Name name2 = NameImpl.of("kafka", "broker");
        Name name3 = NameImpl.of("kafka", "consumer");

        assertThat((Object) name1).isEqualTo(name2);
        assertThat((Object) name1).isNotEqualTo(name3);
        assertThat(name1.hashCode()).isEqualTo(name2.hashCode());
    }

    @Test
    void shouldSupportExtension() {
        Name base = NameImpl.of("kafka");
        Name extended = base.name("broker");

        assertThat(extended.value()).isEqualTo("kafka.broker");
    }

    @Test
    void shouldToStringReturnFullPath() {
        Name name = NameImpl.of("kafka", "broker", "1");

        assertThat(name.toString()).isEqualTo("kafka.broker.1");
    }
}
