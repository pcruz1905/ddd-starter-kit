package myfluxo.adapter.persistence.jdbc;

import myfluxo.kernel.aggregate.AbstractAggregateRoot;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import myfluxo.kernel.id.Identifier;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for {@link JdbiAggregateRepository} that exercise the
 * base class against a minimal aggregate, decoupled from the User
 * domain. Uses a programmatically-created {@code test_aggregates}
 * table — no Flyway migration, no production schema pollution.
 *
 * <p>The User repo IT covers these flows too, but only through the
 * filter of User-specific concerns (email casing, sealed status
 * variants, archive integration). This IT pins the base-class
 * contract on its own so regressions in {@link JdbiAggregateRepository}
 * surface here directly.
 */
class JdbiAggregateRepositoryIT {

    public record TestId(UUID value) implements Identifier<UUID> {
        public static TestId newId() {
            return new TestId(UUID.randomUUID());
        }
    }

    public record TestRow(UUID id, String label, long version) {
        public static final Table<TestRow> TABLE =
            Table.of("test_aggregates", TestRow.class);

        public static TestRow from(TestAggregate a, long newVersion) {
            return new TestRow(a.id().value(), a.label(), newVersion);
        }

        public TestAggregate toAggregate() {
            return TestAggregate.rehydrate(new TestId(id), label, version);
        }
    }

    public static final class TestAggregate extends AbstractAggregateRoot<TestId> {
        private final TestId id;
        private final String label;

        private TestAggregate(TestId id, String label) {
            super();
            this.id = id;
            this.label = label;
        }

        private TestAggregate(TestId id, String label, long version) {
            super(version);
            this.id = id;
            this.label = label;
        }

        public static TestAggregate create(TestId id, String label) {
            return new TestAggregate(id, label);
        }

        public static TestAggregate rehydrate(TestId id, String label, long version) {
            return new TestAggregate(id, label, version);
        }

        @Override public TestId id() { return id; }
        public String label() { return label; }
    }

    static final class TestRepository
        extends JdbiAggregateRepository<TestAggregate, TestId, TestRow> {

        TestRepository(TransactionalHandle tx) {
            super(tx, TestRow.TABLE);
        }

        @Override protected TestRow toRow(TestAggregate a, long v) {
            return TestRow.from(a, v);
        }

        @Override protected TestAggregate toAggregate(TestRow r) {
            return r.toAggregate();
        }

        @Override protected TestId idFromRow(TestRow r) {
            return new TestId(r.id());
        }
    }

    private static Jdbi jdbi;
    private TestRepository repo;

    @BeforeAll
    static void createTable() {
        jdbi = PostgresContainerSupport.jdbi();
        jdbi.registerRowMapper(ConstructorMapper.factory(TestRow.class));
        jdbi.useHandle(h -> h.execute("""
            CREATE TABLE IF NOT EXISTS test_aggregates (
                id UUID PRIMARY KEY,
                label TEXT NOT NULL,
                version BIGINT NOT NULL
            )"""));
    }

    @AfterAll
    static void dropTable() {
        jdbi.useHandle(h -> h.execute("DROP TABLE IF EXISTS test_aggregates"));
    }

    @BeforeEach
    void setUp() {
        repo = new TestRepository(new JdbiUnitOfWork(jdbi));
        jdbi.useHandle(h -> h.execute("DELETE FROM test_aggregates"));
    }

    @Test
    void save_newAggregate_persistsAndAdvancesVersionToOne() {
        var aggregate = TestAggregate.create(TestId.newId(), "hello");
        assertThat(aggregate.isNew()).isTrue();
        assertThat(aggregate.version()).isZero();

        repo.save(aggregate);

        // markPersisted was called: in-memory state advances.
        assertThat(aggregate.isNew()).isFalse();
        assertThat(aggregate.version()).isOne();

        var found = repo.findById(aggregate.id()).orElseThrow();
        assertThat(found.label()).isEqualTo("hello");
        assertThat(found.version()).isOne();
    }

