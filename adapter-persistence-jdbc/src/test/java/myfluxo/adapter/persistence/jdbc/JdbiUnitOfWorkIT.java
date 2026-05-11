package myfluxo.adapter.persistence.jdbc;

import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.errors.UserError;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbiUnitOfWorkIT {

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
    void okResult_commits() {
        var id = UserId.newId();

        Result<User, UserError> result = uow.inTransaction(() -> {
            var user = User.register(id, new Email("alice@example.com"), NOW);
            repo.save(user);
            return Result.ok(user);
        });

        assertThat(result.isOk()).isTrue();
        assertThat(repo.findById(id)).isPresent();
    }

    @Test
    void errResult_rollsBack() {
        var id = UserId.newId();

        Result<User, UserError> result = uow.inTransaction(() -> {
            var user = User.register(id, new Email("alice@example.com"), NOW);
            repo.save(user);
            return Result.err(new UserError.EmailAlreadyTaken(user.email()));
        });

        assertThat(result.isErr()).isTrue();
        assertThat(repo.findById(id))
            .as("rollback should have undone the save")
            .isEmpty();
    }

    @Test
    void thrownException_rollsBackAndRethrows() {
        var id = UserId.newId();

        assertThatThrownBy(() -> uow.inTransaction(() -> {
            var user = User.register(id, new Email("alice@example.com"), NOW);
            repo.save(user);
            throw new RuntimeException("kaboom");
        })).isInstanceOf(RuntimeException.class)
           .hasMessage("kaboom");

        assertThat(repo.findById(id))
            .as("thrown exception should have rolled back")
            .isEmpty();
    }

    @Test
    void nestedInTransaction_reusesOuterTransaction() {
        var id = UserId.newId();

        Result<User, UserError> outer = uow.inTransaction(() ->
            uow.inTransaction(() -> {
                var user = User.register(id, new Email("alice@example.com"), NOW);
                repo.save(user);
                return Result.<User, UserError>ok(user);
            }));

        assertThat(outer.isOk()).isTrue();
        assertThat(repo.findById(id)).isPresent();
    }

    @Test
    void nestedErr_rollsBackTheOuterTransaction() {
        var id = UserId.newId();

        Result<User, UserError> outer = uow.inTransaction(() -> {
            Result<User, UserError> inner = uow.inTransaction(() -> {
                var user = User.register(id, new Email("alice@example.com"), NOW);
                repo.save(user);
                return Result.err(new UserError.AlreadyActive(user.id()));
            });
            return inner;
        });

        assertThat(outer.isErr()).isTrue();
        assertThat(repo.findById(id)).isEmpty();
    }
}
