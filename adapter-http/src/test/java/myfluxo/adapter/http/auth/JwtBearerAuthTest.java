package myfluxo.adapter.http.auth;

import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.AccessToken;
import myfluxo.domain.auth.model.AccessTokenClaims;
import myfluxo.domain.auth.model.Permission;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JwtBearerAuth}. Uses a hand-rolled
 * {@link FakeTokenIssuer} so the tests don't depend on the real JWT
 * implementation. Operates on raw {@code Authorization} header strings
 * to avoid having to mock Helidon's {@code ServerRequest}.
 */
class JwtBearerAuthTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.newId();

    private static <T, E> E unwrapErr(Result<T, E> r) {
        return r.fold(
            ok -> { throw new AssertionError("expected Err, got Ok: " + ok); },
            e -> e
        );
    }

    /** Returns valid claims for any token starting with "good:". */
    private static final class FakeTokenIssuer implements TokenIssuer {
        private final Role role;

        FakeTokenIssuer(Role role) { this.role = role; }

        @Override
        public AccessToken issue(UserId userId, Role role, Instant issuedAt) {
            return new AccessToken("good:" + userId.value(), issuedAt.plusSeconds(900));
        }

        @Override
        public Result<AccessTokenClaims, TokenError> validate(String token) {
            if (token == null || !token.startsWith("good:")) {
                return Result.err(new TokenError.Malformed());
            }
            return Result.ok(new AccessTokenClaims(
                ALICE, role, NOW, NOW.plusSeconds(900)
            ));
        }
    }

    @Test
    void require_returnsUnauthenticated_whenNoHeader() {
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Member.INSTANCE));
        var result = auth.require((String) null);

        assertThat(unwrapErr(result)).isInstanceOf(AuthError.Unauthenticated.class);
    }

    @Test
    void require_returnsUnauthenticated_whenHeaderHasNoBearerPrefix() {
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Member.INSTANCE));
        var result = auth.require("Basic dXNlcjpwYXNz");  // Basic Auth — not us.

        assertThat(unwrapErr(result)).isInstanceOf(AuthError.Unauthenticated.class);
    }

    @Test
    void require_returnsUnauthenticated_forInvalidToken() {
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Member.INSTANCE));
        var result = auth.require("Bearer not-a-valid-token");

        assertThat(unwrapErr(result)).isInstanceOf(AuthError.Unauthenticated.class);
    }

    @Test
    void require_returnsCurrentUser_forValidToken() {
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Member.INSTANCE));
        var result = auth.require("Bearer good:something");

        assertThat(result.isOk()).isTrue();
        var current = result.fold(c -> c, e -> { throw new AssertionError(); });
        assertThat(current.userId()).isEqualTo(ALICE);
        assertThat(current.role()).isSameAs(Role.Member.INSTANCE);
    }

    // ── Permission gating ──────────────────────────────────────────────

    @Test
    void requirePermission_returnsUnauthenticated_whenNoToken() {
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Member.INSTANCE));

        var result = auth.requirePermission((String) null, Permission.USERS_READ);

        assertThat(unwrapErr(result)).isInstanceOf(AuthError.Unauthenticated.class);
    }

    @Test
    void requirePermission_returnsForbidden_whenTokenValidButRoleLacksPermission() {
        // Viewer can read but NOT delete.
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Viewer.INSTANCE));

        var result = auth.requirePermission("Bearer good:x", Permission.USERS_DELETE);

        var error = unwrapErr(result);
        assertThat(error).isInstanceOf(AuthError.Forbidden.class);
        assertThat(((AuthError.Forbidden) error).required()).isEqualTo(Permission.USERS_DELETE);
    }

    @Test
    void requirePermission_returnsOk_whenTokenValidAndPermissionHeld() {
        // Admin has every permission.
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Admin.INSTANCE));

        var result = auth.requirePermission("Bearer good:x", Permission.USERS_DELETE);

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void requirePermission_unauthenticatedShortCircuits_beforePermissionCheck() {
        // If no token, we report Unauthenticated even though Admin would
        // pass the permission check. The order matters: identity first.
        var auth = new JwtBearerAuth(new FakeTokenIssuer(Role.Admin.INSTANCE));

        var result = auth.requirePermission((String) null, Permission.USERS_READ);

        assertThat(unwrapErr(result)).isInstanceOf(AuthError.Unauthenticated.class);
    }
}
