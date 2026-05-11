package myfluxo.application.auth;

import java.time.Duration;
import java.util.Objects;

/**
 * Typed wrapper for the access-token lifetime so DI containers can
 * inject the right value without {@code @Named} qualifiers. Production
 * defaults to 15 minutes; tests can use shorter or longer.
 *
 * <p>Production-grade guidance: short TTLs (5–30 min) limit the
 * exposure window of a stolen access token. Refresh tokens carry the
 * long-lived session.
 */
public record AccessTokenTtl(Duration value) {

    public AccessTokenTtl {
        Objects.requireNonNull(value, "value");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                "AccessTokenTtl must be positive: " + value);
        }
    }

    public static AccessTokenTtl of(Duration d) {
        return new AccessTokenTtl(d);
    }
}
