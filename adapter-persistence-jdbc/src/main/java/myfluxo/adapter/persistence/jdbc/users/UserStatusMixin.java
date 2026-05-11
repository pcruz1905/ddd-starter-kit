package myfluxo.adapter.persistence.jdbc.users;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import myfluxo.domain.users.model.UserStatus;

/**
 * Jackson mixin that adds polymorphic-type discrimination to the sealed
 * {@link UserStatus} hierarchy <em>without</em> annotating the domain
 * type itself.
 *
 * <p>Without this, serializing {@code UserStatus.Pending(since)} produces
 * {@code {"since": "..."}} — fine for write, but on read Jackson cannot
 * tell {@code Pending} apart from {@code Active}. The mixin makes the
 * output {@code {"type": "PENDING", "since": "..."}} which round-trips
 * cleanly.
 *
 * <p>Registered on the shared {@link myfluxo.adapter.persistence.jdbc.JsonMapper}
 * so every event and every snapshot serialized for the outbox/archive
 * uses the same discriminator scheme. The domain layer stays free of
 * Jackson annotations — persistence shape lives in the persistence
 * adapter.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserStatus.Pending.class, name = "PENDING"),
    @JsonSubTypes.Type(value = UserStatus.Active.class, name = "ACTIVE"),
    @JsonSubTypes.Type(value = UserStatus.Deactivated.class, name = "DEACTIVATED")
})
public abstract class UserStatusMixin {
}
