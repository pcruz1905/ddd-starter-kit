package myfluxo.kernel.aggregate;

import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.id.Identifier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractAggregateRootTest {

    record TestId(UUID value) implements Identifier<UUID> {}
    record SomethingHappened(Instant occurredAt) implements DomainEvent {}

    static final class NewAggregate extends AbstractAggregateRoot<TestId> {
        private final TestId id;
        NewAggregate(TestId id) { super(); this.id = id; }
        @Override public TestId id() { return id; }
        void doThing() { recordEvent(new SomethingHappened(Instant.EPOCH)); }
    }

    static final class RehydratedAggregate extends AbstractAggregateRoot<TestId> {
        private final TestId id;
        RehydratedAggregate(TestId id, long version) { super(version); this.id = id; }
        @Override public TestId id() { return id; }
    }

    @Test
    void newAggregate_startsAtVersion0AndIsNew() {
        var agg = new NewAggregate(new TestId(UUID.randomUUID()));
        assertThat(agg.version()).isEqualTo(0L);
        assertThat(agg.isNew()).isTrue();
    }

    @Test
    void rehydrated_carriesLoadedVersionAndIsNotNew() {
        var agg = new RehydratedAggregate(new TestId(UUID.randomUUID()), 7L);
        assertThat(agg.version()).isEqualTo(7L);
        assertThat(agg.isNew()).isFalse();
    }

    @Test
    void rehydrated_rejectsNegativeVersion() {
        assertThatThrownBy(() -> new RehydratedAggregate(new TestId(UUID.randomUUID()), -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPersisted_adoptsPassedVersionAndFlipsIsNew() {
        var agg = new NewAggregate(new TestId(UUID.randomUUID()));

        agg.markPersisted(1L);

        assertThat(agg.version()).isEqualTo(1L);
        assertThat(agg.isNew()).isFalse();
    }

    @Test
    void markPersisted_rejectsVersionThatDoesNotStrictlyIncrease() {
        var agg = new RehydratedAggregate(new TestId(UUID.randomUUID()), 5L);

        assertThatThrownBy(() -> agg.markPersisted(5L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agg.markPersisted(4L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordEvent_appendsToPendingQueue() {
        var agg = new NewAggregate(new TestId(UUID.randomUUID()));

        agg.doThing();

        assertThat(agg.hasPendingEvents()).isTrue();
        assertThat(agg.peekEvents()).hasSize(1);
        assertThat(agg.peekEvents().getFirst()).isInstanceOf(SomethingHappened.class);
    }

    @Test
    void pullEvents_returnsAndClears() {
        var agg = new NewAggregate(new TestId(UUID.randomUUID()));
        agg.doThing();
        agg.doThing();

        var pulled = agg.pullEvents();

        assertThat(pulled).hasSize(2);
        assertThat(agg.hasPendingEvents()).isFalse();
        assertThat(agg.peekEvents()).isEmpty();
    }
}
