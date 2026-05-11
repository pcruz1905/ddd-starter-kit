package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthAuditLogger;
import myfluxo.application.auth.commands.LogoutCommand;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.kernel.result.Result;

import java.time.Clock;
import java.util.Optional;

/**
 * Revoke a single refresh token — the caller-supplied one. Other
 * tokens for the same user (other devices, other browser sessions)
 * stay active.
 *
 * <p>To revoke every session for a user, see {@link ChangePassword}
 * (which calls {@code RefreshTokenRepository.revokeAllForUser}) or a
 * dedicated admin path.
 */
@Singleton
public final class Logout implements UseCase<LogoutCommand, RefreshTokenId, AuthError> {

    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenStrategy refreshStrategy;
    private final AuthAuditLogger audit;
    private final UnitOfWork uow;
    private final Clock clock;

    public Logout(
        RefreshTokenRepository refreshTokens,
        RefreshTokenStrategy refreshStrategy,
        AuthAuditLogger audit,
        UnitOfWork uow,
        Clock clock
    ) {
        this.refreshTokens = refreshTokens;
        this.refreshStrategy = refreshStrategy;
        this.audit = audit;
        this.uow = uow;
        this.clock = clock;
    }

    @Override
    public Result<RefreshTokenId, AuthError> handle(LogoutCommand cmd) {
        return uow.inTransaction(() -> {
            if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            var hash = refreshStrategy.hash(cmd.refreshToken());
            Optional<RefreshToken> stored = refreshTokens.findByTokenHash(hash);

            if (stored.isEmpty()) {
                // Idempotency: presenting a token that doesn't exist
                // (already deleted? never existed?) is treated as
                // "your session is already gone, all good".
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            RefreshToken token = stored.get();
            // Revoking an already-revoked token is a no-op per the
            // aggregate; persist anyway in case version moved.
            token.revoke(clock.instant());
            refreshTokens.save(token);

            audit.loggedOut(token.userId());
            return Result.ok(token.id());
        });
    }
}
