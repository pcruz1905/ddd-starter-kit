package myfluxo.adapter.auth;

import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.AccessTokenClaims;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenIssuerTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] OTHER_SECRET = "fedcba9876543210fedcba9876543210".getBytes();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofMinutes(15);

    private final JwtTokenIssuer issuer = new JwtTokenIssuer(SECRET, "myfluxo", TTL, CLOCK);

    private static <T, E> E unwrapErr(Result<T, E> result) {
        return result.fold(
            ok -> { throw new AssertionError("Expected Err but got Ok: " + ok); },
            err -> err
        );
    }

    @Test
    void issueThenValidate_roundtripsClaims() {
        UserId userId = UserId.newId();
        AccessToken token = issuer.issue(userId, Role.Member.INSTANCE, NOW);

        var result = issuer.validate(token.value());

        assertThat(result.isOk()).isTrue();
        AccessTokenClaims claims = result.fold(c -> c, err -> {
            throw new AssertionError("expected Ok, got: " + err);
        });
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.role()).isSameAs(Role.Member.INSTANCE);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void issue_setsExpiration_atIssuedAtPlusTtl() {
        var t = issuer.issue(UserId.newId(), Role.Admin.INSTANCE, NOW);
        assertThat(t.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void issue_producesDifferentJtiPerCall() {
        // Same user, same instant — tokens differ because each gets a
        // fresh jti. Defends against replay-after-revocation if we
        // wire a jti blocklist later.
        var a = issuer.issue(UserId.newId(), Role.Member.INSTANCE, NOW);
        var b = issuer.issue(UserId.newId(), Role.Member.INSTANCE, NOW);
        assertThat(a.value()).isNotEqualTo(b.value());
    }

    @Test
    void validate_rejectsMalformedToken() {
        var result = issuer.validate("not-a-jwt");
        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result))
            .isInstanceOf(TokenIssuer.TokenError.Malformed.class);
    }

    @Test
    void validate_rejectsNullOrBlank() {
        assertThat(unwrapErr(issuer.validate(null)))
            .isInstanceOf(TokenIssuer.TokenError.Malformed.class);
        assertThat(unwrapErr(issuer.validate("")))
            .isInstanceOf(TokenIssuer.TokenError.Malformed.class);
        assertThat(unwrapErr(issuer.validate("   ")))
            .isInstanceOf(TokenIssuer.TokenError.Malformed.class);
    }

    @Test
    void validate_rejectsTokenSignedWithDifferentSecret() {
        var otherIssuer = new JwtTokenIssuer(OTHER_SECRET, "myfluxo", TTL, CLOCK);
        var token = otherIssuer.issue(UserId.newId(), Role.Member.INSTANCE, NOW);

        var result = issuer.validate(token.value());

        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result))
            .isInstanceOf(TokenIssuer.TokenError.InvalidSignature.class);
    }

    @Test
    void validate_rejectsTokenWithDifferentIssuer() {
        var otherIssuer = new JwtTokenIssuer(SECRET, "other-issuer", TTL, CLOCK);
        var token = otherIssuer.issue(UserId.newId(), Role.Member.INSTANCE, NOW);

        var result = issuer.validate(token.value());

        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result))
            .isInstanceOf(TokenIssuer.TokenError.WrongIssuer.class);
    }

    @Test
    void validate_rejectsExpiredToken() {
        // Issue at time NOW with 15min TTL; validate with clock past
        // the expiration.
        var future = Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC);
        var futureIssuer = new JwtTokenIssuer(SECRET, "myfluxo", TTL, future);

        var token = issuer.issue(UserId.newId(), Role.Member.INSTANCE, NOW);

        var result = futureIssuer.validate(token.value());

        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result))
            .isInstanceOf(TokenIssuer.TokenError.Expired.class);
    }

    @Test
    void ctor_rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtTokenIssuer(new byte[16], "myfluxo", TTL, CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void ctor_rejectsZeroOrNegativeTtl() {
        assertThatThrownBy(() ->
            new JwtTokenIssuer(SECRET, "myfluxo", Duration.ZERO, CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new JwtTokenIssuer(SECRET, "myfluxo", Duration.ofSeconds(-1), CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
