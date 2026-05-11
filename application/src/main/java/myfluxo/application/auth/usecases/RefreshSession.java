package myfluxo.application.auth.usecases;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.application.UseCase;
import myfluxo.application.auth.AuthAuditLogger;
import myfluxo.application.auth.AuthSession;
import myfluxo.application.auth.RefreshTokenTtl;
import myfluxo.application.auth.commands.RefreshSessionCommand;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.User;
import myfluxo.domain.users.UserRepository;
import myfluxo.domain.users.model.UserId;
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
    private final AuthAuditLogger audit;
    private final UnitOfWork uow;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public RefreshSession(
        RefreshTokenRepository refreshTokens,
        UserRepository users,
        RefreshTokenStrategy refreshStrategy,
        TokenIssuer tokenIssuer,
        AuthAuditLogger audit,
        UnitOfWork uow,
        Clock clock,
        RefreshTokenTtl refreshTokenTtl
    ) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.refreshStrategy = refreshStrategy;
        this.tokenIssuer = tokenIssuer;
        this.audit = audit;
        this.uow = uow;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl.value();
    }

    @Override
    public Result<AuthSession, AuthError> handle(RefreshSessionCommand cmd) {
        if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
            return Result.err(new AuthError.InvalidRefreshToken());
        }

        TokenHash presentedHash = refreshStrategy.hash(cmd.refreshToken());

        // Step 1 — reuse detection runs in its OWN transaction so the
        // family-revoke commits independently. If we revoked inside the
        // same UoW that returns Err for the API caller, the UoW would
        // roll back the revoke too. Splitting the transactions is the
        // simplest fix; the cost is one extra DB round-trip on the rare
        // theft-detection path.
        var reuseSignal = uow.inTransaction(() -> {
            var stored = refreshTokens.findByTokenHash(presentedHash);
            if (stored.isPresent() && stored.get().isRotated()) {
                refreshTokens.revokeFamily(
                    stored.get().familyId(), clock.instant());
                return Result.<Optional<TheftSignal>, AuthError>ok(Optional.of(
                    new TheftSignal(stored.get().userId(), stored.get().familyId())));
            }
            return Result.<Optional<TheftSignal>, AuthError>ok(Optional.empty());
        }).orElseThrow();  // inner always returns Ok

        if (reuseSignal.isPresent()) {
            audit.refreshReuseDetected(reuseSignal.get().userId(), reuseSignal.get().familyId());
            return Result.err(new AuthError.RefreshTokenReuseDetected());
        }

        // Step 2 — normal rotation in a fresh transaction.
        return uow.inTransaction(() -> {
            Optional<RefreshToken> stored = refreshTokens.findByTokenHash(presentedHash);
            if (stored.isEmpty()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }
            RefreshToken token = stored.get();
            Instant now = clock.instant();

            // Could've been revoked by a concurrent family-revoke
            // between step 1 and step 2; re-check.
            if (token.isRotated()) {
                // Race: someone else revoked between our two reads.
                // Treat as reuse — but the family is already revoked,
                // so we just report.
                return Result.err(new AuthError.RefreshTokenReuseDetected());
            }
            if (!token.isActive(now)) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }

            Optional<User> userOpt = users.findById(token.userId());
            if (userOpt.isEmpty()) {
                return Result.err(new AuthError.InvalidRefreshToken());
            }
            User user = userOpt.get();

            var newPlaintext = refreshStrategy.generatePlaintext();
            var newHash = refreshStrategy.hash(newPlaintext);
            var newExpiry = now.plus(refreshTokenTtl);
            RefreshToken successor = token.rotate(newHash, newExpiry, now);

            // Save successor BEFORE the rotated original — the original's
            // replaced_by_token_id FK requires the successor to exist
            // within the same transaction.
            refreshTokens.save(successor);
            refreshTokens.save(token);

            var accessToken = tokenIssuer.issue(user.id(), user.role(), now);

            audit.refreshed(user.id());
            return Result.ok(new AuthSession(
                user.id(), user.role(), accessToken, newPlaintext, newExpiry
            ));
        });
    }

    private record TheftSignal(UserId userId, RefreshTokenFamilyId familyId) {}
}
