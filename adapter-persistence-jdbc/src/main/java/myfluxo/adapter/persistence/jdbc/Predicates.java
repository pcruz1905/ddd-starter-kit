package myfluxo.adapter.persistence.jdbc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds primitive {@link Condition}s bound to a {@link Table}.
 * Each predicate validates the column name via {@link Table#col(String)}
 * (typo → {@code IllegalArgumentException} at the call site) and mints
 * a unique bind-parameter id so composing predicates that reference the
 * same component twice (e.g. a BETWEEN over two values, or two
 * independent equality predicates) doesn't collide.
 *
 * <h2>Stateful by design</h2>
 * The bind-parameter sequencer is per-instance — create a fresh
 * {@code Predicates} for every query you build. The class is not thread-safe.
 *
 * <h2>Catalogue</h2>
 * The provided primitives cover the bread-and-butter shapes the kit's
 * repos will use. Anything more exotic — windows, OVER, EXISTS subqueries,
 * dialect-specific operators — should be hand-written in the repo using
 * {@link Table#col(String)} for column references. This class isn't trying
 * to be jOOQ; it's the smallest dynamic-query surface we'd otherwise
 * reinvent in every repo.
 */
public final class Predicates {

    private final Table<?> table;
    private final AtomicInteger paramSeq = new AtomicInteger(0);

    public static Predicates on(Table<?> table) {
        return new Predicates(table);
    }

    private Predicates(Table<?> table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    private String nextParamId(String paramName) {
        return paramName + "_p" + paramSeq.incrementAndGet();
    }

    /** {@code col = :p} — equality. */
    public Condition eq(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " = :" + id, Map.of(id, value));
    }

    /** {@code col <> :p} — inequality. */
    public Condition neq(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " <> :" + id, Map.of(id, value));
    }

    /** {@code LOWER(col) = LOWER(:p)} — case-insensitive equality. */
    public Condition iEq(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(
            "LOWER(" + column + ") = LOWER(:" + id + ")",
            Map.of(id, value)
        );
    }

    /** {@code col > :p}. */
    public Condition gt(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " > :" + id, Map.of(id, value));
    }

    /** {@code col >= :p}. */
    public Condition gte(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " >= :" + id, Map.of(id, value));
    }

    /** {@code col < :p}. */
    public Condition lt(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " < :" + id, Map.of(id, value));
    }

    /** {@code col <= :p}. */
    public Condition lte(String paramName, Object value) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " <= :" + id, Map.of(id, value));
    }

    /** {@code col LIKE :p}. */
    public Condition like(String paramName, String pattern) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " LIKE :" + id, Map.of(id, pattern));
    }

    /** {@code col ILIKE :p} — Postgres case-insensitive LIKE. */
    public Condition iLike(String paramName, String pattern) {
        String column = table.col(paramName);
        String id = nextParamId(paramName);
        return new Condition(column + " ILIKE :" + id, Map.of(id, pattern));
    }

    /** {@code col IS NULL}. */
    public Condition isNull(String paramName) {
        String column = table.col(paramName);
        return new Condition(column + " IS NULL", Map.of());
    }

    /** {@code col IS NOT NULL}. */
    public Condition isNotNull(String paramName) {
        String column = table.col(paramName);
        return new Condition(column + " IS NOT NULL", Map.of());
    }

    /**
     * {@code col IN (:p1, :p2, ...)}. Returns {@link Condition#FALSE}
     * for an empty collection — {@code col IN ()} isn't valid SQL.
     */
    public Condition in(String paramName, Collection<?> values) {
        if (values.isEmpty()) {
            return Condition.FALSE;
        }
        String column = table.col(paramName);
        var binds = new LinkedHashMap<String, Object>(values.size());
        var placeholders = new ArrayList<String>(values.size());
        int idx = 0;
        for (var v : values) {
            String id = nextParamId(paramName) + "_" + idx++;
            placeholders.add(":" + id);
            binds.put(id, v);
        }
        return new Condition(
            column + " IN (" + String.join(", ", placeholders) + ")",
            binds
        );
    }

    /**
     * {@code col BETWEEN :lo AND :hi}. Both bounds inclusive — same
     * semantic as SQL standard {@code BETWEEN}.
     */
    public Condition between(String paramName, Object lo, Object hi) {
        String column = table.col(paramName);
        String idLo = nextParamId(paramName);
        String idHi = nextParamId(paramName);
        var binds = new LinkedHashMap<String, Object>(2);
        binds.put(idLo, lo);
        binds.put(idHi, hi);
        return new Condition(
            column + " BETWEEN :" + idLo + " AND :" + idHi,
            binds
        );
    }
}
