# Persistence

> JDBI for declarative SQL. Records as row types. `Table<R>` derives the SQL. Java 25 `ScopedValue` carries the transaction.

No JPA. No JOOQ codegen. No `@Entity`. No "hidden N+1". You write SQL and the kit gives you the ergonomics to keep it tidy.

---

## The four moving parts

| Concern | Class | One-liner |
| --- | --- | --- |
| Connection pool | HikariCP | Lives in `JdbiSetup`, configured from env |
| Query builder | JDBI 3 | Declarative, named-parameter SQL |
| Schema | Flyway 11 | Versioned migrations under `db/migration/` |
| Transactions | `JdbiUnitOfWork` + `ScopedValue` | One `inTransaction` per use case |

---

## Row records

A **row record** is a Java `record` whose components are the columns of a database table. Naming convention: `XxxRow`.

```java
public record UserRow(
    UUID id,
    String email,
    @ColumnName("display_name") String displayName,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
    public static final Table<UserRow> TABLE = Table.of("users", UserRow.class);
}
```

Two rules:

- **Component name = column name in camelCase** (`displayName` ↔ `display_name`). JDBI's `ConstructorMapper` handles the conversion.
- **Override with `@ColumnName`** when the convention doesn't fit (legacy columns, reserved words, prefixes).

Row records are pure data carriers. They never enter the domain layer — repositories map them to/from aggregates at the boundary.

---

## `Table<R>` — derived SQL fragments

`adapter-persistence-jdbc/.../Table.java`

A `Table<R>` bundles a table name + row record class and derives common SQL fragments from the record's components:

```java
public static final Table<UserRow> TABLE = Table.of("users", UserRow.class);

TABLE.selectAll();
// "SELECT id, email, display_name, status, version, created_at, updated_at FROM users"

TABLE.insert();
// "INSERT INTO users (id, email, ...) VALUES (:id, :email, ...)"

TABLE.updateByIdWithVersion("createdAt");
// "UPDATE users SET email = :email, display_name = :displayName, ...
//  WHERE id = :id AND version = :expectedVersion"

TABLE.deleteById();
// "DELETE FROM users WHERE id = :id"

TABLE.col("displayName");
// "display_name"   — validated at class init; renaming a component
//                    without updating call sites throws *before* any
//                    query runs

TABLE.existsWhere(TABLE.col("email") + " = :email");
// "SELECT EXISTS(SELECT 1 FROM users WHERE email = :email)"
```

### Why this and not JOOQ codegen?

| Concern | JOOQ codegen | `Table<R>` |
| --- | --- | --- |
| Type-safe column names | ✅ | ✅ (validated at class-init) |
| Build-time DB / DDL parsing | required | none |
| Generated code in repo | yes | no |
| Custom SQL still possible | yes | yes |
| Coverage | every operation | the common shapes; you write the rest |

`Table<R>` covers ~90% of the type-safety win at 10% of the build cost. The 10% where it doesn't help (joins, window functions, CTEs) you write as raw SQL with `TABLE.col(...)` for column-name safety. The drift gate ([docs/schema-drift.md](schema-drift.md)) catches the gap.

### Conventions baked in

`Table#updateByIdWithVersion` and `Table#deleteById` assume:
- the primary key column is named `id`,
- the optimistic-concurrency column is named `version`.

Both match every aggregate in this kit. Tables that need a different shape write their statements explicitly.

---

## Unit of Work

`application/src/main/java/myfluxo/application/UnitOfWork.java` (port) and `adapter-persistence-jdbc/.../JdbiUnitOfWork.java` (impl).

Every use case wraps its work in:

```java
return uow.inTransaction(() -> {
    var user = users.findByEmail(cmd.email());      // SELECT
    if (user.isEmpty()) return Result.err(...);
    users.save(user.get().withDisplayName(cmd.name())); // UPDATE
    events.publish(new UserRenamed(user.get().id()));    // INSERT into outbox
    return Result.ok(toDto(user.get()));
});
```

`inTransaction` semantics:

| Inner closure returns | Transaction does |
| --- | --- |
| `Result.Ok(...)` | `COMMIT`, returns Ok |
| `Result.Err(...)` | `ROLLBACK`, returns Err |
| throws | `ROLLBACK`, rethrows |

Nested `inTransaction` calls join the outer one (Spring `REQUIRED` semantics — no nested savepoints, no surprise commits).

### `ScopedValue`, not `ThreadLocal`

The transactional `Handle` is propagated through the call stack via a Java 25 `ScopedValue`:

```java
private static final ScopedValue<Handle> CURRENT = ScopedValue.newInstance();

// in inTransaction:
result = ScopedValue.where(CURRENT, h).call(work::get);
```

Why this matters:

- **The binding cannot leak.** `ScopedValue` is immutable for its scope and unbinds automatically when the closure returns. There is no `ThreadLocal.remove()` to forget.
- **Compile-time-visible scope.** A `ScopedValue.where(...).call(...)` block makes the lifetime of the binding obvious in the call-tree.
- **Plays well with virtual threads.** No `InheritableThreadLocal` thrashing on every task; the scoped value is a normal field of the structured-concurrency carrier.

Repositories read the current Handle via the `TransactionalHandle` interface that `JdbiUnitOfWork` also implements:

```java
public final class JdbiUserRepository implements UserRepository {
    private final TransactionalHandle handle;  // same object as the UoW

    @Override
    public Optional<User> findById(UserId id) {
        return handle.withHandle(h -> h.createQuery(USERS.selectAll() + " WHERE id = :id")
            .bind("id", id.value())
            .map(USERS.rowMapperFactory())
            .findOne()
            .map(UserMapper::toDomain));
    }
}
```

Outside a transaction, `withHandle` opens a short-lived handle on the fly. Inside a transaction, it picks up `CURRENT`.

---

## Optimistic concurrency

Every aggregate has a `long version` field. Updates use:

```sql
UPDATE users
   SET email = :email, version = version + 1, updated_at = :updatedAt
 WHERE id = :id AND version = :expectedVersion
```

If two requests load the same aggregate, both modify, and both `save` — the second `UPDATE` returns `rowsAffected = 0` because the version no longer matches. The repository converts that into a domain error (`OptimisticConcurrency`), the use case returns `Result.Err(...)`, the UoW rolls back.

No pessimistic locks. No row contention. No `SELECT ... FOR UPDATE` outside of the outbox.

---

## RecordSql

`adapter-persistence-jdbc/.../RecordSql.java` is the reflection layer that powers `Table<R>`. It:

- reads a record's components,
- honours `@ColumnName` overrides,
- derives the `selectColumns`, `insertPlaceholders`, and `updateSet` strings,
- exposes `columnByParam(rowType)` for the drift gate to consume.

It runs once per row type at class-init and caches results. Nothing reflective happens per-query.

---

## See also

- [`docs/schema-drift.md`](schema-drift.md) — what happens when migrations and row records get out of sync
- [`docs/result-and-errors.md`](result-and-errors.md) — how `inTransaction`'s commit/rollback maps to `Result`
- [`docs/outbox.md`](outbox.md) — how domain events ride the same transaction
