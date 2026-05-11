package myfluxo.adapter.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredicatesTest {

    public record TestRow(
        UUID id,
        String email,
        Long age,
        Instant createdAt
    ) {}

    private static final Table<TestRow> T = Table.of("rows", TestRow.class);

    @Test
    void eq_emitsColumnEqualsUniqueParam() {
        var p = Predicates.on(T);
        var c = p.eq("email", "alice@example.com");

        assertThat(c.sql()).matches("email = :email_p\\d+");
        assertThat(c.binds()).hasSize(1).containsValue("alice@example.com");
    }

    @Test
    void eq_unknownComponent_throws() {
        var p = Predicates.on(T);
        assertThatThrownBy(() -> p.eq("emaol", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("emaol");
    }

    @Test
    void iEq_lowersBothSides() {
        var p = Predicates.on(T);
        var c = p.iEq("email", "Alice@Example.com");

        assertThat(c.sql()).matches("LOWER\\(email\\) = LOWER\\(:email_p\\d+\\)");
    }

    @Test
    void neq_emitsAngleBrackets() {
        var c = Predicates.on(T).neq("age", 0L);
        assertThat(c.sql()).matches("age <> :age_p\\d+");
    }

    @Test
    void gt_lt_gte_lte_emitTheirOperators() {
        var p = Predicates.on(T);

        assertThat(p.gt("age", 18L).sql()).matches("age > :age_p\\d+");
        assertThat(p.lt("age", 65L).sql()).matches("age < :age_p\\d+");
        assertThat(p.gte("age", 18L).sql()).matches("age >= :age_p\\d+");
        assertThat(p.lte("age", 65L).sql()).matches("age <= :age_p\\d+");
    }

    @Test
    void like_iLike_emitTheirOperators() {
        var p = Predicates.on(T);

        assertThat(p.like("email", "%@example.com").sql())
            .matches("email LIKE :email_p\\d+");
        assertThat(p.iLike("email", "%example%").sql())
            .matches("email ILIKE :email_p\\d+");
    }

    @Test
    void isNull_isNotNull_emitTheirOperators_andBindNothing() {
        var p = Predicates.on(T);
        var nullable = p.isNull("createdAt");
        var present = p.isNotNull("createdAt");

        assertThat(nullable.sql()).isEqualTo("created_at IS NULL");
        assertThat(nullable.binds()).isEmpty();
        assertThat(present.sql()).isEqualTo("created_at IS NOT NULL");
    }

    @Test
    void in_emitsParenList_andBindsAllValues() {
        var p = Predicates.on(T);
        var c = p.in("email", List.of("a@x.com", "b@x.com", "c@x.com"));

        assertThat(c.sql()).matches("email IN \\(:email_p\\d+_0, :email_p\\d+_1, :email_p\\d+_2\\)");
        assertThat(c.binds()).hasSize(3);
    }

    @Test
    void in_emptyCollection_returnsFalse_notAnEmptyInClause() {
        // `col IN ()` isn't valid SQL — Postgres errors out. FALSE keeps
        // the query syntactically valid while preserving semantics (no
        // row matches an empty value set).
        var c = Predicates.on(T).in("email", List.of());
        assertThat(c).isSameAs(Condition.FALSE);
    }

    @Test
    void between_emitsBoundsAndBindsBoth() {
        var c = Predicates.on(T).between("age", 18L, 65L);

        assertThat(c.sql()).matches("age BETWEEN :age_p\\d+ AND :age_p\\d+");
        assertThat(c.binds()).hasSize(2).containsValues(18L, 65L);
    }

    @Test
    void uniqueParamIds_acrossSameComponent_avoidCollision() {
        // Two equality predicates on the same column should compose
        // without bind-key collision.
        var p = Predicates.on(T);
        var a = p.eq("email", "a@x.com");
        var b = p.eq("email", "b@x.com");

        var ab = a.or(b);

        assertThat(ab.binds()).hasSize(2);
        assertThat(ab.sql())
            .matches("\\(email = :email_p\\d+ OR email = :email_p\\d+\\)");
    }

    @Test
    void columnName_honoursTableSnakeCaseDerivation() {
        // Component is `createdAt` — DB column should be `created_at`.
        var c = Predicates.on(T).isNull("createdAt");
        assertThat(c.sql()).startsWith("created_at ");
    }
}
