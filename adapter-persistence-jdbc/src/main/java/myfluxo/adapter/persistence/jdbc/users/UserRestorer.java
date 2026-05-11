package myfluxo.adapter.persistence.jdbc.users;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.aggregate.AggregateRestorer;
import myfluxo.kernel.aggregate.ArchivedSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * Rebuilds a {@link User} aggregate from an archived JSON snapshot.
 *
 * <p>Reads the payload of a {@code UserEvent$Deleted} event — same shape
 * as the published event — and reconstructs the aggregate via
 * {@link User#rehydrate}. Caller decides what to do with the result:
 * {@code userRepository.save(restored)} to actually restore the row,
 * inspect the fields for forensics, etc.
 */
@Singleton
public final class UserRestorer implements AggregateRestorer<User> {

    public static final String ENTITY_TYPE = "User";

    private final ObjectMapper json;

    public UserRestorer() {
        this(JsonMapper.create());
    }

    UserRestorer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public User rehydrate(ArchivedSnapshot snapshot) {
        if (!ENTITY_TYPE.equals(snapshot.entityType())) {
            throw new IllegalArgumentException(
                "UserRestorer can only handle entityType='" + ENTITY_TYPE
                    + "', got '" + snapshot.entityType() + "'");
        }
        try {
            JsonNode payload = json.readTree(snapshot.payloadJson());

            UserId userId = readUserId(payload, snapshot.entityId());
            Email email = readEmail(payload);
            UserStatus status = json.treeToValue(payload.get("status"), UserStatus.class);
            Instant createdAt = Instant.parse(payload.get("createdAt").asText());
            long version = payload.get("version").asLong();

            return User.rehydrate(userId, email, status, createdAt, version);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Failed to parse User snapshot for entityId=" + snapshot.entityId(), ex);
        }
    }

    private static UserId readUserId(JsonNode payload, UUID fallback) {
        JsonNode userIdNode = payload.get("userId");
        if (userIdNode != null && userIdNode.isObject()) {
            JsonNode valueNode = userIdNode.get("value");
            if (valueNode != null && valueNode.isTextual()) {
                return new UserId(UUID.fromString(valueNode.asText()));
            }
        }
        // Fall back to the column-level id from the archive row. Keeps
        // recovery robust even if the payload is missing the field for
        // some reason (older snapshot shape, etc.).
        return new UserId(fallback);
    }

    private static Email readEmail(JsonNode payload) {
        JsonNode emailNode = payload.get("email");
        if (emailNode == null) {
            throw new IllegalStateException("snapshot missing 'email' field");
        }
        if (emailNode.isObject()) {
            return new Email(emailNode.get("value").asText());
        }
        return new Email(emailNode.asText());
    }
}
