package myfluxo.domain.users.events;

import myfluxo.domain.users.User;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.event.DomainEvent;

import java.time.Instant;

/**
 * All domain events emitted by the User aggregate, as a sealed sum type.
 * Subscribers pattern-match exhaustively; the compiler catches every added
 * variant at every subscriber.
 */
public sealed interface UserEvent extends DomainEvent {

    UserId userId();

    record Registered(UserId userId, Email email, Instant occurredAt) implements UserEvent {}

    record Activated(UserId userId, Instant occurredAt) implements UserEvent {}

    record Deactivated(UserId userId, String reason, Instant occurredAt) implements UserEvent {}

    record EmailChanged(UserId userId, Email oldEmail, Email newEmail, Instant occurredAt) implements UserEvent {}

    /**
     * Emitted when a User row is being removed from the primary table —
     * the GDPR/compliance "soft delete via outbox" pattern. Carries a
     * full snapshot so the outbox archive sink (or any other consumer)
     * has everything needed to recover, audit, or forensically inspect.
     *
     * <p>Distinct from {@link Deactivated}, and distinct from any future
     * domain status like {@code Archived}. Those describe states an
     * aggregate is in while it still exists. {@code Deleted} signals the
     * row is being removed from its primary table entirely; the snapshot
     * is preserved in {@code entity_archive} by the outbox sink.
     */
    record Deleted(
        UserId userId,
        Email email,
        UserStatus status,
        Instant createdAt,
        long version,
        Instant occurredAt
    ) implements UserEvent {

        public static Deleted from(User user, Instant now) {
            return new Deleted(
                user.id(),
                user.email(),
                user.status(),
                user.createdAt(),
                user.version(),
                now
            );
        }
    }
}
