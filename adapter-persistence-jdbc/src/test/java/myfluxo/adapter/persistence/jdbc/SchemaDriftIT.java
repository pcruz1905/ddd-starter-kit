package myfluxo.adapter.persistence.jdbc;

import myfluxo.adapter.persistence.jdbc.process.ProcessInstanceRow;
import myfluxo.adapter.persistence.jdbc.users.UserRow;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-validates every registered {@link Table} against the live
 * database schema produced by Flyway. Drift = build break.
 *
 * <p>Catches the silent-failure class described in {@code docs/DDD_GAPS.md}:
 * <ol>
 *     <li>A migration renames a column without updating the row record.</li>
 *     <li>A row component is added without a migration to back it.</li>
 *     <li>A column is dropped while the row record still references it.</li>
 * </ol>
 * Without this check, the breakage surfaces only at query execution —
 * sometimes as a {@code null} return, sometimes as a {@code 42703 column
 * does not exist} error. With this check, the breakage is a clean test
 * failure in CI with a precise message before merge.
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

    @Test
    void everyRegisteredTable_matchesDatabaseSchema() {
        Jdbi jdbi = PostgresContainerSupport.jdbi();
        List<String> problems = new ArrayList<>();

        for (Table<?> table : REGISTERED_TABLES) {
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

        assertThat(problems)
            .as("Schema drift between row records and Flyway-migrated DB:\n  - %s",
                String.join("\n  - ", problems))
            .isEmpty();
    }
}
