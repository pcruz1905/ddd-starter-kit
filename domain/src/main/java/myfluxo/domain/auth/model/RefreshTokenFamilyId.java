package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;
import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.id.UuidV7;

import java.util.UUID;

/**
 * Groups a rotation chain of refresh tokens — every token rotated from
 * the original login carries the same family id.
 *
 * <h2>Why families exist</h2>
 * If a refresh token is stolen and the attacker uses it before the
 * legitimate user, two parties hold tokens from the same family. When
 * the second party tries to refresh, the server sees a token that has
 * already been rotated (its {@code replacedByTokenId} is set). That's
 * the cue to <b>revoke the entire family</b> — both the attacker and
 * the legitimate user are forced to re-authenticate. This is the
 * canonical refresh-token-rotation theft-detection pattern.
 */
public record RefreshTokenFamilyId(UUID value) implements Identifier<UUID>, ValueObject {

    public RefreshTokenFamilyId {
        if (value == null) {
            throw new IllegalArgumentException("RefreshTokenFamilyId value cannot be null");
        }
    }

    public static RefreshTokenFamilyId newId() {
        return new RefreshTokenFamilyId(UuidV7.generate());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
