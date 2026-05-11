package myfluxo.adapter.persistence.jdbc.users;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbiUserRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Jdbi jdbi;
    private JdbiUnitOfWork uow;
    private JdbiUserRepository repo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        uow = new JdbiUnitOfWork(jdbi);
        repo = new JdbiUserRepository(uow);
        jdbi.useHandle(h -> h.execute("DELETE FROM users"));
    }

    @Test
    void savedUser_canBeFoundById() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        repo.save(user);

        var found = repo.findById(user.id());

        assertThat(found).isPresent();
        assertThat(found.get().email().value()).isEqualTo("alice@example.com");
        assertThat(found.get().status()).isInstanceOf(UserStatus.Pending.class);
    }

    @Test
    void findByEmail_isCaseInsensitive() {
        var user = User.register(UserId.newId(), new Email("Mixed@Example.com"), NOW);
        repo.save(user);

        var found = repo.findByEmail(new Email("mixed@example.com"));

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(user.id());
    }

    @Test
    void existsByEmail_reflectsCaseInsensitiveUniqueness() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        repo.save(user);

        assertThat(repo.existsByEmail(new Email("ALICE@example.com"))).isTrue();
        assertThat(repo.existsByEmail(new Email("bob@example.com"))).isFalse();
    }

    @Test
    void save_isUpsertByPrimaryKey() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        repo.save(user);

        user.activate(NOW.plusSeconds(60));
        repo.save(user);

        var found = repo.findById(user.id()).orElseThrow();
        assertThat(found.status()).isInstanceOf(UserStatus.Active.class);
    }

    @Test
    void deactivatedRow_roundTripsWithReason() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        user.deactivate(NOW.plusSeconds(60), "off-boarded");
        repo.save(user);

        var found = repo.findById(user.id()).orElseThrow();

        var deactivated = (UserStatus.Deactivated) found.status();
        assertThat(deactivated.reason()).isEqualTo("off-boarded");
    }

    @Test
    void delete_removesRow() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        repo.save(user);

        repo.delete(user.id());

        assertThat(repo.findById(user.id())).isEmpty();
    }
}
