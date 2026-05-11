package myfluxo.adapter.persistence.jdbc;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable composable predicate — a SQL fragment plus its named bind
 * values. Built via {@link Predicates}; composed via {@link #and},
 * {@link #or}, {@link #not}, and the static {@link #allOf} / {@link #anyOf}
 * combinators.
 *
 * <p>The point is to replace the string-concat dynamic-query pattern:
 * <pre>{@code
 *   var sql = new StringBuilder("... WHERE 1=1");
 *   var binds = new HashMap<String, Object>();
 *   if (categoryFilter.isPresent()) {
 *       sql.append(" AND category = :category");
 *       binds.put("category", categoryFilter.get());
 *   }
 *   // ... 5 more if-branches
 * }</pre>
 * with composable, validated predicates:
 * <pre>{@code
 *   var p = Predicates.on(USERS);
 *   var conditions = new ArrayList<Condition>();
 *   categoryFilter.ifPresent(c -> conditions.add(p.eq("category", c)));
 *   priceMax.ifPresent(max -> conditions.add(p.lt("price", max)));
 *   Condition where = Condition.allOf(conditions);
 *
 *   String sql = USERS.selectAll() + " WHERE " + where.sql();
 *   handle.createQuery(sql).bindMap(where.binds()).mapTo(UserRow.class).list();
 * }</pre>
 *
 * <h2>Identity elements</h2>
 * {@link #TRUE} and {@link #FALSE} are short-circuit identities for
 * {@code and} / {@code or} respectively — combining with them collapses
 * back to the other operand so the rendered SQL stays clean.
 *
 * <h2>Bind keys</h2>
 * {@link Predicates} mints unique bind-parameter names for every
 * primitive predicate it produces. Composing conditions via this class's
 * combinators is safe — a duplicate bind key across composed predicates
 * is treated as a programmer error and fails loud.
 */
public final class Condition {

    /** Tautology — the identity element for {@link #and}. */
    public static final Condition TRUE = new Condition("TRUE", Map.of());

    /** Contradiction — the identity element for {@link #or}. */
    public static final Condition FALSE = new Condition("FALSE", Map.of());

    private final String sql;
    private final Map<String, Object> binds;

    Condition(String sql, Map<String, Object> binds) {
        this.sql = Objects.requireNonNull(sql, "sql");
        // LinkedHashMap → predictable iteration order, helpful for tests
        // and for reading debug-logged SQL alongside its parameters.
        this.binds = Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(binds, "binds")));
    }

    /** The SQL fragment, ready to drop into a WHERE clause. */
    public String sql() {
        return sql;
    }

    /** The named bind values referenced by {@link #sql}. */
    public Map<String, Object> binds() {
        return binds;
    }

    /** Logical AND with another predicate. Identity: {@link #TRUE}. */
    public Condition and(Condition other) {
        Objects.requireNonNull(other, "other");
        if (this == TRUE) return other;
        if (other == TRUE) return this;
        return new Condition(
            "(" + sql + " AND " + other.sql + ")",
            merge(binds, other.binds)
        );
    }

    /** Logical OR with another predicate. Identity: {@link #FALSE}. */
    public Condition or(Condition other) {
        Objects.requireNonNull(other, "other");
        if (this == FALSE) return other;
        if (other == FALSE) return this;
        return new Condition(
            "(" + sql + " OR " + other.sql + ")",
            merge(binds, other.binds)
        );
    }

    /** Logical NOT. */
    public Condition not() {
        return new Condition("NOT (" + sql + ")", binds);
    }

    /**
     * AND of all conditions in the collection. Returns {@link #TRUE} for
     * an empty input — neutral element keeps the call site clean when
     * every filter is optional.
     */
    public static Condition allOf(Collection<Condition> conditions) {
        Condition result = TRUE;
        for (var c : conditions) {
            result = result.and(c);
        }
        return result;
    }

    /**
     * OR of all conditions in the collection. Returns {@link #FALSE} for
     * an empty input.
     */
    public static Condition anyOf(Collection<Condition> conditions) {
        Condition result = FALSE;
        for (var c : conditions) {
            result = result.or(c);
        }
        return result;
    }

    private static Map<String, Object> merge(
        Map<String, Object> a,
        Map<String, Object> b
    ) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var merged = new LinkedHashMap<String, Object>(a.size() + b.size());
        merged.putAll(a);
        for (var e : b.entrySet()) {
            if (merged.containsKey(e.getKey())) {
                throw new IllegalStateException(
                    "Duplicate bind parameter when composing conditions: '"
                    + e.getKey() + "'. This shouldn't happen — Predicates"
                    + " mints unique names. Did you build a Condition by hand?");
            }
            merged.put(e.getKey(), e.getValue());
        }
        return merged;
    }
}
