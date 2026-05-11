package myfluxo.domain.auth;

import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.aggregate.AbstractAggregateRoot;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single refresh-token issuance, with family tracking for theft
 * detection.
 *
 * <h2>States</h2>
 * <ul>
 *     <li><b>active</b> — {@code revokedAt == null}, {@code expiresAt > now}.
 *         Can be exchanged for new tokens.</li>
 *     <li><b>rotated</b> — {@code revokedAt != null && replacedByTokenId != null}.
 *         Was successfully exchanged; presenting it again is reuse and
 *         should trigger family revocation.</li>
 *     <li><b>revoked</b> — {@code revokedAt != null && replacedByTokenId == null}.
 *         Explicitly revoked (logout) or expired without rotation.</li>
 * </ul>
 *
 * <h2>Family tracking</h2>
 * All tokens issued by rotating from a single login share the same
 * {@link RefreshTokenFamilyId}. When reuse of an already-rotated token
 * is detected, the entire family is revoked together — see
 * {@code RefreshTokenRepository.revokeFamily}.
 *
 * <h2>Plaintext discipline</h2>
 * The aggregate never holds plaintext. Only the {@link TokenHash}
 * (HMAC-SHA256 of the plaintext) is persisted. Plaintext exists only
 * in the moment of issuance, returned to the caller, and then thrown
 * away on the server side.
 */
public final class RefreshToken extends AbstractAggregateRoot<RefreshTokenId> {

    private final RefreshTokenId id;
    private final UserId userId;
    private final TokenHash tokenHash;
    private final RefreshTokenFamilyId familyId;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant revokedAt;            // null if active
    private RefreshTokenId replacedByTokenId;  // null if not yet rotated

    /** New token — fresh state, version 0. */
    private RefreshToken(
        RefreshTokenId id,
        UserId userId,
        TokenHash tokenHash,
        RefreshTokenFamilyId familyId,
        Instant expiresAt,
        Instant createdAt
    ) {
        super();
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.familyId = Objects.requireNonNull(familyId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** Rehydration — carries loaded state including revocation if any. */
    private RefreshToken(
        RefreshTokenId id,
        UserId userId,
        TokenHash tokenHash,
        RefreshTokenFamilyId familyId,
        Instant expiresAt,
        Instant createdAt,
        Instant revokedAt,
        RefreshTokenId replacedByTokenId,
        long version
    ) {
        super(version);
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.familyId = Objects.requireNonNull(familyId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.revokedAt = revokedAt;
        this.replacedByTokenId = replacedByTokenId;
    }

    /** Issue a brand-new token, starting a new family. */
    public static RefreshToken issue(
        UserId userId,
        TokenHash tokenHash,
        Instant expiresAt,
        Instant now
    ) {
        return new RefreshToken(
            RefreshTokenId.newId(),
            userId,
            tokenHash,
            RefreshTokenFamilyId.newId(),
            expiresAt,
            now
        );
    }

    public static RefreshToken rehydrate(
        RefreshTokenId id,
        UserId userId,
        TokenHash tokenHash,
        RefreshTokenFamilyId familyId,
        Instant expiresAt,
        Instant createdAt,
        Instant revokedAt,
        RefreshTokenId replacedByTokenId,
        long version
    ) {
        return new RefreshToken(
            id, userId, tokenHash, familyId,
            expiresAt, createdAt, revokedAt, replacedByTokenId, version
        );
    }

    /**
     * Mutate this token into the "rotated" state and return its
     * successor. The successor inherits the family id and gets a fresh
     * id, hash, and expiry. The caller persists both aggregates.
     *
     * <p>Pre-condition: this token is active. Use {@link #isActive(Instant)}
     * to check before calling.
     */
    public RefreshToken rotate(
        TokenHash newTokenHash,
        Instant newExpiresAt,
        Instant now
    ) {
        if (revokedAt != null) {
            throw new IllegalStateException(
                "Cannot rotate a revoked refresh token: " + id);
        }
        var successor = new RefreshToken(
            RefreshTokenId.newId(),
            userId,
            newTokenHash,
            familyId,           // inherits family
            newExpiresAt,
            now
        );
        this.revokedAt = now;
        this.replacedByTokenId = successor.id;
        return successor;
    }

    /** Mark as revoked. Idempotent — revoking an already-revoked token is a no-op. */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean isRotated() {
        return revokedAt != null && replacedByTokenId != null;
    }

    @Override
    public RefreshTokenId id() {
        return id;
    }

    public UserId userId() {
        return userId;
    }

    public TokenHash tokenHash() {
        return tokenHash;
    }

    public RefreshTokenFamilyId familyId() {
        return familyId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Optional<RefreshTokenId> replacedByTokenId() {
        return Optional.ofNullable(replacedByTokenId);
    }
}
