package myfluxo.domain.users;

import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.errors.UserError;
import myfluxo.domain.users.events.UserEvent;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Email EMAIL = new Email("alice@example.com");

    @Test
    void register_createsPendingUserAndRecordsRegisteredEvent() {
        var user = User.register(UserId.newId(), EMAIL, NOW);

        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.status()).isInstanceOf(UserStatus.Pending.class);
        assertThat(user.peekEvents()).hasSize(1);
        assertThat(user.peekEvents().getFirst()).isInstanceOf(UserEvent.Registered.class);
    }

    @Test
    void activate_recordsActivatedEvent() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.pullEvents();   // drain Registered to isolate the assertion

        user.activate(NOW.plusSeconds(60));

        assertThat(user.peekEvents()).hasSize(1);
        assertThat(user.peekEvents().getFirst()).isInstanceOf(UserEvent.Activated.class);
    }

    @Test
    void activate_failsWhenAlreadyActiveAndDoesNotRecordEvent() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.activate(NOW);
        user.pullEvents();   // drain prior events

        var result = user.activate(NOW.plusSeconds(60));

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<User, UserError>) result).error())
            .isInstanceOf(UserError.AlreadyActive.class);
        assertThat(user.peekEvents()).isEmpty();
    }

    @Test
    void deactivate_failsWhenAlreadyDeactivated() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.deactivate(NOW, "first time");

        var result = user.deactivate(NOW.plusSeconds(60), "second time");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<User, UserError>) result).error())
            .isInstanceOf(UserError.AlreadyDeactivated.class);
    }

    @Test
    void deactivate_carriesReasonInBothStateAndEvent() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.pullEvents();

        user.deactivate(NOW.plusSeconds(60), "gone");

        var status = (UserStatus.Deactivated) user.status();
        assertThat(status.reason()).isEqualTo("gone");
        assertThat(user.peekEvents()).hasSize(1);
        var evt = (UserEvent.Deactivated) user.peekEvents().getFirst();
        assertThat(evt.reason()).isEqualTo("gone");
    }

    @Test
    void activate_failsWhenDeactivated() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.deactivate(NOW.plusSeconds(60), "left the team");

        var result = user.activate(NOW.plusSeconds(120));

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<User, UserError>) result).error())
            .isInstanceOf(UserError.CannotActivateDeactivated.class);
    }

    @Test
    void changeEmail_recordsEmailChangedWithOldAndNew() {
        var user = User.register(UserId.newId(), EMAIL, NOW);
        user.pullEvents();
        var newEmail = new Email("alice2@example.com");

        user.changeEmail(newEmail, NOW.plusSeconds(60));

        assertThat(user.email()).isEqualTo(newEmail);
        var evt = (UserEvent.EmailChanged) user.peekEvents().getFirst();
        assertThat(evt.oldEmail()).isEqualTo(EMAIL);
        assertThat(evt.newEmail()).isEqualTo(newEmail);
    }

    @Test
    void rehydrate_doesNotRecordEvent() {
        var user = User.rehydrate(UserId.newId(), EMAIL, new UserStatus.Active(NOW), NOW, 0L);
        assertThat(user.peekEvents()).isEmpty();
    }
}
