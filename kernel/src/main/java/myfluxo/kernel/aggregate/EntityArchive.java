package myfluxo.kernel.aggregate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port for {@code entity_archive} — the audit/restore counterpart
 * of the outbox-driven delete pattern.
 *
 * <p>Aggregates that emit {@code XxxEvent$Deleted} events have their full
 * snapshot persisted here by the outbox sink. Restoration code queries
 * this port to fetch the snapshot, then hands it to an
 * {@link AggregateRestorer} to rehydrate into a domain aggregate.
 *
 * <p>Write side is the {@code EntityArchiveSink} (an outbox consumer);
 * there is no domain-facing write port because archive rows are written
 * as a side effect of dispatching deletion events, not by application
 * code directly.
 */
public interface EntityArchive {

    /**
     * The most recent snapshot for a given aggregate. Use this when you
     * just want to restore the last known state.
     */
    Optional<ArchivedSnapshot> findLatest(String entityType, UUID entityId);

    /**
     * All snapshots for a given aggregate, newest first. Useful when an
     * aggregate has been archived more than once (rare but possible —
     * e.g. archive, restore, edit, archive again) and you want the
     * history.
     */
    List<ArchivedSnapshot> findHistory(String entityType, UUID entityId);
}
