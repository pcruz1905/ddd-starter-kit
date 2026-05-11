package myfluxo.kernel.job;

/**
 * One unit of scheduled background work, typed end-to-end on its
 * payload shape.
 *
 * <p>Inspired by sellhub's {@code async-channel} groups + handlers: a
 * named, typed contract for "do this thing later." Examples:
 * {@code SendWelcomeEmail<UserId>},
 * {@code ExpireAbandonedCarts<NoPayload>},
 * {@code ProcessRefund<RefundCommand>}.
 *
 * <p>Difference from {@link myfluxo.kernel.event.EventSubscriber}:
 * <ul>
 *     <li>An {@code EventSubscriber} fires <em>because a domain event
 *         was emitted</em>. The event is the cause.</li>
 *     <li>A {@code Job} fires <em>because someone (or a schedule)
 *         enqueued it</em>. The schedule is the cause.</li>
 * </ul>
 *
 * <p>The runner uses {@link #payloadType()} to deserialise the
 * persisted JSON into {@code P} before calling {@link #execute}. The
 * job stays typed; deserialisation is the adapter's job.
 *
 * <p>Implementations should be idempotent — the runner is at-least-once.
 *
 * @param <P> the payload type. Typically a record. Use a marker like
 *            {@code record None() {}} when the job needs no input.
 */
public interface Job<P> {

    /**
     * Unique short name. Used as the dispatch key — the runner picks
     * the job by matching {@code name()} to the row's {@code name}.
     */
    String name();

    /** Class of the payload, used by the runner to deserialise from JSON. */
    Class<P> payloadType();

    /**
     * Run the work. Throws on transient failure → the runner
     * increments {@code attempt_count} and retries.
     */
    void execute(P payload);
}
