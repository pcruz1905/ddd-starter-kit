package myfluxo.kernel.process;

import myfluxo.kernel.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted instance of a long-running process (a saga / process
 * manager).
 *
 * <p>Three pieces define an instance:
 * <ul>
 *     <li><b>{@code state}</b> — opaque JSON blob owned by the concrete
 *         process. The kernel doesn't peek inside; each process type
 *         (CheckoutProcess, RefundProcess, …) serialises its own state
 *         shape.</li>
 *     <li><b>{@code processType}</b> — short identifier picking the
 *         {@link ProcessHandler} that interprets the state and reacts
 *         to events.</li>
 *     <li><b>{@code status}</b> — coarse-grained lifecycle the
 *         dispatcher cares about (running / completed / failed). Distinct
 *         from the fine-grained domain-specific state inside {@code state}.</li>
 * </ul>
 *
 * <p>The handler observes {@link DomainEvent}s and advances the
 * instance: returns a new state plus, optionally, a {@code Status}
 * transition. The infrastructure persists the result atomically with
 * the incoming event's outbox row.
 */
public record ProcessInstance(
    ProcessInstanceId id,
    String processType,
    String correlationKey,
    Status status,
    String state,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    public ProcessInstance {
        if (id == null) throw new IllegalArgumentException("id");
        if (processType == null || processType.isBlank())
            throw new IllegalArgumentException("processType");
        if (correlationKey == null || correlationKey.isBlank())
            throw new IllegalArgumentException("correlationKey");
        if (status == null) throw new IllegalArgumentException("status");
        if (state == null) throw new IllegalArgumentException("state");
        if (createdAt == null) throw new IllegalArgumentException("createdAt");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt");
        if (version < 0L) throw new IllegalArgumentException("version must be >= 0");
    }

    public static ProcessInstance start(
        String processType,
        String correlationKey,
        String initialState,
        Instant now
    ) {
        return new ProcessInstance(
            ProcessInstanceId.newId(),
            processType,
            correlationKey,
            Status.RUNNING,
            initialState,
            now,
            now,
            0L
        );
    }

    public ProcessInstance advanced(String newState, Instant now) {
        return new ProcessInstance(id, processType, correlationKey,
            status, newState, createdAt, now, version + 1L);
    }

    public ProcessInstance completed(String finalState, Instant now) {
        return new ProcessInstance(id, processType, correlationKey,
            Status.COMPLETED, finalState, createdAt, now, version + 1L);
    }

    public ProcessInstance failed(String failureState, Instant now) {
        return new ProcessInstance(id, processType, correlationKey,
            Status.FAILED, failureState, createdAt, now, version + 1L);
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    /** Coarse lifecycle the infrastructure tracks for dispatching. */
    public enum Status { RUNNING, COMPLETED, FAILED }

    /** Convenience for tests / generic helpers. */
    public UUID idValue() {
        return id.value();
    }
}
