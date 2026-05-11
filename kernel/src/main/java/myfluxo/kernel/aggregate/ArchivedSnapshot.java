package myfluxo.kernel.aggregate;

import java.time.Instant;
import java.util.UUID;

/**
 * A row read from {@code entity_archive}: a frozen JSON snapshot of an
 * aggregate that was hard-deleted from its primary table.
 *
 * <p>Persistence-agnostic — the {@link EntityArchive} port returns these,
 * and an {@link AggregateRestorer} consumes them to rebuild the aggregate.
 *
 * @param entityType  short domain name of the aggregate, e.g. {@code "User"}
 * @param entityId    the aggregate's branded id (UUID v7)
 * @param payloadJson raw JSON snapshot — opaque at the port level; the
 *                    restorer for this {@code entityType} knows the shape
 * @param archivedAt  server clock when the snapshot was written
 */
public record ArchivedSnapshot(
    String entityType,
    UUID entityId,
    String payloadJson,
    Instant archivedAt
) {
    public ArchivedSnapshot {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must be non-blank");
        }
        if (entityId == null) {
            throw new NullPointerException("entityId");
        }
        if (payloadJson == null) {
            throw new NullPointerException("payloadJson");
        }
        if (archivedAt == null) {
            throw new NullPointerException("archivedAt");
        }
    }
}
