package myfluxo.kernel.aggregate;

import myfluxo.kernel.id.Identifier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditableAggregateRootTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    record TestId(UUID value) implements Identifier<UUID> {}

    static final class NewAuditable extends AuditableAggregateRoot<TestId> {
        private final TestId id;
        NewAuditable(TestId id, Instant now) { super(now); this.id = id; }
        @Override public TestId id() { return id; }
    }

    static final class RehydratedAuditable extends AuditableAggregateRoot<TestId> {
        private final TestId id;
        RehydratedAuditable(TestId id, long version, Instant createdAt, Instant updatedAt) {
            super(version, createdAt, updatedAt);
            this.id = id;
        }
        @Override public TestId id() { return id; }
    }

    @Test
    void newAggregate_createdAtEqualsUpdatedAt() {
        var agg = new NewAuditable(new TestId(UUID.randomUUID()), NOW);
        assertThat(agg.createdAt()).isEqualTo(NOW);
        assertThat(agg.updatedAt()).isEqualTo(NOW);
        assertThat(agg.isNew()).isTrue();
        assertThat(agg.version()).isZero();
    }

    @Test
    void rehydrated_carriesProvidedTimestamps() {
        var later = NOW.plusSeconds(3600);
        var agg = new RehydratedAuditable(new TestId(UUID.randomUUID()), 5L, NOW, later);
        assertThat(agg.createdAt()).isEqualTo(NOW);
        assertThat(agg.updatedAt()).isEqualTo(later);
        assertThat(agg.version()).isEqualTo(5L);
        assertThat(agg.isNew()).isFalse();
    }

    @Test
    void rehydrated_rejectsUpdatedAtBeforeCreatedAt() {
        assertThatThrownBy(() -> new RehydratedAuditable(
            new TestId(UUID.randomUUID()), 1L, NOW, NOW.minusSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("updatedAt must not predate createdAt");
    }

    @Test
    void touch_advancesUpdatedAtMonotonically() {
        var agg = new NewAuditable(new TestId(UUID.randomUUID()), NOW);
        agg.touch(NOW.plusSeconds(60));
        assertThat(agg.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(agg.createdAt())
            .as("createdAt is immutable")
            .isEqualTo(NOW);
    }

    @Test
    void touch_rejectsBackwardsTime() {
        var agg = new NewAuditable(new TestId(UUID.randomUUID()), NOW);
        agg.touch(NOW.plusSeconds(60));
        assertThatThrownBy(() -> agg.touch(NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("updatedAt cannot move backwards");
    }

    @Test
    void touch_rejectsNull() {
        var agg = new NewAuditable(new TestId(UUID.randomUUID()), NOW);
        assertThatThrownBy(() -> agg.touch(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsNullTimes() {
        assertThatThrownBy(() -> new NewAuditable(new TestId(UUID.randomUUID()), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RehydratedAuditable(
            new TestId(UUID.randomUUID()), 1L, null, NOW))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RehydratedAuditable(
            new TestId(UUID.randomUUID()), 1L, NOW, null))
            .isInstanceOf(NullPointerException.class);
    }
}
