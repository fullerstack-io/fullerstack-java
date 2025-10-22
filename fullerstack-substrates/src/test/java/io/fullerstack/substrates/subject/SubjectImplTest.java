package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Scope;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Slot;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.slot.SlotImpl;
import io.fullerstack.substrates.name.NameNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectImplTest {

    @Test
    void shouldCreateSubjectWithAllComponents() {
        Id id = IdImpl.generate();
        Name name = NameNode.of("test-subject");
        State state = StateImpl.empty();
        Class<Scope> type = Scope.class;

        @SuppressWarnings("unchecked")
        Subject<Scope> subject = new SubjectImpl<>(id, name, state, type);

        assertThat(subject.id()).isEqualTo(id);
        assertThat((Object) subject.name()).isEqualTo(name);
        assertThat((Object) subject.state()).isEqualTo(state);
        assertThat(subject.type()).isEqualTo(type);
    }

    @Test
    void shouldReturnPartFromName() {
        Name name = NameNode.of("kafka.broker.1");
        @SuppressWarnings("unchecked")
        Subject<Conduit<Pipe<String>, String>> subject = new SubjectImpl<>(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            (Class<Conduit<Pipe<String>, String>>) (Class<?>) Conduit.class
        );

        assertThat(subject.part()).isEqualTo("1");
    }

    @Test
    void shouldIncludeStateInSubject() {
        Slot<Integer> countSlot = SlotImpl.of(NameNode.of("count"), 42);
        Slot<Boolean> activeSlot = SlotImpl.of(NameNode.of("active"), true);

        State state = StateImpl.empty()
            .state(countSlot)
            .state(activeSlot);

        @SuppressWarnings("unchecked")
        Subject<Circuit> subject = new SubjectImpl<>(
            IdImpl.generate(),
            NameNode.of("test"),
            state,
            Circuit.class
        );

        assertThat(subject.state().stream().count()).isEqualTo(2);
    }
}
