package myfluxo.application.auth.fakes;

import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.AccessTokenClaims;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic token issuer for unit tests. Tokens are "{userId}::{role}::{seq}"
 * — no signing, no parsing. Real JWT semantics tested in
 * {@code adapter-auth.JwtTokenIssuerTest}.
 */
public final class FakeTokenIssuer implements TokenIssuer {

    private final Duration ttl;
    private final AtomicLong sequence = new AtomicLong();

    public FakeTokenIssuer(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public AccessToken issue(UserId userId, Role role, Instant issuedAt) {
        long seq = sequence.incrementAndGet();
        String value = userId.value() + "::" + role.name() + "::" + seq;
        return new AccessToken(value, issuedAt.plus(ttl));
    }

    @Override
    public Result<AccessTokenClaims, TokenError> validate(String token) {
        // Not used in use-case unit tests; HTTP filter ITs cover this.
        return Result.err(new TokenError.Malformed());
    }
}
