package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecordSql}.
 *
 * <p>Documents the column-name convention (camelCase → snake_case),
 * the JDBI {@link ColumnName} override, and the SQL-fragment shapes
 * downstream repositories depend on.
 */
class RecordSqlTest {

    /** Simple row — all defaults, snake_case derivation only. */
    record SimpleRow(UUID id, String email, long version) {}

    /** Multi-word components — exercises camelCase → snake_case. */
    record MultiWordRow(
        UUID id,
        String firstName,
        String lastName,
        Instant createdAtUtc
    ) {}

    /** ColumnName override — one component renamed, others derived. */
    record AnnotatedRow(
        UUID id,
        @ColumnName("e_mail") String email,
        @ColumnName("ver") long version
    ) {}

    /** Single-component edge case — no separator expected. */
    record SingleRow(UUID id) {}

    /** Postgres jsonb column — placeholder must wrap in a CAST. */
    record JsonbRow(
        UUID id,
        @JsonbColumn String state,
        String label
    ) {}

    @Test
    void selectColumns_joinsAllColumnsWithCommaAndSpace() {
        assertThat(RecordSql.selectColumns(SimpleRow.class))
            .isEqualTo("id, email, version");
    }

    @Test
    void selectColumns_convertsCamelCaseToSnakeCase() {
        assertThat(RecordSql.selectColumns(MultiWordRow.class))
            .isEqualTo("id, first_name, last_name, created_at_utc");
    }

    @Test
    void selectColumns_honoursColumnNameAnnotation() {
        assertThat(RecordSql.selectColumns(AnnotatedRow.class))
            .isEqualTo("id, e_mail, ver");
    }

    @Test
    void selectColumns_singleComponentHasNoSeparator() {
        assertThat(RecordSql.selectColumns(SingleRow.class))
            .isEqualTo("id");
    }

    @Test
    void insertPlaceholders_emitsColonPrefixedComponentNames() {
        // Param names track record components (camelCase), NOT columns.
        // JDBI binding is by param name; column name is only for the
        // SQL projection.
        assertThat(RecordSql.insertPlaceholders(MultiWordRow.class))
            .isEqualTo(":id, :firstName, :lastName, :createdAtUtc");
    }

    @Test
    void insertPlaceholders_annotationDoesNotAffectParamName() {
        // @ColumnName changes the COLUMN name, not the parameter name.
        // The binder still binds by record-component name.
        assertThat(RecordSql.insertPlaceholders(AnnotatedRow.class))
            .isEqualTo(":id, :email, :version");
    }

    @Test
    void updateSet_emitsColumnEqualsParamForAllComponents() {
        assertThat(RecordSql.updateSet(MultiWordRow.class))
            .isEqualTo(
                "id = :id, "
                + "first_name = :firstName, "
                + "last_name = :lastName, "
                + "created_at_utc = :createdAtUtc"
            );
    }

    @Test
    void updateSet_excludesNamedParamsFromExceptList() {
        // Typical use: exclude the primary key and immutable created_at.
        assertThat(RecordSql.updateSet(MultiWordRow.class, "id", "createdAtUtc"))
            .isEqualTo("first_name = :firstName, last_name = :lastName");
    }

    @Test
    void updateSet_unknownExceptNameIsIgnored() {
        // Exclusion list is a filter, not an assertion. A typo doesn't
        // explode — the SET clause just contains everything.
        assertThat(RecordSql.updateSet(SimpleRow.class, "nonExistent"))
            .isEqualTo("id = :id, email = :email, version = :version");
    }

    @Test
    void updateSet_honoursColumnNameAnnotation() {
        assertThat(RecordSql.updateSet(AnnotatedRow.class))
            .isEqualTo("id = :id, e_mail = :email, ver = :version");
    }

    @Test
    void bindMap_extractsAllComponentValuesByParamName() {
        var id = UUID.randomUUID();
        var row = new SimpleRow(id, "user@example.com", 3L);

        var map = RecordSql.bindMap(row);

        assertThat(map).containsExactly(
            entry("id", id),
            entry("email", "user@example.com"),
            entry("version", 3L)
        );
    }

    @Test
    void bindMap_preservesNullComponentValues() {
        // INSERT/UPDATE need to write nulls explicitly; the binder must
        // not silently drop null entries or the SQL parameter mapping
        // becomes ambiguous.
        record NullableRow(UUID id, String maybeNull) {}
        var id = UUID.randomUUID();
        var row = new NullableRow(id, null);

        var map = RecordSql.bindMap(row);

        assertThat(map).containsKeys("id", "maybeNull");
        assertThat(map.get("maybeNull")).isNull();
    }

    @Test
    void bindMap_paramNameMatchesInsertPlaceholders() {
        // Contract: every name in insertPlaceholders(C) must appear as
        // a key in bindMap(row) of any C instance. This guarantees the
        // INSERT statement is fully bound.
        var row = new MultiWordRow(UUID.randomUUID(), "A", "B", Instant.EPOCH);
        var map = RecordSql.bindMap(row);

        for (var rc : MultiWordRow.class.getRecordComponents()) {
            assertThat(map).containsKey(rc.getName());
        }
    }

    @Test
    void insertPlaceholders_jsonbColumnEmitsCastExpression() {
        assertThat(RecordSql.insertPlaceholders(JsonbRow.class))
            .isEqualTo(":id, CAST(:state AS jsonb), :label");
    }

    @Test
    void updateSet_jsonbColumnEmitsCastExpression() {
        assertThat(RecordSql.updateSet(JsonbRow.class, "id"))
            .isEqualTo("state = CAST(:state AS jsonb), label = :label");
    }

    @Test
    void selectColumns_jsonbColumnIsPlainColumnNameOnReadSide() {
        // Cast is for write-side bind; SELECT reads jsonb as text via
        // the Postgres JDBC driver's String mapper, no SQL cast needed.
        assertThat(RecordSql.selectColumns(JsonbRow.class))
            .isEqualTo("id, state, label");
    }

    private static <K, V> java.util.Map.Entry<K, V> entry(K k, V v) {
        return java.util.Map.entry(k, v);
    }
}
