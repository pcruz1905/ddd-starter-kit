package myfluxo.application.auth;

import myfluxo.application.auth.commands.RefreshSessionCommand;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshSessionTest {

    private static <T, E> E err(Result<T, E> r) {
        return r.fold(ok -> { throw new AssertionError("expected Err, got Ok: " + ok); }, e -> e);
    }

    private static <T, E> T ok(Result<T, E> r) {
        return r.fold(o -> o, e -> { throw new AssertionError("expected Ok, got Err: " + e); });
    }

    @Test
    void validToken_rotates_issuesNewAccessAndRefreshTokens() {
        var f = new AuthFixture();
        var registered = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "supersecret123")));
        var firstRefresh = registered.refreshTokenPlaintext();

        var refreshed = ok(f.refreshSession.handle(new RefreshSessionCommand(firstRefresh)));

        assertThat(refreshed.refreshTokenPlaintext()).isNotEqualTo(firstRefresh);
        assertThat(refreshed.accessToken().value()).isNotEqualTo(registered.accessToken().value());
        // After rotation: 2 rows exist — old (revoked+rotated), new (active).
        assertThat(f.refreshTokens.size()).isEqualTo(2);
    }

    @Test
    void invalidToken_returnsInvalidRefreshToken() {
        var f = new AuthFixture();

        var result = f.refreshSession.handle(new RefreshSessionCommand("never-issued"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidRefreshToken.class);
    }

    @Test
    void blankToken_returnsInvalidRefreshToken() {
        var f = new AuthFixture();

        assertThat(err(f.refreshSession.handle(new RefreshSessionCommand(""))))
            .isInstanceOf(AuthError.InvalidRefreshToken.class);
        assertThat(err(f.refreshSession.handle(new RefreshSessionCommand(null))))
            .isInstanceOf(AuthError.InvalidRefreshToken.class);
    }

    @Test
    void reusedRotatedToken_revokesEntireFamily_andReportsReuseDetected() {
        // Production-grade defence:
        //  1. Alice logs in, gets RT-1.
        //  2. Alice refreshes — RT-1 becomes "rotated", RT-2 is the live token.
        //  3. An attacker (who copied RT-1 earlier) presents RT-1 again.
        //  4. Use case detects the reuse, revokes RT-1 *and* RT-2 (the
        //     whole family). Both Alice and the attacker are logged out;
        //     Alice must re-authenticate.
        var f = new AuthFixture();
        var registered = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "supersecret123")));
        var firstToken = registered.refreshTokenPlaintext();
        // Normal rotation by Alice.
        var afterRotation = ok(f.refreshSession.handle(new RefreshSessionCommand(firstToken)));
        var liveTokenHash = f.refreshStrategy.hash(afterRotation.refreshTokenPlaintext());

        // Attacker re-presents the original.
        var result = f.refreshSession.handle(new RefreshSessionCommand(firstToken));

        assertThat(err(result)).isInstanceOf(AuthError.RefreshTokenReuseDetected.class);
        // The successor that was previously live is now revoked too.
        var afterAttack = f.refreshTokens.findByTokenHash(liveTokenHash);
        assertThat(afterAttack).isPresent();
        assertThat(afterAttack.get().revokedAt()).isPresent();
    }
}
