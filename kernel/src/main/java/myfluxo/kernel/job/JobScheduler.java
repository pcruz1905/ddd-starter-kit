package myfluxo.kernel.job;

import java.time.Duration;
import java.time.Instant;

/**
 * Port for enqueuing background jobs. Application code calls this to
 * schedule work; the adapter persists it and a separate
 * {@code JdbiJobRunner} dispatches.
 *
 * <p>The {@code payload} is opaque {@code Object} at the kernel level —
 * the adapter serialises it to JSON via Jackson. Callers pass the
 * typed payload record matching the registered {@link Job}'s
 * {@code payloadType()}. The schedule call validates the name against
 * the registered job set so a typo fails immediately.
 *
 * <p>Three flavours:
 * <ul>
 *     <li>{@link #schedule} — run now (next poll).</li>
 *     <li>{@link #scheduleAfter} — run no earlier than {@code now + delay}
 *         (retry-after, deferred confirmations).</li>
 *     <li>{@link #scheduleAt} — run no earlier than the given instant
 *         (scheduled at a specific time).</li>
 * </ul>
 */
public interface JobScheduler {

    JobInstanceId schedule(String name, Object payload);

    JobInstanceId scheduleAfter(String name, Object payload, Duration delay);

    JobInstanceId scheduleAt(String name, Object payload, Instant at);
}
