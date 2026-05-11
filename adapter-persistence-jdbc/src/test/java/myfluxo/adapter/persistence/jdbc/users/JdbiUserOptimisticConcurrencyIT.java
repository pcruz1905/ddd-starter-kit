package myfluxo.adapter.persistence.jdbc.users;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbiUserOptimisticConcurrencyIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Jdbi jdbi;
    private JdbiUserRepository repo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        repo = new JdbiUserRepository(new JdbiUnitOfWork(jdbi));
        jdbi.useHandle(h -> h.execute("DELETE FROM users"));
    }

    @Test
    void newAggregate_persistsAtVersionZero_thenVersionIsAdvancedInMemory() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        assertThat(user.version()).isEqualTo(0L);
        assertThat(user.isNew()).isTrue();

        repo.save(user);

        assertThat(user.version()).isEqualTo(1L);
        assertThat(user.isNew()).isFalse();

        var found = repo.findById(user.id()).orElseThrow();
        assertThat(found.version())
            .as("DB row should reflect the persisted version")
            .isEqualTo(1L);
    }

    @Test
    void staleAggregate_throwsOptimisticConcurrency() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        repo.save(user);

        var clientA = repo.findById(user.id()).orElseThrow();
        var clientB = repo.findById(user.id()).orElseThrow();
        assertThat(clientA.version()).isEqualTo(1L);
        assertThat(clientB.version()).isEqualTo(1L);

        clientA.activate(NOW);
        repo.save(clientA);

        clientB.deactivate(NOW, "race loser");
        assertThatThrownBy(() -> repo.save(clientB))
            .isInstanceOf(OptimisticConcurrencyException.class);
    }
}
