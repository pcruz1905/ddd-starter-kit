package myfluxo.application.users;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDomainEventPublisher;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.application.users.commands.RegisterUserCommand;
import myfluxo.application.users.usecases.RegisterUser;
import myfluxo.domain.users.User;
import myfluxo.domain.users.errors.UserError;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test of the {@code RegisterUser} use case
 * against real Postgres (via Testcontainers).
 *
 * <p>The use case has no idempotency concept — that lives in the HTTP
 * layer (see {@code IdempotencyMiddleware} and the route-level tests).
 */
class RegisterUserIT {

    private Jdbi jdbi;
    private RegisterUser sut;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        var uow = new JdbiUnitOfWork(jdbi);
        var users = new JdbiUserRepository(uow);
        var events = new JdbiOutboxDomainEventPublisher(uow);
        sut = new RegisterUser(users, uow, events, Clock.systemUTC());

        jdbi.useHandle(h -> {
            h.execute("DELETE FROM entity_archive");
            h.execute("DELETE FROM outbox_events");
            h.execute("DELETE FROM users");
            h.execute("DELETE FROM idempotency_cache");
        });
    }

    @Test
    void happyPath_persistsUserAndEmitsRegisteredEventToOutbox() {
        var result = sut.handle(RegisterUserCommand.of("alice@example.com"));

        assertThat(result.isOk()).isTrue();
        var user = ((Result.Ok<User, UserError>) result).value();

        long users = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM users WHERE id = :id")
            .bind("id", user.id().value())
            .mapTo(Long.class)
            .one());
        assertThat(users).isEqualTo(1L);

        long events = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM outbox_events"
                + " WHERE event_type LIKE '%UserEvent$Registered'")
            .mapTo(Long.class)
            .one());
        assertThat(events).isEqualTo(1L);
    }

    @Test
    void duplicateEmail_returnsTypedErrorAndEmitsNoEvent() {
        sut.handle(RegisterUserCommand.of("alice@example.com"));
        jdbi.useHandle(h -> h.execute("DELETE FROM outbox_events"));

        var result = sut.handle(RegisterUserCommand.of("alice@example.com"));

        assertThat(result.isErr()).isTrue();
        var error = ((Result.Err<User, UserError>) result).error();
        assertThat(error).isInstanceOf(UserError.EmailAlreadyTaken.class);

        long events = jdbi.withHandle(h -> h.createQuery(
            "SELECT COUNT(*) FROM outbox_events").mapTo(Long.class).one());
        assertThat(events).isZero();
    }

    @Test
    void invalidEmail_returnsTypedErrorAndEmitsNoEvent() {
        var result = sut.handle(RegisterUserCommand.of("not-an-email"));

        assertThat(result.isErr()).isTrue();
        var error = ((Result.Err<User, UserError>) result).error();
        assertThat(error).isInstanceOf(UserError.InvalidEmail.class);
        assertThat(((UserError.InvalidEmail) error).input()).isEqualTo("not-an-email");

        long events = jdbi.withHandle(h -> h.createQuery(
            "SELECT COUNT(*) FROM outbox_events").mapTo(Long.class).one());
        assertThat(events).isZero();
    }

    @Test
    void blankEmail_returnsTypedErrorAndEmitsNoEvent() {
        var result = sut.handle(RegisterUserCommand.of(""));

        assertThat(result.isErr()).isTrue();
        var error = ((Result.Err<User, UserError>) result).error();
        assertThat(error).isInstanceOf(UserError.InvalidEmail.class);
        assertThat(((UserError.InvalidEmail) error).reason()).isEqualTo("empty");
    }
}
