package myfluxo.kernel.aggregate;

import myfluxo.kernel.id.Identifier;

import java.time.Instant;

/**
 * Opt-in extension of {@link AbstractAggregateRoot} that carries
 * {@code createdAt} + {@code updatedAt} timestamps.
 *
 * <p>Most aggregates in an e-commerce system want both — for audit, for
 * support tools, for sync, for ETag/cache validation. Aggregates that
 * want them simply extend {@code AuditableAggregateRoot} instead of
 * {@code AbstractAggregateRoot}. Aggregates that don't can stay on the
 * lighter base.
 *
 * <p>Discipline: {@code updatedAt} is bumped by the repository (or by
 * the aggregate's own state-transition methods, depending on the
 * pattern you prefer). The aggregate guarantees that {@code updatedAt}
 * never moves backwards. The persistence adapter writes both columns.
 *
 * <p>This is the only timestamp-related state we leak into the kernel.
 * Domain-specific time fields (e.g. {@code Order.placedAt},
 * {@code Subscription.renewedAt}) live in the concrete aggregate — they
 * have meaning beyond audit.
 */
public abstract class AuditableAggregateRoot<ID extends Identifier<?>>
        extends AbstractAggregateRoot<ID> {

    private final Instant createdAt;
    private Instant updatedAt;

    /** New aggregate — version=0, isNew=true, createdAt=updatedAt=now. */
    protected AuditableAggregateRoot(Instant now) {
        super();
        if (now == null) throw new NullPointerException("createdAt");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Rehydration. */
    protected AuditableAggregateRoot(long version, Instant createdAt, Instant updatedAt) {
        super(version);
        if (createdAt == null) throw new NullPointerException("createdAt");
        if (updatedAt == null) throw new NullPointerException("updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                "updatedAt must not predate createdAt: createdAt="
                    + createdAt + ", updatedAt=" + updatedAt);
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public final Instant createdAt() {
        return createdAt;
    }

    public final Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Advance {@code updatedAt}. Typically called by the repository
     * just before persisting a mutation. Monotonic: never moves
     * backwards.
     */
    public final void touch(Instant now) {
        if (now == null) throw new NullPointerException("now");
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                "updatedAt cannot move backwards: was=" + updatedAt + ", got=" + now);
        }
        this.updatedAt = now;
    }
}
