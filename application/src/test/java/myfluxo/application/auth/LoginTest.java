package myfluxo.application.auth;

import myfluxo.application.auth.commands.LoginCommand;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTest {

    private static <T, E> E err(Result<T, E> r) {
        return r.fold(ok -> { throw new AssertionError("expected Err, got Ok: " + ok); }, e -> e);
    }

    private static <T, E> T ok(Result<T, E> r) {
        return r.fold(o -> o, e -> { throw new AssertionError("expected Ok, got Err: " + e); });
    }

    @Test
    void correctCredentials_issueAccessAndRefreshTokens() {
        var f = new AuthFixture();
        f.register.handle(new RegisterCommand("alice@example.com", "supersecret123"));
        // Reset the counter from register's setup verify (there isn't one,
        // but for clarity).
        f.hasher.verifyCount.set(0);

        var result = f.login.handle(new LoginCommand("alice@example.com", "supersecret123"));

        var session = ok(result);
        assertThat(session.accessToken().value()).isNotBlank();
        assertThat(session.refreshTokenPlaintext()).isNotBlank();
        assertThat(f.hasher.verifyCount.get()).isEqualTo(1);
    }

    @Test
    void wrongPassword_returnsInvalidCredentials() {
        var f = new AuthFixture();
        f.register.handle(new RegisterCommand("alice@example.com", "supersecret123"));

        var result = f.login.handle(new LoginCommand("alice@example.com", "wrong-password-1"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidCredentials.class);
    }

    @Test
    void missingUser_alsoCallsVerify_forTimingSafety() {
        // The timing-attack defence: even when the user doesn't exist,
        // the use case must still run verify (against the decoy hash)
        // so an attacker can't distinguish "user not found" from
        // "wrong password" by response time.
        var f = new AuthFixture();
        f.hasher.verifyCount.set(0);

        var result = f.login.handle(new LoginCommand("ghost@example.com", "supersecret123"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidCredentials.class);
        assertThat(f.hasher.verifyCount.get())
            .as("verify must be called even when user is missing")
            .isEqualTo(1);
    }

    @Test
    void invalidEmailFormat_returnsInvalidCredentials_andStillRunsVerify() {
        var f = new AuthFixture();
        f.hasher.verifyCount.set(0);

        var result = f.login.handle(new LoginCommand("not-an-email", "supersecret123"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidCredentials.class);
        assertThat(f.hasher.verifyCount.get())
            .as("verify must run on bad email too, to match the missing-user timing")
            .isEqualTo(1);
    }

    @Test
    void invalidPasswordFormat_returnsInvalidCredentials_andSkipsVerify() {
        // Password format validation short-circuits BEFORE we know
        // anything about the user — no DB hit, no verify needed.
        // Returning fast here doesn't leak existence because we
        // never queried.
        var f = new AuthFixture();
        f.hasher.verifyCount.set(0);

        var result = f.login.handle(new LoginCommand("alice@example.com", "short"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidCredentials.class);
        assertThat(f.hasher.verifyCount.get()).isZero();
    }
}
