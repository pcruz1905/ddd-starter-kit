package myfluxo.adapter.persistence.jdbc.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import myfluxo.kernel.job.Job;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Postgres-backed job runner. Polls the {@code jobs} table for
 * pending work whose {@code run_after} has elapsed, dispatches to the
 * matching {@link Job}, and records the outcome.
 *
 * <p>Inspired by sellhub's background-jobs app — same routing-by-name
 * idea, Postgres queue + virtual threads instead of Cloudflare Queues.
 *
 * <p>Typed end-to-end: each {@code Job<P>} declares its payload class
 * via {@link Job#payloadType()}; the runner deserialises the persisted
 * JSON into {@code P} via Jackson before calling {@code job.execute(P)}.
 *
 * <p>Concurrency: {@code FOR UPDATE SKIP LOCKED} lets multiple runner
 * instances drain the queue without colliding. Each batch runs in its
 * own transaction; on job failure the row stays PENDING with
 * incremented {@code attempt_count}.
 */
public final class JdbiJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JdbiJobRunner.class);

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final Map<String, Job<?>> byName;

    public JdbiJobRunner(Jdbi jdbi, List<Job<?>> jobs) {
        this(jdbi, jobs, JsonMapper.create());
    }

    JdbiJobRunner(Jdbi jdbi, List<Job<?>> jobs, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.byName = jobs.stream().collect(
            Collectors.toUnmodifiableMap(Job::name, j -> j));
    }

    /**
     * Drain up to {@code batchSize} ready jobs. Returns the number
     * that completed successfully.
     */
    public int runPending(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        return jdbi.inTransaction(h -> {
            var rows = h.createQuery("""
                    SELECT id, name, payload::text AS payload, attempt_count
                      FROM jobs
                     WHERE status = 'PENDING' AND run_after <= :now
                     ORDER BY run_after
                     LIMIT :batch
                     FOR UPDATE SKIP LOCKED
                    """)
                .bind("now", Timestamp.from(Instant.now()))
                .bind("batch", batchSize)
                .map((rs, ctx) -> new Pending(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("payload"),
                    rs.getInt("attempt_count")
                ))
                .list();

            int completed = 0;
            for (var row : rows) {
                if (runOne(h, row)) completed++;
            }
            return completed;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean runOne(org.jdbi.v3.core.Handle h, Pending row) {
        // Mark RUNNING the moment we claim the row off the queue, before
        // any decision branch. That keeps `started_at` honest (it's when
        // we actually tried) and satisfies the `jobs_started_at_consistency`
        // constraint without backfill tricks.
        markRunning(h, row);

        var job = byName.get(row.name());
        if (job == null) {
            markFailed(h, row, "No job registered with name '" + row.name() + "'");
            return false;
        }

        try {
            Object payload = json.readValue(row.payload(), job.payloadType());
            ((Job) job).execute(payload);
            markCompleted(h, row);
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            markFailed(h, row, "Failed to parse payload as "
                + job.payloadType().getName() + ": " + ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            LOG.warn("Job {} (id={}) attempt {} threw {} — leaving PENDING for retry",
                row.name(), row.id(), row.attemptCount() + 1, ex.getClass().getSimpleName(), ex);
            h.createUpdate("""
                    UPDATE jobs
                       SET status = 'PENDING',
                           attempt_count = :attempt,
                           last_error = :err,
                           started_at = NULL
                     WHERE id = :id
                    """)
                .bind("attempt", row.attemptCount() + 1)
                .bind("err", truncate(ex.toString(), 4000))
                .bind("id", row.id())
                .execute();
            return false;
        }
    }

    private void markRunning(org.jdbi.v3.core.Handle h, Pending row) {
        h.createUpdate("""
                UPDATE jobs
                   SET status = 'RUNNING', started_at = :startedAt
                 WHERE id = :id
                """)
            .bind("startedAt", Timestamp.from(Instant.now()))
            .bind("id", row.id())
            .execute();
    }

    private void markCompleted(org.jdbi.v3.core.Handle h, Pending row) {
        h.createUpdate("""
                UPDATE jobs
                   SET status = 'COMPLETED',
                       attempt_count = :attempt,
                       completed_at = :completedAt,
                       last_error = NULL
                 WHERE id = :id
                """)
            .bind("attempt", row.attemptCount() + 1)
            .bind("completedAt", Timestamp.from(Instant.now()))
            .bind("id", row.id())
            .execute();
    }

    private void markFailed(org.jdbi.v3.core.Handle h, Pending row, String reason) {
        h.createUpdate("""
                UPDATE jobs
                   SET status = 'FAILED',
                       attempt_count = :attempt,
                       completed_at = :completedAt,
                       last_error = :err
                 WHERE id = :id
                """)
            .bind("attempt", row.attemptCount() + 1)
            .bind("completedAt", Timestamp.from(Instant.now()))
            .bind("err", truncate(reason, 4000))
            .bind("id", row.id())
            .execute();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private record Pending(UUID id, String name, String payload, int attemptCount) {}
}
