package myfluxo.application.users.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.users.commands.RegisterUserCommand;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.errors.UserError;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.UserRepository;
import myfluxo.kernel.event.DomainEventPublisher;
import myfluxo.kernel.result.Result;

import java.time.Clock;

/**
 * Use case: register a new user.
 *
 * <p>Pipeline:
 * <ol>
 *     <li>Open a UoW.</li>
 *     <li>Parse email, enforce email-uniqueness, construct + persist User.</li>
 *     <li>Publish the aggregate's domain events <strong>inside</strong> the
 *         same UoW so that an outbox publisher writes events atomically
 *         with the aggregate (no commit/no-event window).</li>
 *     <li>UoW commits on {@code Ok}, rolls back on {@code Err}.</li>
 * </ol>
 *
 * <p>Idempotency is handled at the HTTP layer via the
 * {@code IdempotencyMiddleware} (Stripe / IETF "Idempotency-Key" RFC
 * pattern). The use case stays unaware of retry semantics.
 *
 * <p>Publishers that have external side effects (real bus, email service)
 * should be implemented via the outbox so the side effect happens only
 * after commit. In-process subscribers can register on the outbox
 * dispatcher, not on this publisher directly.
 */
@Singleton
public final class RegisterUser implements UseCase<RegisterUserCommand, User, UserError> {

    private final UserRepository users;
    private final UnitOfWork uow;
    private final DomainEventPublisher events;
    private final Clock clock;

    public RegisterUser(
        UserRepository users,
        UnitOfWork uow,
        DomainEventPublisher events,
        Clock clock
    ) {
        this.users = users;
        this.uow = uow;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Result<User, UserError> handle(RegisterUserCommand cmd) {
        return uow.inTransaction(() -> {
            var result = doHandle(cmd);
            if (result instanceof Result.Ok<User, UserError>(User user)) {
                // Publish INSIDE the transaction so outbox writes are atomic
                // with the aggregate save. The publisher chooses semantics:
                // - in-memory: dispatches immediately (subscribers must be
                //   tolerant of seeing pre-commit state)
                // - JDBC outbox: persists events; a separate dispatcher
                //   worker forwards them to the real bus after commit.
                events.publishAll(user.pullEvents());
            }
            return result;
        });
    }

    private Result<User, UserError> doHandle(RegisterUserCommand cmd) {
        return switch (Email.parse(cmd.email())) {
            case Result.Err<Email, Email.ParseError>(Email.ParseError err) ->
                Result.err(new UserError.InvalidEmail(err.input(), err.reason()));
            case Result.Ok<Email, Email.ParseError>(Email email) -> {
                if (users.existsByEmail(email)) {
                    yield Result.err(new UserError.EmailAlreadyTaken(email));
                }
                var user = User.register(UserId.newId(), email, clock.instant());
                users.save(user);
                yield Result.ok(user);
            }
        };
    }
}
