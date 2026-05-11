package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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
 * {@link #bindMap} resolves component accessors once per row class into
 * a {@link MethodHandle} array cached in a {@link ClassValue}. Subsequent
 * binds skip reflection entirely — roughly an order of magnitude faster
 * than {@code Method.invoke} on a hot path.
 */
public final class RecordSql {

    private RecordSql() {}

    /** Per-row-class accessor cache. Built lazily on first bindMap call. */
    private static final ClassValue<RowAccess> ROW_ACCESS = new ClassValue<>() {
        @Override
        protected RowAccess computeValue(Class<?> type) {
            return RowAccess.of(type);
        }
    };

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
     *
     * <p>Accessors are resolved to {@link MethodHandle}s on first call
     * per row class and cached in a {@link ClassValue}; subsequent
     * binds skip reflection on the hot path.
     */
    public static Map<String, Object> bindMap(Record row) {
        RowAccess access = ROW_ACCESS.get(row.getClass());
        var map = new LinkedHashMap<String, Object>(access.size());
        try {
            for (int i = 0; i < access.size(); i++) {
                map.put(access.name(i), access.value(row, i));
            }
        } catch (Throwable t) {
            // MethodHandle.invoke declares Throwable. Record accessors are
            // public, no-arg, and don't throw — if we land here, the JVM
            // is in a state no application recovery can address.
            throw new IllegalStateException(
                "Cannot read record components on " + row.getClass(), t);
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

    /**
     * Pre-compiled accessor handles for a row record class. Built once
     * per class on first {@link #bindMap} call; the instance is cached
     * in {@link #ROW_ACCESS}.
     */
    private record RowAccess(String[] names, MethodHandle[] accessors) {

        static RowAccess of(Class<?> type) {
            if (!type.isRecord()) {
                throw new IllegalArgumentException(
                    "Not a record class: " + type.getName());
            }
            RecordComponent[] components = type.getRecordComponents();
            String[] names = new String[components.length];
            MethodHandle[] accessors = new MethodHandle[components.length];
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            try {
                for (int i = 0; i < components.length; i++) {
                    names[i] = components[i].getName();
                    accessors[i] = lookup.unreflect(components[i].getAccessor());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                    "Cannot reflect on " + type.getName()
                    + ". Row record classes must be public.", e);
            }
            return new RowAccess(names, accessors);
        }

        int size() {
            return names.length;
        }

        String name(int i) {
            return names[i];
        }

        Object value(Object row, int i) throws Throwable {
            return accessors[i].invoke(row);
        }
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
