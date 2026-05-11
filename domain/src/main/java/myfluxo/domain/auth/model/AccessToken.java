package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;

import java.time.Instant;
import java.util.Objects;

/**
 * A signed, time-bounded access credential (JWT). Returned to the caller
 * by login/refresh flows; presented on subsequent requests in the
 * {@code Authorization: Bearer ...} header.
 *
 * <p>The token itself is signed by the server's secret. The server
 * never persists access tokens — they're stateless. Revocation requires
 * either waiting for expiry (recommended, given short TTLs) or
 * maintaining a deny-list of {@code jti}s.
 */
public record AccessToken(String value, Instant expiresAt) implements ValueObject {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AccessToken value cannot be blank");
        }
    }

    @Override
    public String toString() {
        // The token itself is sensitive — never log it.
        return "AccessToken{REDACTED, expiresAt=" + expiresAt + "}";
    }
}
