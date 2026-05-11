package myfluxo.adapter.persistence.jdbc.process;

import myfluxo.adapter.persistence.jdbc.JsonbColumn;
import myfluxo.adapter.persistence.jdbc.Table;
import myfluxo.kernel.process.ProcessInstance;
import myfluxo.kernel.process.ProcessInstanceId;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code process_instances} table. {@link ProcessInstance}
 * is a record carrying domain types ({@link ProcessInstanceId}, the
 * {@code Status} enum); this row uses raw JDBC-friendly types so JDBI's
 * {@code ConstructorMapper} can map columns directly by component name.
 *
 * <p>The {@code state} column holds Postgres {@code jsonb}; marking it
 * with {@link JsonbColumn} causes {@link Table}-generated INSERT and
 * UPDATE statements to emit {@code CAST(:state AS jsonb)} so the binding
 * round-trips without a manual cast.
 */
public record ProcessInstanceRow(
    UUID id,
    String processType,
    String correlationKey,
    String status,
    @JsonbColumn String state,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    public static final Table<ProcessInstanceRow> TABLE =
        Table.of("process_instances", ProcessInstanceRow.class);

    /**
     * {@code ProcessInstance} is a record with no {@code markPersisted}
     * counterpart to {@code User}'s mutable version, so the aggregate's
     * own {@code version()} doesn't always reflect what should land in
     * the row: on a fresh save the in-memory aggregate is still at
     * version 0 while the row needs version 1. The caller passes the
     * intended row version explicitly.
     */
    public static ProcessInstanceRow fromAggregate(ProcessInstance pi, long rowVersion) {
        return new ProcessInstanceRow(
            pi.id().value(),
            pi.processType(),
            pi.correlationKey(),
            pi.status().name(),
            pi.state(),
            pi.createdAt(),
            pi.updatedAt(),
            rowVersion
        );
    }

    public ProcessInstance toAggregate() {
        return new ProcessInstance(
            new ProcessInstanceId(id),
            processType,
            correlationKey,
            ProcessInstance.Status.valueOf(status),
            state,
            createdAt,
            updatedAt,
            version
        );
    }
}
