package myfluxo.adapter.persistence.jdbc.outbox;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRoundTripIT {

    private Jdbi jdbi;
    private JdbiUnitOfWork uow;
    private JdbiUserRepository repo;
    private JdbiOutboxDomainEventPublisher outbox;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        uow = new JdbiUnitOfWork(jdbi);
        repo = new JdbiUserRepository(uow);
        outbox = new JdbiOutboxDomainEventPublisher(uow);
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM entity_archive");
            h.execute("DELETE FROM outbox_events");
            h.execute("DELETE FROM users");
        });
    }

    @Test
    void publishingInUow_persistsAtomicallyWithAggregate() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var id = UserId.newId();

        Result<User, Object> result = uow.inTransaction(() -> {
            var user = User.register(id, new Email("alice@example.com"), now);
            repo.save(user);
            outbox.publishAll(user.pullEvents());
            return Result.ok(user);
        });

        assertThat(result.isOk()).isTrue();

        long pending = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM outbox_events WHERE dispatched = FALSE")
            .mapTo(Long.class).one());
        assertThat(pending)
            .as("event written in same transaction as the user — should be visible after commit")
            .isEqualTo(1L);

        String eventType = jdbi.withHandle(h -> h.createQuery(
                "SELECT event_type FROM outbox_events ORDER BY occurred_at LIMIT 1")
            .mapTo(String.class).one());
        assertThat(eventType).isEqualTo("myfluxo.domain.users.events.UserEvent$Registered");
    }

    @Test
    void rollback_alsoRollsBackTheEvent() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var id = UserId.newId();

        uow.inTransaction(() -> {
            var user = User.register(id, new Email("alice@example.com"), now);
            repo.save(user);
            outbox.publishAll(user.pullEvents());
            return Result.err("undo");
        });

        long users = jdbi.withHandle(h -> h.createQuery(
            "SELECT COUNT(*) FROM users").mapTo(Long.class).one());
        long events = jdbi.withHandle(h -> h.createQuery(
            "SELECT COUNT(*) FROM outbox_events").mapTo(Long.class).one());

        assertThat(users).isZero();
        assertThat(events)
            .as("if the aggregate rolled back, its events must roll back too")
            .isZero();
    }

    @Test
    void dispatcher_drainsPendingAndForwardsToSink() {
        var now = Instant.parse("2026-01-01T00:00:00Z");

        uow.inTransaction(() -> {
            var user = User.register(UserId.newId(), new Email("alice@example.com"), now);
            repo.save(user);
            outbox.publishAll(user.pullEvents());
            return Result.ok(user);
        });

        List<String> received = new ArrayList<>();
        var dispatcher = new JdbiOutboxDispatcher(jdbi,
            (eventType, payload) -> received.add(eventType));

        int dispatched = dispatcher.dispatchPending(10);

        assertThat(dispatched).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isEqualTo("myfluxo.domain.users.events.UserEvent$Registered");

        assertThat(dispatcher.dispatchPending(10)).isZero();
    }

    @Test
    void sinkFailure_keepsRowPendingForRetry() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        uow.inTransaction(() -> {
            var user = User.register(UserId.newId(), new Email("alice@example.com"), now);
            repo.save(user);
            outbox.publishAll(user.pullEvents());
            return Result.ok(user);
        });

        var bombDispatcher = new JdbiOutboxDispatcher(jdbi,
            (t, p) -> { throw new RuntimeException("downstream down"); });
        int dispatched = bombDispatcher.dispatchPending(10);
        assertThat(dispatched).isZero();

        long pending = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM outbox_events WHERE dispatched = FALSE")
            .mapTo(Long.class).one());
        assertThat(pending).isEqualTo(1L);

        List<String> seen = new ArrayList<>();
        var workingDispatcher = new JdbiOutboxDispatcher(jdbi, (t, p) -> seen.add(t));
        assertThat(workingDispatcher.dispatchPending(10)).isEqualTo(1);
        assertThat(seen).hasSize(1);
    }
}
