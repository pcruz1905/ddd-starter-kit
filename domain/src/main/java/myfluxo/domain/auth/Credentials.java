package myfluxo.domain.auth;

import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.aggregate.AbstractAggregateRoot;

import java.time.Instant;
import java.util.Objects;

/**
 * Authentication credentials for a single user — owns the password hash.
 *
 * <h2>Identity</h2>
 * Identified by {@link UserId} — Credentials are 1:1 with User. There is
 * no separate {@code CredentialsId}; the credentials for user {@code U}
 * always have id {@code U}.
 *
 * <h2>Bounded context boundary</h2>
 * Credentials live in {@code domain.auth}, deliberately separate from
 * the {@code domain.users.User} aggregate. The User aggregate models
 * the business concept of a participant; Credentials model the
 * authentication artifact. Splitting them keeps each focused — adding
 * a 2FA secret, OAuth identity, or API key doesn't pollute User; adding
 * a {@code billingEmail} to User doesn't touch Credentials.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *     <li>Created when a user registers — fresh hash, version 0.</li>
 *     <li>Hash mutated only via {@link #changePassword}.</li>
 *     <li>Never deleted (cascade-deleted with User if User itself is
 *         hard-deleted; restore re-creates from archive).</li>
 * </ul>
 */
public final class Credentials extends AbstractAggregateRoot<UserId> {

    private final UserId userId;
    private PasswordHash passwordHash;
    private final Instant createdAt;
    private Instant updatedAt;

    /** New credentials — version 0, isNew = true. */
    private Credentials(UserId userId, PasswordHash passwordHash, Instant createdAt) {
        super();
        this.userId = Objects.requireNonNull(userId);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = createdAt;
    }

    /** Rehydration — carries loaded version. */
    private Credentials(
        UserId userId,
        PasswordHash passwordHash,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        super(version);
        this.userId = Objects.requireNonNull(userId);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Credentials create(UserId userId, PasswordHash passwordHash, Instant now) {
        return new Credentials(userId, passwordHash, now);
    }

    public static Credentials rehydrate(
        UserId userId,
        PasswordHash passwordHash,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        return new Credentials(userId, passwordHash, createdAt, updatedAt, version);
    }

    /**
     * Replace the password hash with a new one. Pre-condition: caller
     * has already verified the user's identity (e.g. by checking the
     * old password) — this method does NOT re-check.
     */
    public void changePassword(PasswordHash newHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(newHash);
        this.updatedAt = Objects.requireNonNull(now);
    }

    @Override
    public UserId id() {
        return userId;
    }

    public UserId userId() {
        return userId;
    }

    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
