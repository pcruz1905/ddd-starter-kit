package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthSession;
import myfluxo.application.auth.RefreshTokenTtl;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.CredentialsRepository;
import myfluxo.domain.auth.PasswordHasher;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.UserRepository;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.event.DomainEventPublisher;
import myfluxo.kernel.result.Result;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Register a new user with a password and return an immediate session.
 *
 * <p>Atomic: User + Credentials + initial RefreshToken commit together
 * in the same UoW. If any step fails, all roll back — no half-registered
 * accounts.
 *
 * <p>New users default to {@link Role.Member}. Promoting to Admin /
 * demoting to Viewer is a separate admin-only flow.
 */
@Singleton
public final class Register implements UseCase<RegisterCommand, AuthSession, AuthError> {

    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher hasher;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenStrategy refreshStrategy;
    private final DomainEventPublisher events;
    private final UnitOfWork uow;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public Register(
        UserRepository users,
        CredentialsRepository credentials,
        RefreshTokenRepository refreshTokens,
        PasswordHasher hasher,
        TokenIssuer tokenIssuer,
        RefreshTokenStrategy refreshStrategy,
        DomainEventPublisher events,
        UnitOfWork uow,
        Clock clock,
        RefreshTokenTtl refreshTokenTtl
    ) {
        this.users = users;
        this.credentials = credentials;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.tokenIssuer = tokenIssuer;
        this.refreshStrategy = refreshStrategy;
        this.events = events;
        this.uow = uow;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl.value();
    }

    @Override
    public Result<AuthSession, AuthError> handle(RegisterCommand cmd) {
        return uow.inTransaction(() -> {
            // Validate password format BEFORE checking email — fail fast.
            // Mapping: ParseError.reason → AuthError.WeakPassword(reason).
            Result<Password, Password.ParseError> passwordResult = Password.parse(cmd.password());
            if (passwordResult instanceof Result.Err<Password, Password.ParseError>(Password.ParseError err)) {
                return Result.err(new AuthError.WeakPassword(err.reason()));
            }
            Password password = ((Result.Ok<Password, Password.ParseError>) passwordResult).value();

            // Validate email.
            Result<Email, Email.ParseError> emailResult = Email.parse(cmd.email());
            if (emailResult instanceof Result.Err<Email, Email.ParseError>(Email.ParseError err)) {
                return Result.err(new AuthError.InvalidEmail(err.input(), err.reason()));
            }
            Email email = ((Result.Ok<Email, Email.ParseError>) emailResult).value();

            // Email uniqueness.
            if (users.existsByEmail(email)) {
                return Result.err(new AuthError.EmailAlreadyTaken(email));
            }

            Instant now = clock.instant();

            // Create + persist User.
            User user = User.register(UserId.newId(), email, now);
            users.save(user);

            // Hash password + persist Credentials.
            PasswordHash hash = hasher.hash(password);
            Credentials creds = Credentials.create(user.id(), hash, now);
            credentials.save(creds);

            // Issue and persist initial refresh token + access token.
            var session = issueSession(user.id(), user.role(), now);

            // Publish domain events INSIDE the UoW so outbox writes
            // commit atomically with aggregate saves.
            events.publishAll(user.pullEvents());

            return Result.ok(session);
        });
    }

    private AuthSession issueSession(UserId userId, Role role, Instant now) {
        var accessToken = tokenIssuer.issue(userId, role, now);
        var plaintext = refreshStrategy.generatePlaintext();
        var refreshHash = refreshStrategy.hash(plaintext);
        var refreshExpiry = now.plus(refreshTokenTtl);
        var token = RefreshToken.issue(userId, refreshHash, refreshExpiry, now);
        refreshTokens.save(token);

        return new AuthSession(userId, role, accessToken, plaintext, refreshExpiry);
    }
}
