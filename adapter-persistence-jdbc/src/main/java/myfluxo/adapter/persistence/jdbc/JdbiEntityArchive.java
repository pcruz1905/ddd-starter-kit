package myfluxo.adapter.persistence.jdbc;

import jakarta.inject.Singleton;
import myfluxo.kernel.aggregate.ArchivedSnapshot;
import myfluxo.kernel.aggregate.EntityArchive;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI-backed read of the {@code entity_archive} table. Goes through
 * {@link TransactionalHandle} so a lookup inside a
 * {@code uow.inTransaction} block sees uncommitted writes from the
 * surrounding transaction (consistent with the rest of the repositories).
 *
 * <p>Write-side is the {@code EntityArchiveSink} (an outbox consumer) —
 * there is no write method here.
 */
@Singleton
public final class JdbiEntityArchive implements EntityArchive {

    private static final String SELECT_COLUMNS =
        "entity_type, entity_id, payload::text AS payload, archived_at";

    private final TransactionalHandle tx;

    public JdbiEntityArchive(TransactionalHandle tx) {
        this.tx = tx;
    }

    @Override
    public Optional<ArchivedSnapshot> findLatest(String entityType, UUID entityId) {
        return tx.withHandle(h -> h.createQuery(
                "SELECT " + SELECT_COLUMNS
                + " FROM entity_archive"
                + " WHERE entity_type = :entityType AND entity_id = :entityId"
                + " ORDER BY archived_at DESC"
                + " LIMIT 1")
            .bind("entityType", entityType)
            .bind("entityId", entityId)
            .map(JdbiEntityArchive::mapRow)
            .findFirst());
    }

    @Override
    public List<ArchivedSnapshot> findHistory(String entityType, UUID entityId) {
        return tx.withHandle(h -> h.createQuery(
                "SELECT " + SELECT_COLUMNS
                + " FROM entity_archive"
                + " WHERE entity_type = :entityType AND entity_id = :entityId"
                + " ORDER BY archived_at DESC")
            .bind("entityType", entityType)
            .bind("entityId", entityId)
            .map(JdbiEntityArchive::mapRow)
            .list());
    }

    private static ArchivedSnapshot mapRow(ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
            throws SQLException {
        return new ArchivedSnapshot(
            rs.getString("entity_type"),
            rs.getObject("entity_id", UUID.class),
            rs.getString("payload"),
            // pgJDBC has no Instant column-converter for timestamptz;
            // getTimestamp().toInstant() is the portable read path.
            rs.getTimestamp("archived_at").toInstant()
        );
    }
}
