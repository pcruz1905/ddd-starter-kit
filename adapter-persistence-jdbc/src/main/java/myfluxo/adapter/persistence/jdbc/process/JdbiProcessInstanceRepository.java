package myfluxo.adapter.persistence.jdbc.process;

import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.RecordSql;
import myfluxo.adapter.persistence.jdbc.TransactionalHandle;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.process.ProcessInstance;
import myfluxo.kernel.process.ProcessInstanceId;
import myfluxo.kernel.process.ProcessInstanceRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI + Postgres implementation of {@link ProcessInstanceRepository}.
 *
 * <p>Operates through {@link TransactionalHandle} so a save inside a
 * use-case transaction participates in that transaction — the same way
 * aggregate saves do. This matters for the
 * "advance-process-and-publish-next-command" pattern: both writes
 * commit atomically or both roll back.
 *
 * <p>Statement shapes derive from {@link ProcessInstanceRow#TABLE};
 * {@code state} is marked {@code @JsonbColumn} so INSERT/UPDATE emit
 * {@code CAST(:state AS jsonb)} automatically.
 *
 * <p>{@code save} picks INSERT vs UPDATE on {@code instance.version() == 0L}:
 * version-zero rows are brand new; everything else is an update with a
 * {@code WHERE version = :expectedVersion} optimistic-concurrency check.
 * The instance carries the post-mutation version (advanced/completed/failed
 * already bumped it), so the WHERE-clause expected version is
 * {@code version - 1}.
 */
@Singleton
public final class JdbiProcessInstanceRepository implements ProcessInstanceRepository {

    private static final String COL_ID = ProcessInstanceRow.TABLE.col("id");
    private static final String COL_PROCESS_TYPE = ProcessInstanceRow.TABLE.col("processType");
    private static final String COL_CORRELATION_KEY = ProcessInstanceRow.TABLE.col("correlationKey");
    private static final String COL_STATUS = ProcessInstanceRow.TABLE.col("status");
    private static final String COL_UPDATED_AT = ProcessInstanceRow.TABLE.col("updatedAt");

    private static final String FIND_BY_ID =
        ProcessInstanceRow.TABLE.selectAll() + " WHERE " + COL_ID + " = :id";
    private static final String FIND_RUNNING_BY_CORRELATION =
        ProcessInstanceRow.TABLE.selectAll()
        + " WHERE " + COL_PROCESS_TYPE + " = :processType"
        + "   AND " + COL_CORRELATION_KEY + " = :correlationKey"
        + "   AND " + COL_STATUS + " = 'RUNNING'";
    private static final String FIND_RUNNING =
        ProcessInstanceRow.TABLE.selectAll()
        + " WHERE " + COL_PROCESS_TYPE + " = :processType"
        + "   AND " + COL_STATUS + " = 'RUNNING'"
        + " ORDER BY " + COL_UPDATED_AT
        + " LIMIT :limit";
    private static final String INSERT = ProcessInstanceRow.TABLE.insert();
    private static final String UPDATE =
        ProcessInstanceRow.TABLE.updateByIdWithVersion("createdAt");

    private final TransactionalHandle tx;

    public JdbiProcessInstanceRepository(TransactionalHandle tx) {
        this.tx = tx;
    }

    @Override
    public Optional<ProcessInstance> findById(ProcessInstanceId id) {
        return tx.withHandle(h -> h.createQuery(FIND_BY_ID)
            .bind("id", id.value())
            .mapTo(ProcessInstanceRow.class)
            .findFirst())
            .map(ProcessInstanceRow::toAggregate);
    }

    @Override
    public Optional<ProcessInstance> findRunningByCorrelationKey(
        String processType,
        String correlationKey
    ) {
        return tx.withHandle(h -> h.createQuery(FIND_RUNNING_BY_CORRELATION)
            .bind("processType", processType)
            .bind("correlationKey", correlationKey)
            .mapTo(ProcessInstanceRow.class)
            .findFirst())
            .map(ProcessInstanceRow::toAggregate);
    }

    @Override
    public List<ProcessInstance> findRunning(String processType, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        return tx.withHandle(h -> h.createQuery(FIND_RUNNING)
            .bind("processType", processType)
            .bind("limit", limit)
            .mapTo(ProcessInstanceRow.class)
            .list())
            .stream()
            .map(ProcessInstanceRow::toAggregate)
            .toList();
    }

    @Override
    public void save(ProcessInstance instance) {
        if (instance.version() == 0L) {
            // Fresh aggregate — persist as version 1. The in-memory
            // ProcessInstance record stays at 0; callers reload to see
            // the persisted version.
            insert(ProcessInstanceRow.fromAggregate(instance, 1L));
        } else {
            updateWithVersionCheck(instance);
        }
    }

    private void insert(ProcessInstanceRow row) {
        tx.useHandle(h -> h.createUpdate(INSERT)
            .bindMap(RecordSql.bindMap(row))
            .execute());
    }

    private void updateWithVersionCheck(ProcessInstance instance) {
        // advanced/completed/failed have already bumped instance.version()
        // to the post-mutation value. Row carries that; WHERE guards on
        // the pre-mutation version.
        var row = ProcessInstanceRow.fromAggregate(instance, instance.version());
        long expectedVersion = instance.version() - 1L;
        int rows = tx.withHandle(h -> h.createUpdate(UPDATE)
            .bindMap(RecordSql.bindMap(row))
            .bind("expectedVersion", expectedVersion)
            .execute());
        if (rows == 0) {
            throw new OptimisticConcurrencyException(
                new ProcessAggregateId(instance.id().value()),
                expectedVersion
            );
        }
    }

    /**
     * {@link OptimisticConcurrencyException} expects an {@link Identifier};
     * this thin wrapper lets the process-instance id satisfy that contract
     * without forcing every domain concept to be an aggregate.
     */
    private record ProcessAggregateId(UUID value) implements Identifier<UUID> {}
}
