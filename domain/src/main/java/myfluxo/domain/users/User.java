package myfluxo.domain.users;

import myfluxo.domain.users.errors.UserError;
import myfluxo.domain.users.events.UserEvent;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.aggregate.AbstractAggregateRoot;
import myfluxo.kernel.result.Result;

import java.time.Instant;

/**
 * User aggregate root. All mutations go through methods that enforce
 * invariants; state transitions return {@link Result} so callers see the
 * failure modes in the type signature.
 *
 * <p>Construction is private — use {@link #register} for new users,
 * {@link #rehydrate} for persistence reconstitution.
 */
public final class User extends AbstractAggregateRoot<UserId> {

    private final UserId id;
    private Email email;
    private UserStatus status;
    private final Instant createdAt;

    /** New aggregate — version=0, isNew=true. */
    private User(UserId id, Email email, UserStatus status, Instant createdAt) {
        super();
        this.id = id;
        this.email = email;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Rehydration — carries the loaded version from storage. */
    private User(UserId id, Email email, UserStatus status, Instant createdAt, long version) {
        super(version);
        this.id = id;
        this.email = email;
        this.status = status;
        this.createdAt = createdAt;
    }

    // ── Factory ─────────────────────────────────────────────────────────

    public static User register(UserId id, Email email, Instant now) {
        var user = new User(id, email, new UserStatus.Pending(now), now);
        user.recordEvent(new UserEvent.Registered(id, email, now));
        return user;
    }

    public static User rehydrate(UserId id, Email email, UserStatus status, Instant createdAt, long version) {
        return new User(id, email, status, createdAt, version);
    }

    // ── Behavior ────────────────────────────────────────────────────────

    public Result<User, UserError> activate(Instant now) {
        return switch (this.status) {
            case UserStatus.Pending ignored -> {
                this.status = new UserStatus.Active(now);
                recordEvent(new UserEvent.Activated(this.id, now));
                yield Result.ok(this);
            }
            case UserStatus.Active ignored -> Result.err(new UserError.AlreadyActive(this.id));
            case UserStatus.Deactivated ignored -> Result.err(new UserError.CannotActivateDeactivated(this.id));
        };
    }

    public Result<User, UserError> deactivate(Instant now, String reason) {
        return switch (this.status) {
            case UserStatus.Deactivated ignored -> Result.err(new UserError.AlreadyDeactivated(this.id));
            case UserStatus.Pending _, UserStatus.Active _ -> {
                this.status = new UserStatus.Deactivated(now, reason);
                recordEvent(new UserEvent.Deactivated(this.id, reason, now));
                yield Result.ok(this);
            }
        };
    }

    public void changeEmail(Email newEmail, Instant now) {
        var old = this.email;
        this.email = newEmail;
        recordEvent(new UserEvent.EmailChanged(this.id, old, newEmail, now));
    }

    // ── Accessors ───────────────────────────────────────────────────────

    @Override
    public UserId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
