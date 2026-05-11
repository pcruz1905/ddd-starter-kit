package myfluxo.adapter.persistence.jdbc.job;

import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.kernel.job.Job;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbiJobRunnerIT {

    private Jdbi jdbi;

    record IncrementPayload(int by) {}
    record NoPayload() {}

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        jdbi.useHandle(h -> h.execute("DELETE FROM jobs"));
    }

    @Test
    void schedule_then_run_marksCompleted() {
        var counter = new AtomicInteger();
        var increment = new Job<IncrementPayload>() {
            @Override public String name() { return "increment"; }
            @Override public Class<IncrementPayload> payloadType() { return IncrementPayload.class; }
            @Override public void execute(IncrementPayload p) { counter.addAndGet(p.by()); }
        };

        var scheduler = new JdbiJobScheduler(jdbi, List.of(increment));
        var runner = new JdbiJobRunner(jdbi, List.of(increment));

        scheduler.schedule("increment", new IncrementPayload(5));
        assertThat(runner.runPending(10)).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(5);

        long completed = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM jobs WHERE status = 'COMPLETED'")
            .mapTo(Long.class).one());
        assertThat(completed).isEqualTo(1L);
    }

    @Test
    void scheduleAfter_doesNotFireBeforeDelayPasses() {
        var fired = new AtomicInteger();
        var laterJob = new Job<NoPayload>() {
            @Override public String name() { return "later"; }
            @Override public Class<NoPayload> payloadType() { return NoPayload.class; }
            @Override public void execute(NoPayload p) { fired.incrementAndGet(); }
        };
        var scheduler = new JdbiJobScheduler(jdbi, List.of(laterJob));
        var runner = new JdbiJobRunner(jdbi, List.of(laterJob));

        scheduler.scheduleAfter("later", new NoPayload(), Duration.ofHours(1));

        assertThat(runner.runPending(10)).isZero();
        assertThat(fired.get()).isZero();
    }

    @Test
    void exception_leavesRowPendingAndBumpsAttemptCount() {
        var attempts = new AtomicInteger();
        var bombJob = new Job<NoPayload>() {
            @Override public String name() { return "bomb"; }
            @Override public Class<NoPayload> payloadType() { return NoPayload.class; }
            @Override public void execute(NoPayload p) {
                attempts.incrementAndGet();
                throw new RuntimeException("kaboom");
            }
        };
        var scheduler = new JdbiJobScheduler(jdbi, List.of(bombJob));
        var runner = new JdbiJobRunner(jdbi, List.of(bombJob));
        scheduler.schedule("bomb", new NoPayload());

        assertThat(runner.runPending(10)).isZero();
        assertThat(attempts.get()).isEqualTo(1);

        long pending = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM jobs WHERE status = 'PENDING' AND attempt_count = 1")
            .mapTo(Long.class).one());
        assertThat(pending).isEqualTo(1L);

        assertThat(runner.runPending(10)).isZero();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void scheduling_unknownName_throwsImmediately() {
        var scheduler = new JdbiJobScheduler(jdbi, List.of());
        assertThatThrownBy(() -> scheduler.schedule("not-registered", new NoPayload()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown job name");
    }

    @Test
    void runningWithoutAnyJobsRegistered_completesQuietly() {
        var runner = new JdbiJobRunner(jdbi, List.of());
        assertThat(runner.runPending(10)).isZero();
    }

    @Test
    void rowForUnregisteredName_isMarkedFailed_notLeftPending() {
        // Schedule via raw SQL to bypass the scheduler's validation —
        // simulates a job whose handler class was deleted/renamed.
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO jobs
                    (id, name, payload, status, attempt_count, run_after, enqueued_at)
                VALUES
                    (gen_random_uuid(), 'ghost', '{}'::jsonb, 'PENDING', 0,
                     now(), now())
                """)
            .execute());

        var runner = new JdbiJobRunner(jdbi, List.of());
        runner.runPending(10);

        var status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM jobs WHERE name = 'ghost'")
            .mapTo(String.class).one());
        assertThat(status)
            .as("a row with no registered handler should be marked FAILED, not retried forever")
            .isEqualTo("FAILED");
    }
}
