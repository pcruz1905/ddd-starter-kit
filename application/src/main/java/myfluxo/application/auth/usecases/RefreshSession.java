package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthSession;
import myfluxo.application.auth.commands.RefreshSessionCommand;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.users.User;
import myfluxo.domain.users.UserRepository;
import myfluxo.kernel.result.Result;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Exchange a refresh token for a fresh access + refresh token pair.
 *
 * <h2>Refresh-token rotation</h2>
 * Every successful refresh rotates the token: the presented token is
 * marked revoked + replacedBy, and a successor is issued in the same
 * family. Both writes commit atomically in the same UoW.
 *
 * <h2>Theft detection via family revocation</h2>
 * If the presented token has already been rotated (its
 * {@code replacedByTokenId} is set), one of two parties is replaying
 * a token that was already exchanged — either the attacker raced the
 * legit user, or the legit user is presenting an old token by mistake
 * (browser auto-refresh, etc.). We can't distinguish, so we treat it
 * as theft: revoke the entire family. Both parties are forced to
 * log in again. This is the canonical refresh-token-rotation defence.
 *
 * <h2>Role pickup</h2>
 * Re-reads the user's current role on every refresh. If an admin demotes
 * a user from ADMIN to MEMBER, the demoted user's NEXT refresh issues
 * a MEMBER-scoped access token. The previously issued (still-valid)
 * access token retains its old role until expiry — short TTL bounds
 * the exposure.
 */
@Singleton
public final class RefreshSession implements UseCase<RefreshSessionCommand, AuthSession, AuthError> {

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final RefreshTokenStrategy refreshStrategy;
    private final TokenIssuer tokenIssuer;
    private final UnitOfWork uow;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public RefreshSession(
        RefreshTokenRepository refreshTokens,
        UserRepository users,
        RefreshTokenStrategy refreshStrategy,
        TokenIssuer tokenIssuer,
        UnitOfWork uow,
        Clock clock,
        Duration refreshTokenTtl
    ) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.refreshStrategy = refreshStrategy;
        this.tokenIssuer = tokenIssuer;
        this.uow = uow;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @Override
    public Result<AuthSession, AuthError> handle(RefreshSessionCommand cmd) {
        return uow.inTransaction(() -> {
            if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            var presentedHash = refreshStrategy.hash(cmd.refreshToken());
            Optional<RefreshToken> stored = refreshTokens.findByTokenHash(presentedHash);

            if (stored.isEmpty()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            RefreshToken token = stored.get();
            Instant now = clock.instant();

            // Theft signal: token was already rotated. Revoke the
            // entire family and refuse this attempt.
            if (token.isRotated()) {
                refreshTokens.revokeFamily(token.familyId(), now);
                return Result.err(new AuthError.RefreshTokenReuseDetected());
            }

            // Expired or explicitly revoked — just no good, no family
            // action needed (it's not a theft signal).
            if (!token.isActive(now)) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            // User must still exist. If the account was hard-deleted
            // between issuance and refresh, the token is dead too.
            Optional<User> userOpt = users.findById(token.userId());
            if (userOpt.isEmpty()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }
            User user = userOpt.get();

            // Rotate.
            var newPlaintext = refreshStrategy.generatePlaintext();
            var newHash = refreshStrategy.hash(newPlaintext);
            var newExpiry = now.plus(refreshTokenTtl);
            RefreshToken successor = token.rotate(newHash, newExpiry, now);

            // Save both — the old one transitions to rotated, the
            // successor is freshly issued.
            refreshTokens.save(token);
            refreshTokens.save(successor);

            var accessToken = tokenIssuer.issue(user.id(), user.role(), now);

            return Result.ok(new AuthSession(
                user.id(), user.role(), accessToken, newPlaintext, newExpiry
            ));
        });
    }
}
