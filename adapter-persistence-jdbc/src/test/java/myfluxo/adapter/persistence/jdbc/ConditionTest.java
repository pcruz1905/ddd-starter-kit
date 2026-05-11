package myfluxo.adapter.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Condition} composition.
 *
 * <p>Most operators are exercised through {@link Predicates} in
 * {@link PredicatesTest}. This file pins the combinators specifically:
 * AND/OR/NOT, the TRUE/FALSE identities, and the bind-collision guard.
 */
class ConditionTest {

    private static Condition raw(String sql, Map<String, Object> binds) {
        return new Condition(sql, binds);
    }

    @Test
    void true_isIdentityForAnd_eitherSide() {
        var x = raw("x = :p", Map.of("p", 1));

        assertThat(Condition.TRUE.and(x).sql()).isEqualTo("x = :p");
        assertThat(x.and(Condition.TRUE).sql()).isEqualTo("x = :p");
    }

    @Test
    void false_isIdentityForOr_eitherSide() {
        var x = raw("x = :p", Map.of("p", 1));

        assertThat(Condition.FALSE.or(x).sql()).isEqualTo("x = :p");
        assertThat(x.or(Condition.FALSE).sql()).isEqualTo("x = :p");
    }

    @Test
    void and_wrapsInParens_andMergesBinds() {
        var a = raw("a = :pa", Map.of("pa", 1));
        var b = raw("b = :pb", Map.of("pb", 2));

        var both = a.and(b);

        assertThat(both.sql()).isEqualTo("(a = :pa AND b = :pb)");
        assertThat(both.binds()).containsExactly(
            Map.entry("pa", 1),
            Map.entry("pb", 2)
        );
    }

    @Test
    void or_wrapsInParens_andMergesBinds() {
        var a = raw("a = :pa", Map.of("pa", 1));
        var b = raw("b = :pb", Map.of("pb", 2));

        assertThat(a.or(b).sql()).isEqualTo("(a = :pa OR b = :pb)");
    }

    @Test
    void not_wrapsInParens() {
        var a = raw("a = :pa", Map.of("pa", 1));

        assertThat(a.not().sql()).isEqualTo("NOT (a = :pa)");
        assertThat(a.not().binds()).containsExactly(Map.entry("pa", 1));
    }

    @Test
    void allOf_emptyCollection_returnsTrue() {
        assertThat(Condition.allOf(List.of())).isSameAs(Condition.TRUE);
    }

    @Test
    void allOf_singleCondition_returnsIt() {
        var a = raw("a = :pa", Map.of("pa", 1));
        assertThat(Condition.allOf(List.of(a)).sql()).isEqualTo("a = :pa");
    }

    @Test
    void allOf_multipleConditions_andsThemTogether() {
        var a = raw("a = :pa", Map.of("pa", 1));
        var b = raw("b = :pb", Map.of("pb", 2));
        var c = raw("c = :pc", Map.of("pc", 3));

        assertThat(Condition.allOf(List.of(a, b, c)).sql())
            .isEqualTo("((a = :pa AND b = :pb) AND c = :pc)");
    }

    @Test
    void anyOf_emptyCollection_returnsFalse() {
        assertThat(Condition.anyOf(List.of())).isSameAs(Condition.FALSE);
    }

    @Test
    void anyOf_multipleConditions_orsThemTogether() {
        var a = raw("a = :pa", Map.of("pa", 1));
        var b = raw("b = :pb", Map.of("pb", 2));

        assertThat(Condition.anyOf(List.of(a, b)).sql())
            .isEqualTo("(a = :pa OR b = :pb)");
    }

    @Test
    void compose_throwsIfBindKeysCollide() {
        // Bind-name collision is a programmer error — we want it loud,
        // not silently overwriting the value. Predicates mints unique
        // ids so this only fires for hand-built Conditions.
        var a = raw("x = :p", Map.of("p", 1));
        var b = raw("y = :p", Map.of("p", 2));

        assertThatThrownBy(() -> a.and(b))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate bind parameter")
            .hasMessageContaining("'p'");
    }

    @Test
    void notNot_doesNotShortCircuit() {
        // Double negation is left explicit — readers/optimisers can
        // see it; we don't optimise it away because it'd cost more
        // than it saves and obscure the source.
        var a = raw("a", Map.of());
        assertThat(a.not().not().sql()).isEqualTo("NOT (NOT (a))");
    }
}
