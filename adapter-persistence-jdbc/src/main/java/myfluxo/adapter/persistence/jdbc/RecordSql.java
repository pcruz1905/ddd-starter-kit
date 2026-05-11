package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Derives SQL fragments and bind maps from a {@link Record} row type.
 *
 * <p>JDBI handles row-to-record mapping and named-parameter binding
 * natively; this helper fills the one gap JDBI doesn't cover —
 * generating the column-list / placeholder / SET-clause <em>strings</em>
 * from a record class so they stay in lockstep with the record's
 * components.
 *
 * <h2>Naming</h2>
 * Record-component names become parameter names verbatim (camelCase).
 * Column names are derived by converting camelCase → snake_case unless
 * the component is annotated with {@link ColumnName}, in which case the
 * annotation value wins. This matches JDBI's default
 * {@code SnakeCaseColumnNameMatcher} on the read side, so a row produced
 * by {@code selectColumns} round-trips cleanly through
 * {@code ConstructorMapper.factory(rowType)}.
 *
 * <h2>Caching</h2>
 * Nothing is cached. {@code Class#getRecordComponents()} returns the
 * cached component array maintained by the JDK; the per-call cost is a
 * small loop over that array. Profile before adding a {@code ClassValue}
 * if a hot path proves it matters.
 */
public final class RecordSql {

    private RecordSql() {}

    /**
     * Comma-separated DB column names — for {@code SELECT &lt;list&gt;}
     * or the column list in {@code INSERT INTO t (&lt;list&gt;)}.
     */
    public static String selectColumns(Class<? extends Record> rowType) {
        var sb = new StringBuilder();
        for (var rc : rowType.getRecordComponents()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(columnNameOf(rc));
        }
        return sb.toString();
    }

    /**
     * Comma-separated parameter expressions — for {@code VALUES (&lt;list&gt;)}.
     * Default expression is the colon-prefixed component name. Components
     * marked with {@link JsonbColumn} produce {@code CAST(:name AS jsonb)}.
     */
    public static String insertPlaceholders(Class<? extends Record> rowType) {
        var sb = new StringBuilder();
        for (var rc : rowType.getRecordComponents()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(placeholderOf(rc));
        }
        return sb.toString();
    }

    /**
     * {@code column = :param} pairs for an {@code UPDATE … SET} clause.
     * Components whose parameter name appears in {@code exceptParamNames}
     * are skipped — typical exclusions are the primary key and immutable
     * audit columns. {@link JsonbColumn} components emit
     * {@code column = CAST(:name AS jsonb)}.
     */
    public static String updateSet(
        Class<? extends Record> rowType,
        String... exceptParamNames
    ) {
        Set<String> excluded = Set.of(exceptParamNames);
        var sb = new StringBuilder();
        for (var rc : rowType.getRecordComponents()) {
            if (excluded.contains(rc.getName())) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(columnNameOf(rc)).append(" = ").append(placeholderOf(rc));
        }
        return sb.toString();
    }

    /**
     * Component-name → value map for {@code SqlStatement.bindMap(Map)}.
     * Preserves nulls so the bind step matches every placeholder
     * emitted by {@link #insertPlaceholders}.
     */
    public static Map<String, Object> bindMap(Record row) {
        var components = row.getClass().getRecordComponents();
        var map = new LinkedHashMap<String, Object>(components.length);
        for (var rc : components) {
            try {
                map.put(rc.getName(), rc.getAccessor().invoke(row));
            } catch (ReflectiveOperationException e) {
                // Record accessors are public and never throw — if this
                // fires, the runtime is broken in a way no application
                // recovery can address.
                throw new IllegalStateException(
                    "Cannot read record component " + rc.getName()
                        + " on " + row.getClass(),
                    e
                );
            }
        }
        return map;
    }

    /**
     * Component-name → column-name map. Preserves declaration order
     * (LinkedHashMap). Used by {@link Table#col(String)} to validate
     * WHERE-clause references at class-init time.
     */
    public static Map<String, String> columnByParam(Class<? extends Record> rowType) {
        var map = new LinkedHashMap<String, String>();
        for (var rc : rowType.getRecordComponents()) {
            map.put(rc.getName(), columnNameOf(rc));
        }
        return Collections.unmodifiableMap(map);
    }

    private static String placeholderOf(RecordComponent rc) {
        if (rc.getAccessor().isAnnotationPresent(JsonbColumn.class)) {
            return "CAST(:" + rc.getName() + " AS jsonb)";
        }
        return ":" + rc.getName();
    }

    private static String columnNameOf(RecordComponent rc) {
        // JDBI's @ColumnName targets {PARAMETER, FIELD, METHOD} — not
        // RECORD_COMPONENT — so when written on a record component the
        // compiler attaches it to the accessor method (and the field /
        // canonical-constructor parameter). The component reflection
        // object itself carries nothing; the accessor is the readable
        // surface.
        ColumnName ann = rc.getAccessor().getAnnotation(ColumnName.class);
        if (ann != null) return ann.value();
        return toSnakeCase(rc.getName());
    }

    private static String toSnakeCase(String camel) {
        var sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
