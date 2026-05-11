package myfluxo.kernel.process;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link ProcessInstance}.
 *
 * <p>Implementations enforce optimistic concurrency: {@link #save}
 * matches on {@code (id, version)} so two dispatchers handling the same
 * instance cannot silently clobber each other — the loser sees a
 * concurrency conflict and retries on the next poll.
 */
public interface ProcessInstanceRepository {

    Optional<ProcessInstance> findById(ProcessInstanceId id);

    /**
     * Look up a running instance by its caller-supplied correlation key.
     * Used by the dispatcher to route an inbound event to the right
     * instance without having to remember the instance id.
     */
    Optional<ProcessInstance> findRunningByCorrelationKey(
        String processType,
        String correlationKey
    );

    /** All running instances. Bounded by {@code limit} for batched processing. */
    List<ProcessInstance> findRunning(String processType, int limit);

    void save(ProcessInstance instance);
}
