package myfluxo.domain.auth;

import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for persisting and querying {@link RefreshToken} aggregates.
 * Implementation in {@code adapter-persistence-jdbc}.
 */
public interface RefreshTokenRepository {

    /**
     * Lookup by the server-stored HMAC of the plaintext token. The
     * caller HMACs the user-presented refresh token, then queries by
     * the resulting hash.
     */
    Optional<RefreshToken> findByTokenHash(TokenHash tokenHash);

    /** Lookup by aggregate id. */
    Optional<RefreshToken> findById(RefreshTokenId id);

    /** Persist new or updated token. */
    void save(RefreshToken token);

    /**
     * Mark every active token in the family as revoked. Used when
     * reuse of a rotated token is detected — the family is now
     * compromised so all sibling tokens are invalidated.
     */
    void revokeFamily(RefreshTokenFamilyId familyId, Instant now);

    /**
     * Mark every active token for a user as revoked. Used when a
     * password is changed: existing sessions should not survive a
     * password rotation (the old password may have been compromised).
     */
    void revokeAllForUser(UserId userId, Instant now);
}
