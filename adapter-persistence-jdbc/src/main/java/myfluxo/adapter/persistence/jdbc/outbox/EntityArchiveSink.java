package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import myfluxo.kernel.id.UuidV7;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Outbox sink that catches deletion events and writes their snapshot to
 * the {@code entity_archive} table — the <b>hard-delete-with-archive</b>
 * pattern. Aggregates that need to be removed from their primary table
 * emit a {@code XxxEvent$Deleted} event in the same transaction as the
 * DELETE; this sink writes the snapshot durably for recovery and audit.
 *
 * <p>This is <em>not</em> a soft-delete: the primary row is gone after
 * the transaction commits. We distinguish it deliberately so that
 * {@code XxxStatus.Archived} (a discontinued-but-still-existing domain
 * state) does not collide with {@code XxxEvent$Deleted} (the row went
 * away). The table is {@code entity_archive} because it archives the
 * snapshots of deletions — two distinct concerns, named accordingly.
 *
 * <p>Events that don't match the deletion convention pass through
 * silently. The sink is additive — meant to be composed with other sinks
 * (Kafka producer, webhook dispatcher, logger, etc.).
 *
 * <h2>Convention</h2>
 * <p>A deletion event must be:
 * <ol>
 *     <li>A nested record inside a sealed {@code XxxEvent} interface,
 *         named {@code Deleted}. Full class name pattern:
 *         {@code <pkg>.XxxEvent$Deleted}.</li>
 *     <li>Carrying a top-level field {@code <xxx>Id} (lowercase first
 *         letter of the entity name) whose value is a branded record
 *         like {@code XxxId(UUID value)}, serialized as
 *         {@code {"value": "uuid..."}}.</li>
 * </ol>
 *
 * <p>The entity_type column gets the simple entity name (e.g. "User",
 * "Order"); the entity_id column gets the inner UUID; the payload column
 * gets the full event JSON.
 */
public final class EntityArchiveSink implements BiConsumer<String, JsonNode> {

    private static final Logger LOG = LoggerFactory.getLogger(EntityArchiveSink.class);
    private static final String DELETED_SUFFIX = "$Deleted";
    private static final String EVENT_SUFFIX = "Event";

    private final Jdbi jdbi;

    public EntityArchiveSink(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void accept(String eventType, JsonNode payload) {
        if (!eventType.endsWith(DELETED_SUFFIX)) return;

        String entityType = deriveEntityType(eventType);
        if (entityType == null) {
            LOG.error("Deletion event {} cannot be archived — class name does not "
                + "match the `<pkg>.XxxEvent$Deleted` convention. Row will not be "
                + "written to entity_archive.", eventType);
            return;
        }
        UUID entityId = extractEntityId(payload, entityType);
        if (entityId == null) {
            LOG.error("Deletion event {} cannot be archived — payload is missing "
                + "the conventional `{}Id` branded-uuid field. Row will not be "
                + "written to entity_archive.",
                eventType,
                Character.toLowerCase(entityType.charAt(0)) + entityType.substring(1));
            return;
        }

        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO entity_archive
                    (id, entity_type, entity_id, payload, archived_at)
                VALUES
                    (:id, :entityType, :entityId, CAST(:payload AS jsonb), :archivedAt)
                """)
            .bind("id", UuidV7.generate())
            .bind("entityType", entityType)
            .bind("entityId", entityId)
            .bind("payload", payload.toString())
            .bind("archivedAt", Instant.now())
            .execute());
    }

    /**
     * "myfluxo.domain.users.events.UserEvent$Deleted" → "User".
     * Returns null if the class name doesn't match the nested-sealed pattern.
     */
    static String deriveEntityType(String eventClassName) {
        int dollarIdx = eventClassName.indexOf('$');
        if (dollarIdx < 0) return null;
        String beforeDollar = eventClassName.substring(0, dollarIdx);
        int lastDot = beforeDollar.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? beforeDollar.substring(lastDot + 1) : beforeDollar;
        if (simpleName.endsWith(EVENT_SUFFIX) && simpleName.length() > EVENT_SUFFIX.length()) {
            return simpleName.substring(0, simpleName.length() - EVENT_SUFFIX.length());
        }
        return simpleName;
    }

    /**
     * Pulls the branded id out of the payload by convention:
     * "User" → look up "userId" → return inner "value" as UUID.
     */
    static UUID extractEntityId(JsonNode payload, String entityType) {
        if (entityType == null || entityType.isEmpty()) return null;
        String idField = Character.toLowerCase(entityType.charAt(0))
            + entityType.substring(1) + "Id";
        JsonNode idNode = payload.get(idField);
        if (idNode == null) return null;
        JsonNode valueNode = idNode.isObject() ? idNode.get("value") : idNode;
        if (valueNode == null || !valueNode.isTextual()) return null;
        try {
            return UUID.fromString(valueNode.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
