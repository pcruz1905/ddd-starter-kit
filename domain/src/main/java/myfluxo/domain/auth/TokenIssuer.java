package myfluxo.domain.auth;

import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.AccessTokenClaims;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;

import java.time.Instant;

/**
 * Issues and validates short-lived access tokens (JWTs).
 *
 * <h2>Implementation requirements</h2>
 * <ul>
 *     <li>Sign with HS256 (HMAC-SHA256) or RS256 (RSA-SHA256). HMAC
 *         secret must be 256+ bits.</li>
 *     <li>Standard claims: {@code iat}, {@code exp}, {@code sub},
 *         {@code iss}, optionally {@code jti}. Add a {@code role}
 *         custom claim.</li>
 *     <li>TTL short — minutes, not hours. Refresh tokens are how
 *         long-lived sessions work.</li>
 *     <li>{@link #validate} verifies signature, issuer, expiration.
 *         Failure modes are {@link TokenError} variants — never throw.</li>
 * </ul>
 */
public interface TokenIssuer {

    AccessToken issue(UserId userId, Role role, Instant issuedAt);

    Result<AccessTokenClaims, TokenError> validate(String token);

    sealed interface TokenError {
        record Malformed() implements TokenError {}
        record InvalidSignature() implements TokenError {}
        record Expired() implements TokenError {}
        record WrongIssuer() implements TokenError {}
        record MissingClaim(String name) implements TokenError {}
    }
}
