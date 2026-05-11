package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthSession;
import myfluxo.application.auth.RefreshTokenTtl;
import myfluxo.application.auth.commands.LoginCommand;
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
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.result.Result;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Verify credentials and issue a new session.
 *
 * <h2>Timing-attack safety</h2>
 * Argon2 verify is the dominant cost in the login path. If we skipped
 * verification when the user is missing, an attacker could probe the
 * "user exists?" question by measuring response time. To defeat that,
 * we precompute a {@link #decoyHash} at construction and run verify
 * against it whenever the user isn't found.
 *
 * <p>Order of checks pinned:
 * <ol>
 *     <li>Parse password — invalid format reports {@code InvalidCredentials}
 *         (don't reveal that it was a format error).</li>
 *     <li>Parse email — same treatment, but still run verify against
 *         decoy hash to keep response time consistent.</li>
 *     <li>Look up user → load credentials → verify password (always,
 *         against decoy if either is missing).</li>
 *     <li>If user missing OR password mismatch → {@code InvalidCredentials}.</li>
 *     <li>If user inactive → {@code AccountInactive}.</li>
 *     <li>Issue tokens; return session.</li>
 * </ol>
 */
@Singleton
public final class Login implements UseCase<LoginCommand, AuthSession, AuthError> {

    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher hasher;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenStrategy refreshStrategy;
    private final UnitOfWork uow;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    /**
     * Precomputed Argon2id hash of a throwaway value, so the verify
     * step always runs even when no user is found. Argon2 verify time
     * is dominated by memory access patterns, not by hash content, so
     * the same decoy across all "user missing" branches doesn't expose
     * a timing signature.
     */
    private final PasswordHash decoyHash;

    public Login(
        UserRepository users,
        CredentialsRepository credentials,
        RefreshTokenRepository refreshTokens,
        PasswordHasher hasher,
        TokenIssuer tokenIssuer,
        RefreshTokenStrategy refreshStrategy,
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
        this.uow = uow;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl.value();
        // One-time ~50-100ms cost at startup; the value lives for the
        // process lifetime.
        this.decoyHash = hasher.hash(Password.of("decoy-for-timing-safety-only"));
    }

    @Override
    public Result<AuthSession, AuthError> handle(LoginCommand cmd) {
        return uow.inTransaction(() -> {
            // Password format invalid → InvalidCredentials. Caller
            // can't distinguish "wrong password" from "malformed
            // password" — same response.
            Result<Password, Password.ParseError> passwordResult = Password.parse(cmd.password());
            if (passwordResult.isErr()) {
                // No verify needed — we don't even know how to build
                // a real attempt. Returning fast here doesn't leak
                // user existence because we never queried.
                return Result.err(new AuthError.InvalidCredentials());
            }
            Password password = ((Result.Ok<Password, ?>) passwordResult).value();

            // Email format invalid → InvalidCredentials, BUT run verify
            // against decoy first to keep response time aligned with
            // the valid-email-but-no-user path.
            Result<Email, Email.ParseError> emailResult = Email.parse(cmd.email());
            if (emailResult.isErr()) {
                hasher.verify(password, decoyHash);
                return Result.err(new AuthError.InvalidCredentials());
            }
            Email email = ((Result.Ok<Email, Email.ParseError>) emailResult).value();

            Optional<User> userOpt = users.findByEmail(email);

            // Either real hash or decoy — verify ALWAYS runs.
            PasswordHash hashToVerify = userOpt
                .flatMap(u -> credentials.findByUserId(u.id()))
                .map(c -> c.passwordHash())
                .orElse(decoyHash);

            boolean passwordMatch = hasher.verify(password, hashToVerify);

            // Both conditions checked together — uniform response.
            if (userOpt.isEmpty() || !passwordMatch) {
                return Result.err(new AuthError.InvalidCredentials());
            }

            User user = userOpt.get();

            // Block only Deactivated. Pending is allowed (no email
            // verification flow yet — when one lands, this check
            // tightens to require Active). Active is the happy path.
            //
            // Returning AccountInactive rather than InvalidCredentials
            // is a deliberate small leak (attacker who proves the
            // password also learns the account is suspended) but the
            // alternative would confuse legitimate users whose accounts
            // were suspended.
            if (user.status() instanceof UserStatus.Deactivated) {
                return Result.err(new AuthError.AccountInactive(user.id()));
            }

            Instant now = clock.instant();
            Role role = Role.Member.INSTANCE;  // Phase 3 will source from User.role
            var accessToken = tokenIssuer.issue(user.id(), role, now);
            var plaintext = refreshStrategy.generatePlaintext();
            var refreshHash = refreshStrategy.hash(plaintext);
            var refreshExpiry = now.plus(refreshTokenTtl);
            var refreshToken = RefreshToken.issue(user.id(), refreshHash, refreshExpiry, now);
            refreshTokens.save(refreshToken);

            return Result.ok(new AuthSession(
                user.id(), role, accessToken, plaintext, refreshExpiry
            ));
        });
    }
}
