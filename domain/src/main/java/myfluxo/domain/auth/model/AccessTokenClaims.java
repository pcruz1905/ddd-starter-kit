package myfluxo.domain.auth.model;

import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.ddd.ValueObject;

import java.time.Instant;
import java.util.Objects;

/**
 * The claims successfully parsed from a verified access token. The
 * result of {@link myfluxo.domain.auth.TokenIssuer#validate} on a valid
 * token.
 *
 * <p>Includes only the fields downstream code (HTTP filter, authz
 * checks) actually needs. Custom claims that aren't load-bearing for
 * authz aren't propagated here.
 */
public record AccessTokenClaims(
    UserId userId,
    Role role,
    Instant issuedAt,
    Instant expiresAt
) implements ValueObject {

    public AccessTokenClaims {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
