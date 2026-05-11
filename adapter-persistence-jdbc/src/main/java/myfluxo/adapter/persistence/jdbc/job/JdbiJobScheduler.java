package myfluxo.adapter.persistence.jdbc.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import myfluxo.kernel.job.Job;
import myfluxo.kernel.job.JobInstanceId;
import myfluxo.kernel.job.JobScheduler;
import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Postgres-backed {@link JobScheduler}. Inserts one row into the
 * {@code jobs} table per enqueue.
 *
 * <p>Validates the job name at enqueue time against the registered
 * {@link Job} set — scheduling a typo gives an immediate error
 * instead of producing an unrunnable row.
 *
 * <p>The opaque {@code Object} payload from the kernel-level port is
 * serialised here via Jackson before the DB insert. The matching
 * {@code JdbiJobRunner} deserialises it back into the {@code Job}'s
 * declared payload type.
 */
@Singleton
public final class JdbiJobScheduler implements JobScheduler {

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final Set<String> registeredNames;

    public JdbiJobScheduler(Jdbi jdbi, List<Job<?>> jobs) {
        this(jdbi, jobs, JsonMapper.create());
    }

    JdbiJobScheduler(Jdbi jdbi, List<Job<?>> jobs, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.registeredNames = jobs.stream()
            .map(Job::name)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public JobInstanceId schedule(String name, Object payload) {
        return enqueue(name, payload, Instant.now());
    }

    @Override
    public JobInstanceId scheduleAfter(String name, Object payload, Duration delay) {
        if (delay == null) throw new NullPointerException("delay");
        return enqueue(name, payload, Instant.now().plus(delay));
    }

    @Override
    public JobInstanceId scheduleAt(String name, Object payload, Instant at) {
        if (at == null) throw new NullPointerException("at");
        return enqueue(name, payload, at);
    }

    private JobInstanceId enqueue(String name, Object payload, Instant runAfter) {
        if (!registeredNames.contains(name)) {
            throw new IllegalArgumentException(
                "Unknown job name: '" + name + "'. Registered jobs: " + registeredNames);
        }
        var id = JobInstanceId.newId();
        var now = Instant.now();
        var payloadStr = serialize(payload == null ? Map.of() : payload);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO jobs
                    (id, name, payload, status, attempt_count,
                     run_after, enqueued_at)
                VALUES
                    (:id, :name, CAST(:payload AS jsonb), 'PENDING', 0,
                     :runAfter, :enqueuedAt)
                """)
            .bind("id", id.value())
            .bind("name", name)
            .bind("payload", payloadStr)
            .bind("runAfter", Timestamp.from(runAfter))
            .bind("enqueuedAt", Timestamp.from(now))
            .execute());
        return id;
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize job payload", ex);
        }
    }
}
