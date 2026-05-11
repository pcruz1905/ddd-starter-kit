package myfluxo.adapter.persistence.jdbc.outbox;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.events.UserEvent;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the soft-delete-via-outbox pattern:
 *   1. Save a user (commits to {@code users}).
 *   2. In a single transaction: publish {@code UserEvent.Deleted}
 *      and {@code DELETE} the row.
 *   3. Outbox dispatcher drains; {@link EntityArchiveSink} writes the
 *      snapshot to {@code entity_archive}.
 *   4. {@code users} is empty; {@code entity_archive} has the snapshot
 *      indexed by (entity_type='User', entity_id=<userId>).
 */
class EntityArchiveSinkIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

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
    void archivedUser_landsInEntityArchiveAfterDispatch() {
        var userId = UserId.newId();

        uow.inTransaction(() -> {
            var user = User.register(userId, new Email("alice@example.com"), NOW);
            repo.save(user);
            return Result.ok(user);
        });

        // Archive: publish event + hard delete, atomically.
        uow.inTransaction(() -> {
            var user = repo.findById(userId).orElseThrow();
            outbox.publish(UserEvent.Deleted.from(user, NOW));
            repo.delete(userId);
            return Result.ok(userId);
        });

        assertThat(repo.findById(userId))
            .as("primary row should be gone")
            .isEmpty();

        // Dispatcher → archive sink → entity_archive row.
        var dispatcher = new JdbiOutboxDispatcher(jdbi, new EntityArchiveSink(jdbi));
        int dispatched = dispatcher.dispatchPending(10);
        assertThat(dispatched).isEqualTo(1);

        long archived = jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM entity_archive
                 WHERE entity_type = 'User' AND entity_id = :id
                """)
            .bind("id", userId.value())
            .mapTo(Long.class)
            .one());
        assertThat(archived).isEqualTo(1L);

        String archivedEmail = jdbi.withHandle(h -> h.createQuery("""
                SELECT payload->'email'->>'value'
                  FROM entity_archive
                 WHERE entity_type = 'User' AND entity_id = :id
                """)
            .bind("id", userId.value())
            .mapTo(String.class)
            .one());
        assertThat(archivedEmail).isEqualTo("alice@example.com");
    }

    @Test
    void nonArchiveEvent_passesThroughWithoutArchiving() {
        var userId = UserId.newId();

        uow.inTransaction(() -> {
            var user = User.register(userId, new Email("bob@example.com"), NOW);
            repo.save(user);
            outbox.publish(new UserEvent.Registered(user.id(), user.email(), NOW));
            return Result.ok(user);
        });

        var dispatcher = new JdbiOutboxDispatcher(jdbi, new EntityArchiveSink(jdbi));
        int dispatched = dispatcher.dispatchPending(10);
        assertThat(dispatched).isEqualTo(1);

        long archived = jdbi.withHandle(h -> h.createQuery(
            "SELECT COUNT(*) FROM entity_archive").mapTo(Long.class).one());
        assertThat(archived)
            .as("non-archive events should not be written to entity_archive")
            .isZero();
    }

    @Test
    void archiveSnapshot_supportsRecovery() {
        var userId = UserId.newId();

        uow.inTransaction(() -> {
            var user = User.register(userId, new Email("carol@example.com"), NOW);
            user.activate(NOW.plusSeconds(60));
            repo.save(user);
            return Result.ok(user);
        });

        uow.inTransaction(() -> {
            var user = repo.findById(userId).orElseThrow();
            outbox.publish(UserEvent.Deleted.from(user, NOW.plusSeconds(120)));
            repo.delete(userId);
            return Result.ok(userId);
        });

        var dispatcher = new JdbiOutboxDispatcher(jdbi, new EntityArchiveSink(jdbi));
        dispatcher.dispatchPending(10);

        // The snapshot fully describes the archived aggregate: enough to
        // rehydrate or to answer "what did this user look like at archive
        // time?".
        var snapshot = jdbi.withHandle(h -> h.createQuery("""
                SELECT payload::text FROM entity_archive
                 WHERE entity_type = 'User' AND entity_id = :id
                """)
            .bind("id", userId.value())
            .mapTo(String.class)
            .one());
        assertThat(snapshot)
            .contains("carol@example.com")
            .contains("\"version\"");
    }

    @SuppressWarnings("unused")
    private static UUID asUuid(String s) {
        return UUID.fromString(s);
    }
}
