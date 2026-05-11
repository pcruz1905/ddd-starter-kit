package myfluxo.kernel.aggregate;

/**
 * Thrown by a repository when an update fails because another writer
 * advanced the aggregate's version between read and write.
 *
 * <p>The caller's options are usually:
 * <ol>
 *     <li>Reload the aggregate, re-apply the change, retry.</li>
 *     <li>Surface to the user as "the record was changed elsewhere".</li>
 * </ol>
 *
 * <p>HTTP adapters typically map this to {@code 409 Conflict}.
 *
 * <p>This is a {@link RuntimeException} rather than a checked exception
 * because conflicts are rare and recovery is a use-case-level decision —
 * we don't want every save site to have a {@code throws} clause.
 */
public final class OptimisticConcurrencyException extends RuntimeException {

    private final Object aggregateId;
    private final long expectedVersion;

    public OptimisticConcurrencyException(Object aggregateId, long expectedVersion) {
        super("Optimistic concurrency conflict for aggregate id=" + aggregateId
            + " expectedVersion=" + expectedVersion
            + " (another writer advanced the version)");
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
    }

    public Object aggregateId() {
        return aggregateId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
