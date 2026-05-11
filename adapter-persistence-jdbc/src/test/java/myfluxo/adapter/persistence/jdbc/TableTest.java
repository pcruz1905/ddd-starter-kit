package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Table}.
 *
 * <p>{@link Table} composes {@link RecordSql} into the conventional
 * statement shapes the repos use. The tests pin those shapes so any
 * future change to whitespace / column ordering / convention is
 * intentional.
 */
class TableTest {

    public record AggregateRow(
        UUID id,
        String email,
        long version,
        Instant createdAt
    ) {}

    private static final Table<AggregateRow> AGGREGATES =
        Table.of("aggregates", AggregateRow.class);

    @Test
    void name_returnsTheConfiguredTableName() {
        assertThat(AGGREGATES.name()).isEqualTo("aggregates");
    }

    @Test
    void columns_returnsCommaSeparatedColumnNames() {
        assertThat(AGGREGATES.columns())
            .isEqualTo("id, email, version, created_at");
    }

    @Test
    void selectAll_emitsSelectFromTable() {
        assertThat(AGGREGATES.selectAll())
            .isEqualTo("SELECT id, email, version, created_at FROM aggregates");
    }

    @Test
    void insert_emitsInsertIntoTableWithColumnsAndPlaceholders() {
        assertThat(AGGREGATES.insert()).isEqualTo(
            "INSERT INTO aggregates (id, email, version, created_at) "
            + "VALUES (:id, :email, :version, :createdAt)"
        );
    }

    @Test
    void updateByIdWithVersion_excludesIdAndUsesOptimisticConcurrency() {
        // id is always excluded from SET (you don't UPDATE the primary
        // key); version is included so the new version is written; the
        // WHERE clause guards on the loaded version (:expectedVersion).
        assertThat(AGGREGATES.updateByIdWithVersion()).isEqualTo(
            "UPDATE aggregates SET "
            + "email = :email, version = :version, created_at = :createdAt"
            + " WHERE id = :id AND version = :expectedVersion"
        );
    }

    @Test
    void updateByIdWithVersion_alsoExcludesAdditionalParamNames() {
        // Typical use: exclude createdAt because it is immutable.
        assertThat(AGGREGATES.updateByIdWithVersion("createdAt")).isEqualTo(
            "UPDATE aggregates SET email = :email, version = :version"
            + " WHERE id = :id AND version = :expectedVersion"
        );
    }

    @Test
    void deleteById_emitsConventionalSinglePkDelete() {
        assertThat(AGGREGATES.deleteById())
            .isEqualTo("DELETE FROM aggregates WHERE id = :id");
    }

    @Test
    void rowMapperFactory_isAConstructorMapperForRowType() {
        // Smoke check — the factory is produced; functional behaviour
        // is exercised by the JDBI integration tests.
        assertThat(AGGREGATES.rowMapperFactory()).isNotNull();
    }

    @Test
    void col_returnsSnakeCaseColumnNameForKnownComponent() {
        // Use case in repos: "WHERE " + USERS.col("createdAt") + " < :cutoff"
        // — instead of hard-coding "created_at" and risking typos.
        assertThat(AGGREGATES.col("createdAt")).isEqualTo("created_at");
        assertThat(AGGREGATES.col("email")).isEqualTo("email");
    }

    @Test
    void col_throwsForUnknownParam_atCallTime() {
        // The validator fires lazily on the call. Because real usages
        // sit in `private static final` initializers, the JVM throws
        // at class init — long before any IT executes.
        assertThatThrownBy(() -> AGGREGATES.col("emaol"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("emaol")
            .hasMessageContaining("aggregates")
            .hasMessageContaining("id, email, version, createdAt");
    }

    @Test
    void col_honoursJdbiColumnNameAnnotation() {
        // If a component is annotated, col() returns the override (not
        // the snake-case default).
        record OverrideRow(UUID id, @ColumnName("e_mail") String email) {}
        Table<OverrideRow> t = Table.of("overrides", OverrideRow.class);

        assertThat(t.col("id")).isEqualTo("id");
        assertThat(t.col("email")).isEqualTo("e_mail");
    }

    @Test
    void existsWhere_wrapsPredicateInExistsSelectFromTable() {
        assertThat(AGGREGATES.existsWhere("email = :email"))
            .isEqualTo(
                "SELECT EXISTS(SELECT 1 FROM aggregates WHERE email = :email)"
            );
    }

    @Test
    void existsWhere_passesPredicateVerbatim_supportsRichExpressions() {
        // Case-insensitive equality, LIKE, AND/OR combinations — all
        // pass through as-is so callers retain full SQL expressiveness.
        assertThat(AGGREGATES.existsWhere(
            "LOWER(email) = LOWER(:email) AND version > 0"))
            .isEqualTo("SELECT EXISTS(SELECT 1 FROM aggregates WHERE "
                + "LOWER(email) = LOWER(:email) AND version > 0)");
    }

    // ── Construction-time guards ────────────────────────────────────────

    public record EmptyRow() {}

    public record NoIdRow(String email, long version) {}

    public record NoVersionRow(UUID id, String email) {}

    @Test
    void of_rejectsEmptyComponentRecord() {
        assertThatThrownBy(() -> Table.of("empty", EmptyRow.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EmptyRow")
            .hasMessageContaining("at least one");
    }

    @Test
    void of_rejectsNonRecordType() {
        // Only Record.class itself qualifies as Class<? extends Record>
        // without being an actual record class — defensive guard for the
        // edge case the generic constraint can't catch.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<? extends Record> notARecord = (Class) Record.class;

        assertThatThrownBy(() -> Table.of("foo", notARecord))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("record");
    }

    @Test
    void deleteById_failsLoudIfRowHasNoIdComponent() {
        Table<NoIdRow> t = Table.of("things", NoIdRow.class);
        assertThatThrownBy(t::deleteById)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NoIdRow")
            .hasMessageContaining("id");
    }

    @Test
    void updateByIdWithVersion_failsLoudIfRowHasNoIdComponent() {
        Table<NoIdRow> t = Table.of("things", NoIdRow.class);
        assertThatThrownBy(t::updateByIdWithVersion)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NoIdRow")
            .hasMessageContaining("id");
    }

    @Test
    void updateByIdWithVersion_failsLoudIfRowHasNoVersionComponent() {
        Table<NoVersionRow> t = Table.of("things", NoVersionRow.class);
        assertThatThrownBy(t::updateByIdWithVersion)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NoVersionRow")
            .hasMessageContaining("version");
    }

    @Test
    void updateByIdWithVersion_rejectsExcludedNameThatIsntAComponent() {
        // Typo guard: "createdAty" doesn't match any component on
        // AggregateRow, so the caller almost certainly meant
        // "createdAt". Better to fail loud than silently include the
        // column in the SET clause.
        assertThatThrownBy(() -> AGGREGATES.updateByIdWithVersion("createdAty"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("createdAty")
            .hasMessageContaining("AggregateRow");
    }

    @Test
    void updateByIdWithVersion_acceptsDuplicateExcludedNames() {
        // Caller passes the same param twice (e.g. composing exclude
        // lists). The dedupe should be silent — no exception, and the
        // resulting SET clause should be identical to the single-call
        // version.
        String oneOf = AGGREGATES.updateByIdWithVersion("createdAt");
        String dupeOf = AGGREGATES.updateByIdWithVersion("createdAt", "createdAt");

        assertThat(dupeOf).isEqualTo(oneOf);
    }
}
