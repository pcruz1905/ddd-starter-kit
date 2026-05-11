package myfluxo.adapter.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.AccessTokenClaims;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * HS256-signed JWT access tokens via {@link io.jsonwebtoken jjwt}.
 *
 * <h2>Claims</h2>
 * <ul>
 *     <li>{@code sub} — user id (UUID string)</li>
 *     <li>{@code iss} — issuer (myfluxo, configurable)</li>
 *     <li>{@code iat} — issued-at</li>
 *     <li>{@code exp} — expiration ({@code issuedAt + accessTokenTtl})</li>
 *     <li>{@code jti} — token id (UUID v4) for downstream auditing /
 *         blocklist if you wire one</li>
 *     <li>{@code role} — custom: the user's role at issue time
 *         ({@code ADMIN}/{@code MEMBER}/{@code VIEWER}).</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * Verifies signature, issuer, and expiration. {@link TokenIssuer.TokenError}
 * variants describe each failure mode; this implementation never throws
 * — JWT parsing exceptions are mapped to {@code Err} results.
 *
 * <h2>Key rotation</h2>
 * Not built in. To rotate: deploy with a new secret, accept N days of
 * tokens signed by the old AND new (instantiate two issuers, try each
 * on validate), then drop the old. Out of scope for v1.
 */
public final class JwtTokenIssuer implements TokenIssuer {

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtTokenIssuer(
        byte[] hmacSecret,
        String issuer,
        Duration accessTokenTtl,
        Clock clock
    ) {
        Objects.requireNonNull(hmacSecret, "hmacSecret");
        if (hmacSecret.length < 32) {
            throw new IllegalArgumentException(
                "HMAC secret must be >= 32 bytes (256 bits); got "
                + hmacSecret.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(hmacSecret);
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.accessTokenTtl = Objects.requireNonNull(accessTokenTtl, "accessTokenTtl");
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException(
                "accessTokenTtl must be positive: " + accessTokenTtl);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AccessToken issue(UserId userId, Role role, Instant issuedAt) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(issuedAt, "issuedAt");

        Instant expiresAt = issuedAt.plus(accessTokenTtl);

        String compact = Jwts.builder()
            .subject(userId.value().toString())
            .issuer(issuer)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString())
            .claim("role", role.name())
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();

        return new AccessToken(compact, expiresAt);
    }

    @Override
    public Result<AccessTokenClaims, TokenError> validate(String token) {
        if (token == null || token.isBlank()) {
            return Result.err(new TokenError.Malformed());
        }

        Claims parsed;
        try {
            parsed = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (ExpiredJwtException ex) {
            return Result.err(new TokenError.Expired());
        } catch (SignatureException ex) {
            return Result.err(new TokenError.InvalidSignature());
        } catch (MalformedJwtException ex) {
            return Result.err(new TokenError.Malformed());
        } catch (io.jsonwebtoken.IncorrectClaimException ex) {
            // Wrong issuer falls through here from .requireIssuer.
            return Result.err(new TokenError.WrongIssuer());
        } catch (JwtException ex) {
            // Catch-all for parsing/structural problems we haven't
            // matched specifically. Map to malformed — caller doesn't
            // need to know the internal taxonomy.
            return Result.err(new TokenError.Malformed());
        }

        String subString = parsed.getSubject();
        if (subString == null || subString.isBlank()) {
            return Result.err(new TokenError.MissingClaim("sub"));
        }
        Object roleClaim = parsed.get("role");
        if (!(roleClaim instanceof String roleName) || roleName.isBlank()) {
            return Result.err(new TokenError.MissingClaim("role"));
        }

        UserId userId;
        Role role;
        try {
            userId = new UserId(UUID.fromString(subString));
            role = Role.fromName(roleName);
        } catch (IllegalArgumentException ex) {
            return Result.err(new TokenError.Malformed());
        }

        return Result.ok(new AccessTokenClaims(
            userId,
            role,
            parsed.getIssuedAt().toInstant(),
            parsed.getExpiration().toInstant()
        ));
    }
}
