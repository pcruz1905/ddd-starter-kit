package myfluxo.kernel.event;

import java.time.Instant;

/**
 * A fact that has happened in the domain. Domain events are immutable and
 * carry only the data needed to react to them. Concrete events are records:
 *
 * <pre>{@code
 * public record UserRegistered(UserId userId, Email email, Instant occurredAt)
 *     implements DomainEvent {}
 * }</pre>
 */
public interface DomainEvent {
    Instant occurredAt();
}
