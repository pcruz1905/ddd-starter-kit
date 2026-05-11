package myfluxo.application.auth;

import myfluxo.application.auth.commands.LogoutCommand;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogoutTest {

    private static <T, E> E err(Result<T, E> r) {
        return r.fold(ok -> { throw new AssertionError("expected Err, got Ok: " + ok); }, e -> e);
    }

    private static <T, E> T ok(Result<T, E> r) {
        return r.fold(o -> o, e -> { throw new AssertionError("expected Ok, got Err: " + e); });
    }

    @Test
    void validToken_isRevoked() {
        var f = new AuthFixture();
        var session = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "supersecret123")));

        ok(f.logout.handle(new LogoutCommand(session.refreshTokenPlaintext())));

        var stored = f.refreshTokens.findByTokenHash(
            f.refreshStrategy.hash(session.refreshTokenPlaintext()));
        assertThat(stored).isPresent();
        assertThat(stored.get().revokedAt()).isPresent();
    }

    @Test
    void unknownToken_returnsInvalidRefreshToken() {
        var f = new AuthFixture();

        var result = f.logout.handle(new LogoutCommand("never-issued"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidRefreshToken.class);
    }

    @Test
    void otherSessions_areNotAffected() {
        // Logout revokes ONE session, not all of them. Browser tab A
        // logging out leaves browser tab B alive.
        var f = new AuthFixture();
        ok(f.register.handle(new RegisterCommand("alice@example.com", "supersecret123")));
        var sessionA = ok(f.login.handle(new myfluxo.application.auth.commands.LoginCommand(
            "alice@example.com", "supersecret123")));
        var sessionB = ok(f.login.handle(new myfluxo.application.auth.commands.LoginCommand(
            "alice@example.com", "supersecret123")));

        ok(f.logout.handle(new LogoutCommand(sessionA.refreshTokenPlaintext())));

        var bHash = f.refreshStrategy.hash(sessionB.refreshTokenPlaintext());
        var b = f.refreshTokens.findByTokenHash(bHash).orElseThrow();
        assertThat(b.revokedAt()).as("Session B must still be active").isEmpty();
    }
}
