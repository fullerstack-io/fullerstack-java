package io.fullerstack.substrates.id;

import io.humainary.substrates.api.Substrates.Id;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdImplTest {

    @Test
    void shouldGenerateUniqueIds() {
        Set<Id> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(IdImpl.generate());
        }

        assertThat(ids).hasSize(1000);
    }

    @Test
    void shouldCreateIdFromUUID() {
        UUID uuid = UUID.randomUUID();
        IdImpl id = (IdImpl) IdImpl.of(uuid);

        assertThat(id.uuid()).isEqualTo(uuid);
    }

    @Test
    void shouldSupportEquality() {
        UUID uuid = UUID.randomUUID();
        Id id1 = IdImpl.of(uuid);
        Id id2 = IdImpl.of(uuid);

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    void shouldReturnUUIDAsString() {
        IdImpl id = (IdImpl) IdImpl.generate();

        assertThat(id.toString()).isNotNull();
        assertThat(id.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
