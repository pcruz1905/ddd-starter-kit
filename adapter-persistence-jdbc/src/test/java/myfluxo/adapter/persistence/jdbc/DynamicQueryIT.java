package myfluxo.adapter.persistence.jdbc;

import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.adapter.persistence.jdbc.users.UserRow;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT proving the dynamic-query primitives compose into a
 * working query against Postgres: a search where every filter is
 * independently optional. This is the scenario problem #6 was about —
 * 100 LOC of string-concat replaced with composable conditions.
 *
 * <p>The repo {@link JdbiUserRepository} stays unchanged; this test
 * just demonstrates the pattern any future search endpoint will
 * follow.
 */
class DynamicQueryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Jdbi jdbi;
    private TransactionalHandle tx;
    private JdbiUserRepository userRepo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        tx = new JdbiUnitOfWork(jdbi);
        userRepo = new JdbiUserRepository(tx);
        jdbi.useHandle(h -> h.execute("DELETE FROM users"));

        userRepo.save(User.register(UserId.newId(), new Email("alice@example.com"), NOW));
        userRepo.save(User.register(UserId.newId(), new Email("bob@example.com"), NOW.plusSeconds(60)));
        userRepo.save(User.register(UserId.newId(), new Email("carol@other.org"), NOW.plusSeconds(120)));
    }

    /**
     * Realistic search shape: 3 optional filters, each adds a predicate
     * if present. Without {@link Predicates} this would be 30+ LOC of
     * StringBuilder + bind-map juggling.
     */
    private List<UserRow> search(
        Optional<String> emailContains,
        Optional<String> statusType,
        Optional<Instant> createdSince
    ) {
        var p = Predicates.on(UserRow.TABLE);
        var conditions = new ArrayList<Condition>();

        emailContains.ifPresent(q -> conditions.add(p.iLike("email", "%" + q + "%")));
        statusType.ifPresent(s -> conditions.add(p.eq("statusType", s)));
        createdSince.ifPresent(t -> conditions.add(p.gte("createdAt", t)));

        Condition where = Condition.allOf(conditions);
        String sql = where == Condition.TRUE
            ? UserRow.TABLE.selectAll()
            : UserRow.TABLE.selectAll() + " WHERE " + where.sql();

        return tx.withHandle(h -> h.createQuery(sql)
            .bindMap(where.binds())
            .mapTo(UserRow.class)
            .list());
    }

    @Test
    void noFilters_returnsAllRows() {
        var results = search(Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(results).hasSize(3);
    }

    @Test
    void emailFilter_matchesCaseInsensitiveSubstring() {
        var results = search(Optional.of("EXAMPLE.com"), Optional.empty(), Optional.empty());

        assertThat(results)
            .extracting(UserRow::email)
            .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
    }

    @Test
    void statusFilter_narrowsToActivated() {
        // All users are registered as Pending in setUp; filter by ACTIVE
        // and expect none.
        var results = search(Optional.empty(), Optional.of("ACTIVE"), Optional.empty());
        assertThat(results).isEmpty();
    }

    @Test
    void createdSinceFilter_excludesEarlierRows() {
        var results = search(
            Optional.empty(),
            Optional.empty(),
            Optional.of(NOW.plusSeconds(90))  // excludes alice (NOW) and bob (NOW+60)
        );
        assertThat(results)
            .extracting(UserRow::email)
            .containsExactly("carol@other.org");
    }

    @Test
    void multipleFilters_combineAsAnd() {
        var results = search(
            Optional.of("example"),     // alice + bob
            Optional.of("PENDING"),     // all three
            Optional.of(NOW.plusSeconds(30))  // bob + carol
        );

        // Intersection: bob only.
        assertThat(results)
            .extracting(UserRow::email)
            .containsExactly("bob@example.com");
    }
}
