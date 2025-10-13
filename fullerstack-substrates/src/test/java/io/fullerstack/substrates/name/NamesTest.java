package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    void shouldCreateSimpleName() {
        // When
        Name name = Names.of("test");

        // Then
        assertThat((Object) name).isNotNull();
        assertThat(name.toString()).isEqualTo("test");
    }

    @Test
    void shouldCreateHierarchicalName() {
        // When
        Name name = Names.hierarchical("kafka", "broker", "1");

        // Then
        assertThat((Object) name).isNotNull();
        assertThat(name.toString()).isEqualTo("kafka.broker.1");
    }

    @Test
    void shouldCreateBrokerName() {
        // When
        Name name = Names.broker("1", "jvm.heap");

        // Then
        assertThat((Object) name).isNotNull();
        assertThat(name.toString()).isEqualTo("kafka.broker.1.jvm.heap");
    }

    @Test
    void shouldCreatePartitionName() {
        // When
        Name name = Names.partition("topic-a", 0, "lag");

        // Then
        assertThat((Object) name).isNotNull();
        assertThat(name.toString()).isEqualTo("kafka.partition.topic-a.0.lag");
    }

    @Test
    void shouldCreateClientName() {
        // When
        Name name = Names.client("producer-1", "send");

        // Then
        assertThat((Object) name).isNotNull();
        assertThat(name.toString()).isEqualTo("kafka.client.producer-1.send");
    }
}
