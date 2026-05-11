package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthAuditLogger;
import myfluxo.application.auth.commands.ChangePasswordCommand;
import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.CredentialsRepository;
import myfluxo.domain.auth.PasswordHasher;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Replace the current user's password after verifying the old one.
 *
 * <h2>Side effect: revoke other sessions</h2>
 * On success, every refresh token for this user is revoked. The user
 * is forced to re-authenticate on all OTHER devices.
 *
 * <p>Why: if the old password was leaked or guessed, an attacker may
 * have an active session. Changing the password without revoking
 * tokens lets them stay in. Standard production-grade flow forces
 * a re-login.
 *
 * <p>The token being used by the caller making this request is also
 * revoked — the access token survives until expiry (minutes), and the
 * caller must obtain a new refresh token via login. Mild UX cost for
 * a real security gain.
 */
@Singleton
public final class ChangePassword implements UseCase<ChangePasswordCommand, UserId, AuthError> {

    private final CredentialsRepository credentials;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher hasher;
    private final AuthAuditLogger audit;
    private final UnitOfWork uow;
    private final Clock clock;

    public ChangePassword(
        CredentialsRepository credentials,
        RefreshTokenRepository refreshTokens,
        PasswordHasher hasher,
        AuthAuditLogger audit,
        UnitOfWork uow,
        Clock clock
    ) {
        this.credentials = credentials;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.audit = audit;
        this.uow = uow;
        this.clock = clock;
    }

    @Override
    public Result<UserId, AuthError> handle(ChangePasswordCommand cmd) {
        return uow.inTransaction(() -> {
            UserId userId = cmd.currentUserId();

            // New password format validated first — short-circuit
            // before we do the expensive Argon2 verify of the old one.
            Result<Password, Password.ParseError> newPwResult = Password.parse(cmd.newPassword());
            if (newPwResult instanceof Result.Err<Password, Password.ParseError>(Password.ParseError err)) {
                return Result.err(new AuthError.WeakPassword(err.reason()));
            }
            Password newPassword = ((Result.Ok<Password, Password.ParseError>) newPwResult).value();

            // Old password: best-effort parse — if it doesn't even
            // meet the format, it certainly doesn't match.
            Result<Password, Password.ParseError> oldPwResult = Password.parse(cmd.oldPassword());
            if (oldPwResult.isErr()) {
                return Result.err(new AuthError.OldPasswordMismatch());
            }
            Password oldPassword = ((Result.Ok<Password, ?>) oldPwResult).value();

            Optional<Credentials> stored = credentials.findByUserId(userId);
            if (stored.isEmpty()) {
                // The currentUserId came from a valid JWT but has no
                // credentials row. Either it's a fresh OAuth-only user
                // or a data inconsistency. Treat as mismatch (don't
                // leak which).
                return Result.err(new AuthError.OldPasswordMismatch());
            }
            Credentials creds = stored.get();

            if (!hasher.verify(oldPassword, creds.passwordHash())) {
                return Result.err(new AuthError.OldPasswordMismatch());
            }

            // Replace hash.
            PasswordHash newHash = hasher.hash(newPassword);
            Instant now = clock.instant();
            creds.changePassword(newHash, now);
            credentials.save(creds);

            // Kill all other sessions.
            refreshTokens.revokeAllForUser(userId, now);

            audit.passwordChanged(userId);
            return Result.ok(userId);
        });
    }
}
