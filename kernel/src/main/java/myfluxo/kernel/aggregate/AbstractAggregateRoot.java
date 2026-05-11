package myfluxo.kernel.aggregate;

import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.id.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Default base class for aggregates. Provides:
 * <ul>
 *     <li><b>Domain event registration</b> — {@link #recordEvent} accumulates
 *         events drained later by the application layer.</li>
 *     <li><b>Optimistic concurrency version</b> — {@link #version} +
 *         {@link #isNew} let the repository tell INSERT from UPDATE and
 *         enforce the version check.</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *     <li>A brand-new aggregate is created via a no-arg call to
 *         {@link #AbstractAggregateRoot()} — {@code version=0}, {@code isNew=true}.</li>
 *     <li>An existing aggregate is rehydrated via
 *         {@link #AbstractAggregateRoot(long)} — {@code version=N},
 *         {@code isNew=false}.</li>
 *     <li>After the repository persists the aggregate it calls
 *         {@link #markPersisted} with the version that was actually written
 *         to storage — the in-memory {@code version} adopts that value and
 *         {@code isNew} flips false.</li>
 * </ol>
 *
 * <p>Events accumulate as the aggregate mutates ({@code recordEvent(...)}
 * in a behavior method). They are drained ({@link #pullEvents}) by the
 * application layer <strong>after</strong> the unit of work commits — never
 * before, never inside the aggregate itself.
 */
public abstract class AbstractAggregateRoot<ID extends Identifier<?>> implements AggregateRoot<ID> {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Current version. 0 for a new aggregate; ≥0 for a rehydrated one. */
    private long version;

    /** True until the aggregate is first persisted. */
    private boolean isNew;

    /** Constructor for brand-new aggregates (created via a static factory). */
    protected AbstractAggregateRoot() {
        this.version = 0L;
        this.isNew = true;
    }

    /**
     * Constructor for rehydration. The persistence adapter passes the
     * version it read from storage.
     */
    protected AbstractAggregateRoot(long version) {
        if (version < 0L) {
            throw new IllegalArgumentException("version must be >= 0, was " + version);
        }
        this.version = version;
        this.isNew = false;
    }

    // ── Events ──────────────────────────────────────────────────────────

    protected final void recordEvent(DomainEvent event) {
        if (event == null) {
            throw new NullPointerException("event cannot be null");
        }
        pendingEvents.add(event);
    }

    public final List<DomainEvent> peekEvents() {
        return List.copyOf(pendingEvents);
    }

    public final List<DomainEvent> pullEvents() {
        var copy = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return copy;
    }

    public final boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }

    // ── Optimistic concurrency ──────────────────────────────────────────

    /** Version this aggregate was loaded at (or 0 for a new aggregate). */
    public final long version() {
        return version;
    }

    /** True if this aggregate has never been persisted. */
    public final boolean isNew() {
        return isNew;
    }

    /**
     * Called by the repository <em>after</em> a successful insert or
     * version-checked update. Adopts the version that was actually written
     * to storage and flips {@link #isNew} false so a subsequent save uses
     * UPDATE, not INSERT.
     *
     * <p>The repository owns the version-bump rule (typically
     * {@code version() + 1}) so it can use the same number in the SQL row,
     * the UPDATE's WHERE clause, and this call. Passing it in here makes
     * that one expression the single source of truth instead of having
     * "+1" duplicated across the aggregate and every repository
     * implementation.
     *
     * @param persistedVersion the version that was just written to storage;
     *     must strictly increase from the current {@link #version}
     */
    public final void markPersisted(long persistedVersion) {
        if (persistedVersion <= this.version) {
            throw new IllegalArgumentException(
                "persistedVersion must strictly increase: was=" + this.version
                    + ", got=" + persistedVersion);
        }
        this.version = persistedVersion;
        this.isNew = false;
    }
}
