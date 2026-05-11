package myfluxo.adapter.persistence.jdbc;

import myfluxo.adapter.persistence.jdbc.process.ProcessInstanceRow;
import myfluxo.adapter.persistence.jdbc.users.UserRow;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-validates every registered {@link Table} against the live
 * database schema produced by Flyway. <b>Drift = build break.</b>
 *
 * <p>Catches the silent-failure class described in {@code docs/DDD_GAPS.md}:
 * <ol>
 *     <li>A migration renames a column without updating the row record.</li>
 *     <li>A row component is added without a migration to back it.</li>
 *     <li>A column is dropped while the row record still references it.</li>
 * </ol>
 * Without this check, the breakage surfaces only at query execution —
 * sometimes as a {@code null} return, sometimes as a {@code 42703 column
 * does not exist} error. With this check, the breakage is a JUnit
 * assertion failure → Maven surefire/failsafe non-zero exit → CI red.
 *
 * <h2>Adding a new aggregate</h2>
 * When you add a new {@code Table} constant on a new row record,
 * register it in {@link #REGISTERED_TABLES} below. The check is forward
 * only — extra columns on the DB side (audit triggers, generated
 * columns) are allowed.
 */
class SchemaDriftIT {

    /**
     * Every {@link Table} we expect to map to a real DB table. Updating
     * this list is part of "add a new aggregate" — the check will
     * remind you.
     */
    private static final List<Table<?>> REGISTERED_TABLES = List.of(
        UserRow.TABLE,
        ProcessInstanceRow.TABLE
    );

    /**
     * A drift report — empty {@link #problems} means schema and code
     * agree. Non-empty problems list = build failure.
     */
    record DriftReport(List<String> problems) {
        boolean hasDrift() {
            return !problems.isEmpty();
        }
        String summary() {
            return String.join("\n  - ", problems);
        }
    }

    /**
     * Pure drift-detection logic, extracted from the test method so
     * {@link #check_reportsDriftForDeliberatelyMismatchedTable} can
     * exercise the failure path on a fabricated row record. Production
     * callers pass {@link #REGISTERED_TABLES}.
     */
    static DriftReport check(Jdbi jdbi, List<Table<?>> tables) {
        List<String> problems = new ArrayList<>();

        for (Table<?> table : tables) {
            Set<String> dbColumns = jdbi.withHandle(h -> h.createQuery("""
                    SELECT column_name
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = :tableName
                    """)
                .bind("tableName", table.name())
                .mapTo(String.class)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));

            if (dbColumns.isEmpty()) {
                problems.add(
                    "Table '" + table.name() + "' (row "
                    + table.rowType().getSimpleName()
                    + ") has no columns in the DB. Missing Flyway migration?");
                continue;
            }

            var columnByParam = RecordSql.columnByParam(table.rowType());
            for (var entry : columnByParam.entrySet()) {
                String paramName = entry.getKey();
                String expectedColumn = entry.getValue();

                if (!dbColumns.contains(expectedColumn)) {
                    problems.add(
                        "Drift in '" + table.name() + "': component '"
                        + paramName + "' on " + table.rowType().getSimpleName()
                        + " expects column '" + expectedColumn
                        + "', not present in DB. DB columns: " + dbColumns);
                }
            }
        }

        return new DriftReport(problems);
    }

    @Test
    void everyRegisteredTable_matchesDatabaseSchema() {
        Jdbi jdbi = PostgresContainerSupport.jdbi();

        DriftReport report = check(jdbi, REGISTERED_TABLES);

        assertThat(report.hasDrift())
            .as("Schema drift between row records and Flyway-migrated DB:\n  - %s",
                report.summary())
            .isFalse();
    }

    // ── Failure-path proof ──────────────────────────────────────────────

    /**
     * Row record deliberately drifted from the {@code users} table —
     * the second component would resolve to column {@code fictional}
     * which doesn't exist. Used by the failure-path test below.
     */
    public record DriftedUserRow(UUID id, String fictional) {}

    @Test
    void check_reportsDriftForDeliberatelyMismatchedTable() {
        // Proves the assertion fires when a Table expects a column the
        // DB doesn't have. This is the failure mode CI will trip on
        // when a real migration goes out of sync with a real row record.
        Jdbi jdbi = PostgresContainerSupport.jdbi();
        Table<DriftedUserRow> drifted = Table.of("users", DriftedUserRow.class);

        DriftReport report = check(jdbi, List.of(drifted));

        assertThat(report.hasDrift()).isTrue();
        assertThat(report.problems())
            .as("Expected drift report to mention the missing column")
            .anySatisfy(p -> assertThat(p)
                .contains("fictional")
                .contains("DriftedUserRow")
                .contains("users"));
    }
}