    @Test
    void save_existingAggregate_routesToUpdateAndBumpsVersion() {
        var id = TestId.newId();
        var initial = TestAggregate.create(id, "v1");
        repo.save(initial);

        var loaded = repo.findById(id).orElseThrow();
        assertThat(loaded.version()).isOne();

        // Mutation: in this minimal aggregate we re-rehydrate with a
        // new label and the same version. A real aggregate would have
        // a behavior method; the contract under test is the persistence
        // routing (UPDATE not INSERT).
        var renamed = TestAggregate.rehydrate(id, "v2", loaded.version());
        repo.save(renamed);

        assertThat(renamed.version()).isEqualTo(2L);

        var afterUpdate = repo.findById(id).orElseThrow();
        assertThat(afterUpdate.label()).isEqualTo("v2");
        assertThat(afterUpdate.version()).isEqualTo(2L);
    }

    @Test
    void save_existing_withStaleVersion_throwsOptimisticConcurrencyException() {
        var id = TestId.newId();
        var initial = TestAggregate.create(id, "initial");
        repo.save(initial);  // → version 1

        // Two callers load the same row; first writer wins.
        var loadedA = repo.findById(id).orElseThrow();
        var loadedB = repo.findById(id).orElseThrow();

        repo.save(TestAggregate.rehydrate(id, "A wrote", loadedA.version()));  // → 2

        // B saves with stale version=1; row is at version=2.
        assertThatExceptionOfType(OptimisticConcurrencyException.class)
            .isThrownBy(() -> repo.save(
                TestAggregate.rehydrate(id, "B wrote", loadedB.version())))
            .satisfies(e -> {
                assertThat(e.expectedVersion()).isOne();
                assertThat(e.aggregateId()).isInstanceOf(TestId.class);
            });
    }

    @Test
    void findById_unknown_returnsEmpty() {
        Optional<TestAggregate> found = repo.findById(TestId.newId());
        assertThat(found).isEmpty();
    }

    @Test
    void delete_removesTheRow() {
        var aggregate = TestAggregate.create(TestId.newId(), "soon-gone");
        repo.save(aggregate);
        assertThat(repo.findById(aggregate.id())).isPresent();

        repo.delete(aggregate.id());

        assertThat(repo.findById(aggregate.id())).isEmpty();
    }

    @Test
    void delete_unknownId_isSilentNoOp() {
        // Idempotent delete: no row → no exception, no rows affected.
        repo.delete(TestId.newId());
    }

    @Test
    void restore_insertsRowEvenWhenAggregateIsNotNew() {
        var id = TestId.newId();
        var fresh = TestAggregate.create(id, "before");
        repo.save(fresh);
        repo.delete(id);

        // Simulate rehydrating from archive: isNew=false, version>0.
        var rehydrated = TestAggregate.rehydrate(id, "from archive", 1L);
        assertThat(rehydrated.isNew()).isFalse();

        repo.restore(rehydrated);

        var found = repo.findById(id).orElseThrow();
        assertThat(found.label()).isEqualTo("from archive");
        // restore bumps version from the rehydrated value.
        assertThat(found.version()).isEqualTo(2L);
    }

    @Test
    void ctor_rejectsTypoInUpdateExcept_atClassInit() {
        // The validation lives in Table.updateByIdWithVersion; verify
        // the base class propagates it cleanly when wired wrong.
        assertThatThrownBy(() ->
            new JdbiAggregateRepository<TestAggregate, TestId, TestRow>(
                new JdbiUnitOfWork(jdbi), TestRow.TABLE, "labl"
            ) {
                @Override protected TestRow toRow(TestAggregate a, long v) { return null; }
                @Override protected TestAggregate toAggregate(TestRow r) { return null; }
                @Override protected TestId idFromRow(TestRow r) { return null; }
            })
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("labl")
            .hasMessageContaining("TestRow");
    }
}
