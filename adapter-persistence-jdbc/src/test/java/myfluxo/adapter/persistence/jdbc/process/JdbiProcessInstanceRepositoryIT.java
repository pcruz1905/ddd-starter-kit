package myfluxo.adapter.persistence.jdbc.process;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import myfluxo.kernel.process.ProcessInstance;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class JdbiProcessInstanceRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PROCESS_TYPE = "CheckoutProcess";

    private Jdbi jdbi;
    private JdbiProcessInstanceRepository repo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        repo = new JdbiProcessInstanceRepository(new JdbiUnitOfWork(jdbi));
        jdbi.useHandle(h -> h.execute("DELETE FROM process_instances"));
    }

    @Test
    void save_roundTripsNewInstance() {
        var instance = ProcessInstance.start(
            PROCESS_TYPE, "order-123", "{\"step\":\"reserving\"}", NOW);
        repo.save(instance);

        var found = repo.findById(instance.id()).orElseThrow();
        assertThat(found.processType()).isEqualTo(PROCESS_TYPE);
        assertThat(found.correlationKey()).isEqualTo("order-123");
        assertThat(found.status()).isEqualTo(ProcessInstance.Status.RUNNING);
        assertThat(found.state()).contains("reserving");
        assertThat(found.version()).isEqualTo(1L);
    }

    @Test
    void advance_bumpsVersionAndPersistsNewState() {
        var initial = ProcessInstance.start(
            PROCESS_TYPE, "order-456", "{\"step\":\"reserving\"}", NOW);
        repo.save(initial);
        var loaded = repo.findById(initial.id()).orElseThrow();

        var advanced = loaded.advanced("{\"step\":\"charging\"}", NOW.plusSeconds(60));
        repo.save(advanced);

        var afterAdvance = repo.findById(initial.id()).orElseThrow();
        assertThat(afterAdvance.version()).isEqualTo(2L);
        assertThat(afterAdvance.state()).contains("charging");
        assertThat(afterAdvance.status()).isEqualTo(ProcessInstance.Status.RUNNING);
    }

    @Test
    void staleSave_throwsOptimisticConcurrency() {
        var initial = ProcessInstance.start(
            PROCESS_TYPE, "order-789", "{\"step\":\"reserving\"}", NOW);
        repo.save(initial);

        var loadedA = repo.findById(initial.id()).orElseThrow();
        var loadedB = repo.findById(initial.id()).orElseThrow();

        repo.save(loadedA.advanced("{\"step\":\"charging\"}", NOW.plusSeconds(60)));

        assertThatExceptionOfType(OptimisticConcurrencyException.class)
            .isThrownBy(() -> repo.save(
                loadedB.advanced("{\"step\":\"fulfilling\"}", NOW.plusSeconds(120))));
    }

    @Test
    void findRunningByCorrelationKey_returnsOnlyRunning() {
        var first = ProcessInstance.start(
            PROCESS_TYPE, "order-X", "{}", NOW);
        repo.save(first);
        var loaded = repo.findById(first.id()).orElseThrow();
        repo.save(loaded.completed("{\"done\":true}", NOW.plusSeconds(60)));

        // Completed run — should NOT be found as running.
        assertThat(repo.findRunningByCorrelationKey(PROCESS_TYPE, "order-X")).isEmpty();

        // A fresh run with the same correlation key is allowed once the
        // previous one terminated (partial unique index).
        var second = ProcessInstance.start(
            PROCESS_TYPE, "order-X", "{\"step\":\"reserving\"}", NOW.plusSeconds(120));
        repo.save(second);
        assertThat(repo.findRunningByCorrelationKey(PROCESS_TYPE, "order-X"))
            .isPresent();
    }

    @Test
    void findRunning_listsInOrderBoundedByLimit() {
        for (int i = 0; i < 5; i++) {
            repo.save(ProcessInstance.start(
                PROCESS_TYPE, "order-" + i, "{}", NOW.plusSeconds(i)));
        }
        var running = repo.findRunning(PROCESS_TYPE, 3);
        assertThat(running).hasSize(3);
        // Ordered by updated_at ascending (oldest first).
        assertThat(running.get(0).correlationKey()).isEqualTo("order-0");
    }

    @Test
    void terminalStates_doNotBlockNewRunningInstanceWithSameCorrelationKey() {
        var first = ProcessInstance.start(
            PROCESS_TYPE, "order-Y", "{}", NOW);
        repo.save(first);
        repo.save(repo.findById(first.id()).orElseThrow()
            .failed("{\"reason\":\"timeout\"}", NOW.plusSeconds(60)));

        // Failed → should be able to start a new instance with same key.
        var retried = ProcessInstance.start(
            PROCESS_TYPE, "order-Y", "{\"step\":\"reserving\"}", NOW.plusSeconds(120));
        repo.save(retried);
        assertThat(repo.findRunningByCorrelationKey(PROCESS_TYPE, "order-Y"))
            .isPresent();
    }
}
