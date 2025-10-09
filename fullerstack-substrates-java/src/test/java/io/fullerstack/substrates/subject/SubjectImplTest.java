package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.util.NameImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectImplTest {

    @Test
    void shouldCreateSubjectWithAllComponents() {
        Id id = IdImpl.generate();
        Name name = NameImpl.of("test-subject");
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
        Name name = NameImpl.of("kafka", "broker", "1");
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
                NameImpl.of("test"),
                StateImpl.empty(),
                type
            );

            assertThat(subject.type()).isEqualTo(type);
        }
    }

    @Test
    void shouldIncludeStateInSubject() {
        State state = new StateImpl()
            .state(NameImpl.of("count"), 42)
            .state(NameImpl.of("active"), true);

        Subject subject = new SubjectImpl(
            IdImpl.generate(),
            NameImpl.of("test"),
            state,
            Subject.Type.CIRCUIT
        );

        assertThat(subject.state().stream().count()).isEqualTo(2);
    }
}
