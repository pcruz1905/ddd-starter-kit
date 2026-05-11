package myfluxo.adapter.persistence.jdbc.users;

import myfluxo.adapter.persistence.jdbc.JdbiEntityArchive;
import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.outbox.EntityArchiveSink;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDispatcher;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDomainEventPublisher;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.events.UserEvent;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the archive + restore pattern:
 *
 *   1. Save a user with a non-trivial status (Deactivated has both
 *      timestamp and reason — exercises sealed-status round-trip).
 *   2. Archive: emit {@code UserEvent.Deleted} + DELETE in one tx.
 *   3. Dispatcher drains the outbox → {@link EntityArchiveSink} writes
 *      the snapshot to {@code entity_archive}.
 *   4. {@link JdbiEntityArchive} reads the snapshot back.
 *   5. {@link UserRestorer} rehydrates a {@link User} aggregate.
 *   6. {@code userRepo.save(restored)} re-inserts the row.
 *   7. Verify the round-tripped user matches the original by id, email,
 *      and status variant (with all status fields preserved).
 */
class UserRecoveryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Jdbi jdbi;
    private JdbiUnitOfWork uow;
    private JdbiUserRepository repo;
    private JdbiOutboxDomainEventPublisher outbox;
    private JdbiEntityArchive archive;
    private UserRestorer restorer;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        uow = new JdbiUnitOfWork(jdbi);
        repo = new JdbiUserRepository(uow);
        outbox = new JdbiOutboxDomainEventPublisher(uow);
        archive = new JdbiEntityArchive(uow);
        restorer = new UserRestorer();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM entity_archive");
            h.execute("DELETE FROM outbox_events");
            h.execute("DELETE FROM users");
        });
    }

    @Test
    void archiveThenRestore_preservesUserStateIncludingSealedStatus() {
        var userId = UserId.newId();

        uow.inTransaction(() -> {
            var user = User.register(userId, new Email("alice@example.com"), NOW);
            user.deactivate(NOW.plusSeconds(60), "off-boarded");
            repo.save(user);
            return Result.ok(user);
        });

        // Archive the user (publish + delete atomically).
        uow.inTransaction(() -> {
            var user = repo.findById(userId).orElseThrow();
            outbox.publish(UserEvent.Deleted.from(user, NOW.plusSeconds(120)));
            repo.delete(userId);
            return Result.ok(userId);
        });

        // Drain outbox → archive row written.
        var dispatcher = new JdbiOutboxDispatcher(jdbi, new EntityArchiveSink(jdbi));
        assertThat(dispatcher.dispatchPending(10)).isEqualTo(1);
        assertThat(repo.findById(userId)).isEmpty();

        // Recover.
        var snapshot = archive.findLatest("User", userId.value()).orElseThrow();
        var restored = restorer.rehydrate(snapshot);

        assertThat(restored.id()).isEqualTo(userId);
        assertThat(restored.email().value()).isEqualTo("alice@example.com");
        assertThat(restored.status()).isInstanceOf(UserStatus.Deactivated.class);
        var status = (UserStatus.Deactivated) restored.status();
        assertThat(status.reason()).isEqualTo("off-boarded");
        assertThat(status.on()).isEqualTo(NOW.plusSeconds(60));

        // Restore: re-insert into the primary table.
        uow.inTransaction(() -> {
            repo.restore(restored);
            return Result.ok(restored);
        });
        var afterRestore = repo.findById(userId).orElseThrow();
        assertThat(afterRestore.email().value()).isEqualTo("alice@example.com");
        assertThat(afterRestore.status()).isInstanceOf(UserStatus.Deactivated.class);
    }

    @Test
    void findHistory_returnsAllSnapshotsNewestFirst() {
        var userId = UserId.newId();

        // First lifecycle: register, archive, dispatch.
        uow.inTransaction(() -> {
            var user = User.register(userId, new Email("bob@example.com"), NOW);
            repo.save(user);
            return Result.ok(user);
        });
        uow.inTransaction(() -> {
            var user = repo.findById(userId).orElseThrow();
            outbox.publish(UserEvent.Deleted.from(user, NOW.plusSeconds(60)));
            repo.delete(userId);
            return Result.ok(userId);
        });
        var dispatcher = new JdbiOutboxDispatcher(jdbi, new EntityArchiveSink(jdbi));
        dispatcher.dispatchPending(10);

        // Second lifecycle: restore, mutate, archive again.
        var snapshot1 = archive.findLatest("User", userId.value()).orElseThrow();
        var restored = restorer.rehydrate(snapshot1);
        uow.inTransaction(() -> {
            repo.restore(restored);
            return Result.ok(restored);
        });
        uow.inTransaction(() -> {
            var user = repo.findById(userId).orElseThrow();
            user.activate(NOW.plusSeconds(120));
            repo.save(user);
            outbox.publish(UserEvent.Deleted.from(user, NOW.plusSeconds(180)));
            repo.delete(userId);
            return Result.ok(userId);
        });
        dispatcher.dispatchPending(10);

        var history = archive.findHistory("User", userId.value());
        assertThat(history)
            .as("two archive cycles should leave two snapshots")
            .hasSize(2);
        assertThat(history.get(0).archivedAt())
            .as("history is newest-first")
            .isAfter(history.get(1).archivedAt());
    }

    @Test
    void findLatest_returnsEmptyWhenNoSnapshot() {
        assertThat(archive.findLatest("User", UserId.newId().value())).isEmpty();
    }

    @Test
    void rehydrate_rejectsSnapshotForDifferentEntityType() {
        var snapshot = new myfluxo.kernel.aggregate.ArchivedSnapshot(
            "Order", UserId.newId().value(), "{}", NOW);
        try {
            restorer.rehydrate(snapshot);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getMessage()).contains("entityType='User'");
        }
    }
}
