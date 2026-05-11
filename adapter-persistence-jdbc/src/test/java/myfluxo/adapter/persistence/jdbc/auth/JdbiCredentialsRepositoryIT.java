package myfluxo.adapter.persistence.jdbc.auth;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbiCredentialsRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PasswordHash HASH_A = PasswordHash.of("$argon2id$test$hashA");
    private static final PasswordHash HASH_B = PasswordHash.of("$argon2id$test$hashB");

    private Jdbi jdbi;
    private JdbiUserRepository userRepo;
    private JdbiCredentialsRepository repo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        var uow = new JdbiUnitOfWork(jdbi);
        userRepo = new JdbiUserRepository(uow);
        repo = new JdbiCredentialsRepository(uow);
        jdbi.useHandle(h -> h.execute("DELETE FROM credentials"));
        jdbi.useHandle(h -> h.execute("DELETE FROM users"));
    }

    @Test
    void saveThenFindByUserId_roundTripsPasswordHashAndTimestamps() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        userRepo.save(user);
        var creds = Credentials.create(user.id(), HASH_A, NOW);

        repo.save(creds);

        var found = repo.findByUserId(user.id()).orElseThrow();
        assertThat(found.passwordHash()).isEqualTo(HASH_A);
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.updatedAt()).isEqualTo(NOW);
        assertThat(found.version()).isOne();
    }

    @Test
    void findByUserId_returnsEmpty_whenNoCredentialsExist() {
        var user = User.register(UserId.newId(), new Email("ghost@example.com"), NOW);
        userRepo.save(user);

        assertThat(repo.findByUserId(user.id())).isEmpty();
    }

    @Test
    void changePassword_updatesHashAndUpdatedAt_andBumpsVersion() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        userRepo.save(user);
        var creds = Credentials.create(user.id(), HASH_A, NOW);
        repo.save(creds);

        // Reload, change, save.
        var loaded = repo.findByUserId(user.id()).orElseThrow();
        var later = NOW.plusSeconds(3600);
        loaded.changePassword(HASH_B, later);
        repo.save(loaded);

        var after = repo.findByUserId(user.id()).orElseThrow();
        assertThat(after.passwordHash()).isEqualTo(HASH_B);
        assertThat(after.updatedAt()).isEqualTo(later);
        assertThat(after.createdAt()).isEqualTo(NOW);  // immutable
        assertThat(after.version()).isEqualTo(2L);
    }

    @Test
    void delete_removesTheRow() {
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        userRepo.save(user);
        repo.save(Credentials.create(user.id(), HASH_A, NOW));
        assertThat(repo.findByUserId(user.id())).isPresent();

        repo.delete(user.id());

        assertThat(repo.findByUserId(user.id())).isEmpty();
    }

    @Test
    void cascadeFromUserDelete_removesCredentialsRow() {
        // The credentials table has ON DELETE CASCADE on user_id. If we
        // hard-delete the User row, the credentials must go with it
        // (no orphaned password hashes for non-existent users).
        var user = User.register(UserId.newId(), new Email("alice@example.com"), NOW);
        userRepo.save(user);
        repo.save(Credentials.create(user.id(), HASH_A, NOW));

        userRepo.delete(user.id());

        assertThat(repo.findByUserId(user.id())).isEmpty();
    }
}
