package myfluxo.application.auth;

import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.shared.model.Email;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterTest {

    private static <T, E> E err(Result<T, E> r) {
        return r.fold(ok -> { throw new AssertionError("expected Err, got Ok: " + ok); }, e -> e);
    }

    private static <T, E> T ok(Result<T, E> r) {
        return r.fold(o -> o, e -> { throw new AssertionError("expected Ok, got Err: " + e); });
    }

    @Test
    void success_persistsUserCredentialsAndRefreshToken_andPublishesRegisteredEvent() {
        var f = new AuthFixture();

        var result = f.register.handle(new RegisterCommand("alice@example.com", "supersecret123"));

        var session = ok(result);
        assertThat(session.refreshTokenPlaintext()).isNotBlank();
        assertThat(f.users.findByEmail(new Email("alice@example.com"))).isPresent();
        assertThat(f.credentials.findByUserId(session.userId())).isPresent();
        assertThat(f.refreshTokens.size()).isEqualTo(1);
        assertThat(f.events.published)
            .as("UserEvent.Registered must hit the outbox in the same UoW")
            .hasSize(1);
    }

    @Test
    void emailAlreadyTaken_returnsErr_doesNotCreateUser() {
        var f = new AuthFixture();
        f.register.handle(new RegisterCommand("alice@example.com", "supersecret123"));
        int usersBefore = f.refreshTokens.size();

        var second = f.register.handle(
            new RegisterCommand("alice@example.com", "anotherpassword42"));

        assertThat(err(second)).isInstanceOf(AuthError.EmailAlreadyTaken.class);
        assertThat(f.refreshTokens.size()).isEqualTo(usersBefore);
    }

    @Test
    void weakPassword_returnsWeakPassword_withParseReason() {
        var f = new AuthFixture();

        var result = f.register.handle(new RegisterCommand("alice@example.com", "short"));

        var error = err(result);
        assertThat(error).isInstanceOf(AuthError.WeakPassword.class);
        assertThat(((AuthError.WeakPassword) error).reason()).isEqualTo("too_short");
    }

    @Test
    void invalidEmail_returnsInvalidEmail_withParseReason() {
        var f = new AuthFixture();

        var result = f.register.handle(new RegisterCommand("not-an-email", "supersecret123"));

        assertThat(err(result)).isInstanceOf(AuthError.InvalidEmail.class);
    }
}
