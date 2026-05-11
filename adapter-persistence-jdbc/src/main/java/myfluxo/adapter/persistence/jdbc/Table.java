package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.mapper.RowMapperFactory;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pre-built SQL fragments and JDBI row-mapper factory for a
 * record-mapped database table.
 *
 * <p>Bundles the table name and the row record class so the column
 * list, INSERT placeholders, UPDATE SET clause, and full statements
 * for the codebase's standard shapes are derivable in one place.
 * Drop into a {@code public static final} on the row record itself —
 * {@code UserRow.TABLE}, {@code ProcessInstanceRow.TABLE}, etc. — and
 * both the repository and {@code JdbiSetup} read from the same source
 * of truth.
 *
 * <h2>Conventions baked in</h2>
 * The {@code updateByIdWithVersion} and {@code deleteById} helpers
 * assume:
 * <ul>
 *     <li>the primary key column is named {@code id},</li>
 *     <li>the optimistic-concurrency column is named {@code version}.</li>
 * </ul>
 * Both conventions match every aggregate in this kit. Tables that
 * deviate should write their statements explicitly instead.
 */
public final class Table<R extends Record> {

    private final String name;
    private final Class<R> rowType;
    private final String columns;
    private final String placeholders;
    private final Map<String, String> columnByParam;

    public static <R extends Record> Table<R> of(String name, Class<R> rowType) {
        return new Table<>(name, rowType);
    }

    private Table(String name, Class<R> rowType) {
        this.name = Objects.requireNonNull(name, "name");
        this.rowType = Objects.requireNonNull(rowType, "rowType");
        if (!rowType.isRecord()) {
            throw new IllegalArgumentException(
                "rowType must be a record class, was " + rowType.getName());
        }
        this.columnByParam = RecordSql.columnByParam(rowType);
        if (columnByParam.isEmpty()) {
            throw new IllegalArgumentException(
                "Row record " + rowType.getSimpleName()
                + " must declare at least one component");
        }
        this.columns = RecordSql.selectColumns(rowType);
        this.placeholders = RecordSql.insertPlaceholders(rowType);
    }

    public String name() {
        return name;
    }

    public Class<R> rowType() {
        return rowType;
    }

    public String columns() {
        return columns;
    }

    /** {@code SELECT <columns> FROM <name>}. Caller appends WHERE / ORDER BY / LIMIT. */
    public String selectAll() {
        return "SELECT " + columns + " FROM " + name;
    }

    /** {@code INSERT INTO <name> (<columns>) VALUES (<placeholders>)}. */
    public String insert() {
        return "INSERT INTO " + name + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    /**
     * {@code UPDATE <name> SET <set> WHERE id = :id AND version = :expectedVersion}.
     * Requires the row record to have both {@code id} and {@code version}
     * components — fails loud at call time if either is missing.
     * {@code id} is always excluded from the SET clause; pass additional
     * param names in {@code alsoExcept} to exclude immutable columns
     * such as {@code createdAt}. Duplicates are silently deduplicated;
     * names that aren't components on the row record throw.
     */
    public String updateByIdWithVersion(String... alsoExcept) {
        requireComponent("id", "updateByIdWithVersion");
        requireComponent("version", "updateByIdWithVersion");
        Set<String> except = new LinkedHashSet<>();
        except.add("id");
        for (String name : alsoExcept) {
            if (!columnByParam.containsKey(name)) {
                throw new IllegalArgumentException(
                    "alsoExcept name '" + name + "' is not a component on "
                    + rowType.getSimpleName()
                    + ". Valid components: "
                    + String.join(", ", columnByParam.keySet()));
            }
            except.add(name);
        }
        return "UPDATE " + name + " SET "
            + RecordSql.updateSet(rowType, except.toArray(String[]::new))
            + " WHERE id = :id AND version = :expectedVersion";
    }

    /**
     * {@code DELETE FROM <name> WHERE id = :id}. Requires the row
     * record to have an {@code id} component.
     */
    public String deleteById() {
        requireComponent("id", "deleteById");
        return "DELETE FROM " + name + " WHERE id = :id";
    }

    /**
     * Returns the DB column name for a row-record component, validated
     * against the row type. Renaming a component without updating call
     * sites makes {@code col("oldName")} throw at class-init time —
     * before any test or query runs.
     *
     * <p>Typical use in a WHERE fragment:
     * <pre>{@code
     *     private static final String FIND_BY_CREATED_AT =
     *         USERS.selectAll() + " WHERE " + USERS.col("createdAt") + " > :cutoff";
     * }</pre>
     */
    public String col(String paramName) {
        String column = columnByParam.get(paramName);
        if (column == null) {
            throw new IllegalArgumentException(
                "Table '" + name + "' has no component '" + paramName
                + "' on " + rowType.getSimpleName()
                + ". Valid components: " + String.join(", ", columnByParam.keySet())
            );
        }
        return column;
    }

    /**
     * {@code SELECT EXISTS(SELECT 1 FROM <name> WHERE <predicate>)}.
     * The predicate is inserted verbatim — callers compose it however
     * they need, including with {@link #col(String)} for column safety.
     *
     * <p><b>SECURITY:</b> the predicate must be a compile-time literal
     * (typically composed of {@code col(...)} fragments and JDBI named
     * placeholders like {@code :name}). Never interpolate user-supplied
     * input — that's a SQL-injection vector. JDBI named-parameter binding
     * is the safe channel for values.
     */
    public String existsWhere(String predicate) {
        return "SELECT EXISTS(SELECT 1 FROM " + name + " WHERE " + predicate + ")";
    }

    private void requireComponent(String paramName, String calledFrom) {
        if (!columnByParam.containsKey(paramName)) {
            throw new IllegalStateException(
                "Row record " + rowType.getSimpleName()
                + " has no component '" + paramName
                + "', required by " + calledFrom + "."
                + " Valid components: "
                + String.join(", ", columnByParam.keySet()));
        }
    }

    /** Hand to {@code Jdbi#registerRowMapper}; resolves SELECT columns to record components. */
    public RowMapperFactory rowMapperFactory() {
        return ConstructorMapper.factory(rowType);
    }
}
