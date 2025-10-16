package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.name.LinkedName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectImplTest {

    @Test
    void shouldCreateSubjectWithAllComponents() {
        Id id = IdImpl.generate();
        Name name = new LinkedName("test-subject", null);
        State state = StateImpl.empty();
        Subject.Type type = Subject.Type.SCOPE;

        Subject subject = new SubjectImpl(id, name, state, type);

        assertThat(subject.id()).isEqualTo(id);
        assertThat((Object) subject.name()).isEqualTo(name);
        assertThat((Object) subject.state()).isEqualTo(state);
        assertThat(subject.type()).isEqualTo(type);
    }

    @Test
    void shouldReturnPartFromName() {
        Name name = new LinkedName("1", new LinkedName("broker", new LinkedName("kafka", null)));
        Subject subject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SOURCE
        );

        assertThat(subject.part()).isEqualTo("1");
    }

    @Test
    void shouldSupportAllSubjectTypes() {
        for (Subject.Type type : Subject.Type.values()) {
            Subject subject = new SubjectImpl(
                IdImpl.generate(),
                new LinkedName("test", null),
                StateImpl.empty(),
                type
            );

            assertThat(subject.type()).isEqualTo(type);
        }
    }

    @Test
    void shouldIncludeStateInSubject() {
        State state = new StateImpl()
            .state(new LinkedName("count", null), 42)
            .state(new LinkedName("active", null), true);

        Subject subject = new SubjectImpl(
            IdImpl.generate(),
            new LinkedName("test", null),
            state,
            Subject.Type.CIRCUIT
        );

        assertThat(subject.state().stream().count()).isEqualTo(2);
    }
}
