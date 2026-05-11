package myfluxo.application.auth;

import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * The result of a successful auth operation that issues credentials —
 * Register, Login, RefreshSession. Carries both tokens the caller
 * needs to act as the user.
 *
 * <h2>Plaintext refresh token</h2>
 * {@link #refreshTokenPlaintext} is the only place in the system that
 * the unhashed refresh token exists after issuance. The use case
 * returns it once; the HTTP layer puts it in the response body; the
 * server forgets it. Only its HMAC is persisted in
 * {@code refresh_tokens.token_hash}.
 *
 * <p>Sensitive: never log this record. The default {@code toString} is
 * overridden to redact tokens.
 */
public record AuthSession(
    UserId userId,
    Role role,
    AccessToken accessToken,
    String refreshTokenPlaintext,
    Instant refreshTokenExpiresAt
) {

    public AuthSession {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshTokenPlaintext, "refreshTokenPlaintext");
        Objects.requireNonNull(refreshTokenExpiresAt, "refreshTokenExpiresAt");
        if (refreshTokenPlaintext.isBlank()) {
            throw new IllegalArgumentException("refreshTokenPlaintext cannot be blank");
        }
    }

    @Override
    public String toString() {
        return "AuthSession{userId=" + userId
            + ", role=" + role
            + ", accessToken=REDACTED"
            + ", refreshTokenPlaintext=REDACTED"
            + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt
            + "}";
    }
}
