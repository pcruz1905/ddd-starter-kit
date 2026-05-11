package myfluxo.application.auth;

import myfluxo.application.auth.commands.ChangePasswordCommand;
import myfluxo.application.auth.commands.LoginCommand;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChangePasswordTest {

    private static <T, E> E err(Result<T, E> r) {
        return r.fold(ok -> { throw new AssertionError("expected Err, got Ok: " + ok); }, e -> e);
    }

    private static <T, E> T ok(Result<T, E> r) {
        return r.fold(o -> o, e -> { throw new AssertionError("expected Ok, got Err: " + e); });
    }

    @Test
    void correctOldPassword_replacesHash_andRevokesOtherSessions() {
        var f = new AuthFixture();
        var s1 = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "originalpw123")));
        // Second login: another active session (different device).
        var s2 = ok(f.login.handle(new LoginCommand("alice@example.com", "originalpw123")));

        ok(f.changePassword.handle(new ChangePasswordCommand(
            s1.userId(), "originalpw123", "newpassword456"
        )));

        // Old password no longer works.
        assertThat(err(f.login.handle(new LoginCommand("alice@example.com", "originalpw123"))))
            .isInstanceOf(AuthError.InvalidCredentials.class);
        // New password works.
        ok(f.login.handle(new LoginCommand("alice@example.com", "newpassword456")));
        // Both prior sessions are revoked.
        var s1Hash = f.refreshStrategy.hash(s1.refreshTokenPlaintext());
        var s2Hash = f.refreshStrategy.hash(s2.refreshTokenPlaintext());
        assertThat(f.refreshTokens.findByTokenHash(s1Hash).orElseThrow().revokedAt())
            .as("Original session must be revoked after password change")
            .isPresent();
        assertThat(f.refreshTokens.findByTokenHash(s2Hash).orElseThrow().revokedAt())
            .as("Other device session must be revoked after password change")
            .isPresent();
    }

    @Test
    void wrongOldPassword_returnsOldPasswordMismatch() {
        var f = new AuthFixture();
        var s = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "originalpw123")));

        var result = f.changePassword.handle(new ChangePasswordCommand(
            s.userId(), "wrongoldpassword", "newpassword456"
        ));

        assertThat(err(result)).isInstanceOf(AuthError.OldPasswordMismatch.class);
        // Hash unchanged.
        assertThat(f.credentials.findByUserId(s.userId()).orElseThrow().passwordHash())
            .isEqualTo(f.hasher.hash(myfluxo.domain.auth.model.Password.of("originalpw123")));
    }

    @Test
    void weakNewPassword_returnsWeakPassword() {
        var f = new AuthFixture();
        var s = ok(f.register.handle(
            new RegisterCommand("alice@example.com", "originalpw123")));

        var result = f.changePassword.handle(new ChangePasswordCommand(
            s.userId(), "originalpw123", "short"
        ));

        assertThat(err(result)).isInstanceOf(AuthError.WeakPassword.class);
    }
}
