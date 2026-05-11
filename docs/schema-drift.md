# The schema drift gate

> A CI check that fails the build when row records and Flyway-migrated tables stop agreeing. Catches a silent class of bug before it reaches a query.

`adapter-persistence-jdbc/src/test/java/myfluxo/adapter/persistence/jdbc/SchemaDriftIT.java`

---

## The problem it solves

Without codegen, the "shape of a table" lives in two places:

1. The Flyway migration that created the table.
2. The row record (`UserRow`, `CredentialsRow`, ...) that maps it.

These two **must agree column-for-column**, but the compiler can't see the database schema, so nothing forces it.

The failure modes when they drift:

| Drift | What you see (without this gate) |
| --- | --- |
| Migration renames `display_name` → `full_name`, row record stays | First query returns `null` for the field, or Postgres yells `42703 column "display_name" does not exist` — at runtime, possibly in production |
| Row record adds a `String middleName` component, no migration | Same as above on INSERT |
| Migration drops a column, row record still references it | Same |
| Migration adds a `NOT NULL` column without a default | INSERTs fail at runtime |

All of these are **silent at compile time**. The build is green. The first test that touches the table fails — or worse, it doesn't, and prod breaks.

---

## How the gate works

`SchemaDriftIT` runs as part of the integration-test phase (`mvn verify`). It:

1. **Boots Postgres** via Testcontainers (reused across runs for speed).
2. **Runs every Flyway migration** against it — the live schema.
3. **For each registered `Table<R>`**, queries `information_schema.columns`:

   ```sql
   SELECT column_name
     FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = :tableName
   ```

4. **Cross-validates**: for every component on the row record, the corresponding column name (camelCase → snake_case, or `@ColumnName` override) must exist in the DB.
5. **Emits a structured report**, not a one-line failure:

   ```
   Schema drift between row records and Flyway-migrated DB:
     - Drift in 'users': component 'displayName' on UserRow expects
       column 'display_name', not present in DB. DB columns:
       [id, email, full_name, status, version, created_at, updated_at]
   ```

A non-empty problems list = JUnit assertion failure = surefire/failsafe non-zero exit = CI red.

---

## What it deliberately *doesn't* check

The check is **forward only**: extra columns on the database side are allowed.

This is by design. The database is allowed to grow audit triggers, generated columns, computed fields, and Postgres-specific extras (`tsvector` indexes, etc.) without forcing every row record to carry them. The gate fails the build if the **record-side** is wrong; the **DB-side** can be richer.

The gate also doesn't check:

- Column types (a `text` column matched against a `record String` is "fine"). Type mismatches surface at query time as JDBC `SQLException`s — those are loud enough.
- Constraints, indexes, foreign keys — out of scope.
- Migration order or checksums — Flyway already does this.

---

## The registration list

```java
private static final List<Table<?>> REGISTERED_TABLES = List.of(
    UserRow.TABLE,
    ProcessInstanceRow.TABLE,
    CredentialsRow.TABLE,
    RefreshTokenRow.TABLE
);
```

This is the only place you update when adding a new aggregate. The gate doesn't auto-discover tables on the classpath — that would be too clever and would silently miss row records that aren't yet wired in.

---

## The failure-path proof

The test class also has a deliberately-drifted row record:

```java
public record DriftedUserRow(UUID id, String fictional) {}

@Test
void check_reportsDriftForDeliberatelyMismatchedTable() {
    Table<DriftedUserRow> drifted = Table.of("users", DriftedUserRow.class);
    DriftReport report = check(jdbi, List.of(drifted));

    assertThat(report.hasDrift()).isTrue();
    assertThat(report.problems())
        .anySatisfy(p -> assertThat(p)
            .contains("fictional")
            .contains("DriftedUserRow")
            .contains("users"));
}
```

This proves the gate **actually fires** when drift exists. A green CI run that never failed isn't proof of anything; this test makes the failure path part of the contract.

---

## Why a CI gate and not a runtime check

Two reasons:

1. **Runtime checks shift cost to the wrong place.** A bad schema deserves to fail the deploy, not the first user's request after deploy.
2. **CI is the contract.** "All tests green" is what reviewers, CI badges, and merge bots trust. Moving drift detection into CI puts it on the same trust footing as every other test.

The check runs on every PR, before merge, exactly once. It costs about 5 seconds beyond what the other ITs already pay.

---

## See also

- [`docs/persistence.md`](persistence.md) — what `Table<R>` and `RecordSql` do
- [`docs/adding-an-aggregate.md`](adding-an-aggregate.md) — the recipe that includes "register in `SchemaDriftIT`"
