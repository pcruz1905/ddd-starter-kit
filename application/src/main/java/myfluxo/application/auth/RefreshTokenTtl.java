package myfluxo.application.auth;

import java.time.Duration;
import java.util.Objects;

/**
 * Typed wrapper for the refresh-token lifetime. Production-grade
 * guidance: 7–14 days. Long enough to feel like a "stay signed in"
 * experience; short enough that lost devices stop being a session
 * risk within a manageable window.
 */
public record RefreshTokenTtl(Duration value) {

    public RefreshTokenTtl {
        Objects.requireNonNull(value, "value");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                "RefreshTokenTtl must be positive: " + value);
        }
    }

    public static RefreshTokenTtl of(Duration d) {
        return new RefreshTokenTtl(d);
    }
}
