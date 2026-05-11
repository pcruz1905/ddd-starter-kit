package myfluxo.adapter.persistence.jdbc.auth;

import myfluxo.adapter.persistence.jdbc.Table;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code refresh_tokens} table.
 *
 * <p>Two nullable components ({@code revokedAt}, {@code replacedByTokenId})
 * carry the lifecycle state — both null on a freshly issued token.
 *
 * <p>{@code tokenHash} is the HMAC-SHA256 of the plaintext token,
 * base64url-encoded. Lookups by hash hit the unique index on this column.
 */
public record RefreshTokenRow(
    UUID id,
    UUID userId,
    String tokenHash,
    UUID familyId,
    Instant expiresAt,
    Instant createdAt,
    Instant revokedAt,
    UUID replacedByTokenId,
    long version
) {

    public static final Table<RefreshTokenRow> TABLE =
        Table.of("refresh_tokens", RefreshTokenRow.class);

    public static RefreshTokenRow fromAggregate(RefreshToken t, long newVersion) {
        return new RefreshTokenRow(
            t.id().value(),
            t.userId().value(),
            t.tokenHash().encoded(),
            t.familyId().value(),
            t.expiresAt(),
            t.createdAt(),
            t.revokedAt().orElse(null),
            t.replacedByTokenId().map(RefreshTokenId::value).orElse(null),
            newVersion
        );
    }

    public RefreshToken toAggregate() {
        return RefreshToken.rehydrate(
            new RefreshTokenId(id),
            new UserId(userId),
            TokenHash.of(tokenHash),
            new RefreshTokenFamilyId(familyId),
            expiresAt,
            createdAt,
            revokedAt,
            replacedByTokenId == null ? null : new RefreshTokenId(replacedByTokenId),
            version
        );
    }
}
