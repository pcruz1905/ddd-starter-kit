package myfluxo.adapter.persistence.jdbc.auth;

import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbiRefreshTokenRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TOMORROW = NOW.plusSeconds(86400);

    private Jdbi jdbi;
    private JdbiUserRepository userRepo;
    private JdbiRefreshTokenRepository repo;

    @BeforeEach
    void setUp() {
        jdbi = PostgresContainerSupport.jdbi();
        var uow = new JdbiUnitOfWork(jdbi);
        userRepo = new JdbiUserRepository(uow);
        repo = new JdbiRefreshTokenRepository(uow);
        jdbi.useHandle(h -> h.execute("DELETE FROM refresh_tokens"));
        jdbi.useHandle(h -> h.execute("DELETE FROM users"));
    }

    private UserId aFreshUser(String email) {
        var u = User.register(UserId.newId(), new Email(email), NOW);
        userRepo.save(u);
        return u.id();
    }

    @Test
    void issueAndFindByHash_roundTrips() {
        var userId = aFreshUser("alice@example.com");
        var hash = TokenHash.of("h-alice-1");
        var t = RefreshToken.issue(userId, hash, TOMORROW, NOW);
        repo.save(t);

        var found = repo.findByTokenHash(hash).orElseThrow();
        assertThat(found.id()).isEqualTo(t.id());
        assertThat(found.userId()).isEqualTo(userId);
        assertThat(found.familyId()).isEqualTo(t.familyId());
        assertThat(found.isActive(NOW)).isTrue();
        assertThat(found.revokedAt()).isEmpty();
        assertThat(found.replacedByTokenId()).isEmpty();
    }

    @Test
    void rotate_persistsBothRowsCorrectly() {
        var userId = aFreshUser("alice@example.com");
        var original = RefreshToken.issue(userId, TokenHash.of("h-1"), TOMORROW, NOW);
        repo.save(original);
        var later = NOW.plusSeconds(60);

        var successor = original.rotate(TokenHash.of("h-2"), later.plusSeconds(86400), later);
        // Save successor first — original's replaced_by_token_id FK
        // requires the successor row to exist.
        repo.save(successor);
        repo.save(original);

        var origAfter = repo.findById(original.id()).orElseThrow();
        assertThat(origAfter.isRotated()).isTrue();
        assertThat(origAfter.replacedByTokenId()).contains(successor.id());

        var newAfter = repo.findByTokenHash(TokenHash.of("h-2")).orElseThrow();
        assertThat(newAfter.familyId()).isEqualTo(original.familyId());
        assertThat(newAfter.isActive(later)).isTrue();
    }

    @Test
    void revokeFamily_marksEveryActiveTokenInTheFamily() {
        // Build a 3-deep rotation chain — all share a family.
        var userId = aFreshUser("alice@example.com");
        var t1 = RefreshToken.issue(userId, TokenHash.of("h-1"), TOMORROW, NOW);
        repo.save(t1);
        var t2 = t1.rotate(TokenHash.of("h-2"), TOMORROW, NOW.plusSeconds(10));
        repo.save(t2); repo.save(t1);
        var t3 = t2.rotate(TokenHash.of("h-3"), TOMORROW, NOW.plusSeconds(20));
        repo.save(t3); repo.save(t2);

        // Theft detected — revoke family.
        repo.revokeFamily(t1.familyId(), NOW.plusSeconds(30));

        assertThat(repo.findById(t1.id()).orElseThrow().revokedAt()).isPresent();
        assertThat(repo.findById(t2.id()).orElseThrow().revokedAt()).isPresent();
        // t3 was active until the family revoke; should now be revoked.
        assertThat(repo.findById(t3.id()).orElseThrow().revokedAt()).isPresent();
    }

    @Test
    void revokeFamily_doesNotTouchOtherFamilies() {
        var aliceId = aFreshUser("alice@example.com");
        var bobId = aFreshUser("bob@example.com");

        var aliceToken = RefreshToken.issue(aliceId, TokenHash.of("alice-h"), TOMORROW, NOW);
        var bobToken = RefreshToken.issue(bobId, TokenHash.of("bob-h"), TOMORROW, NOW);
        repo.save(aliceToken);
        repo.save(bobToken);

        repo.revokeFamily(aliceToken.familyId(), NOW.plusSeconds(60));

        assertThat(repo.findById(aliceToken.id()).orElseThrow().revokedAt()).isPresent();
        assertThat(repo.findById(bobToken.id()).orElseThrow().revokedAt())
            .as("Bob's family must NOT be affected by Alice's family revocation")
            .isEmpty();
    }

    @Test
    void revokeAllForUser_killsEverySessionForThatUser() {
        var aliceId = aFreshUser("alice@example.com");
        var bobId = aFreshUser("bob@example.com");

        // Alice: two active sessions (two devices).
        var aliceA = RefreshToken.issue(aliceId, TokenHash.of("alice-A"), TOMORROW, NOW);
        var aliceB = RefreshToken.issue(aliceId, TokenHash.of("alice-B"), TOMORROW, NOW);
        repo.save(aliceA);
        repo.save(aliceB);
        // Bob: one active session — should survive Alice's logout-all.
        var bobToken = RefreshToken.issue(bobId, TokenHash.of("bob-h"), TOMORROW, NOW);
        repo.save(bobToken);

        repo.revokeAllForUser(aliceId, NOW.plusSeconds(60));

        assertThat(repo.findById(aliceA.id()).orElseThrow().revokedAt()).isPresent();
        assertThat(repo.findById(aliceB.id()).orElseThrow().revokedAt()).isPresent();
        assertThat(repo.findById(bobToken.id()).orElseThrow().revokedAt())
            .as("Bob's session must not be touched")
            .isEmpty();
    }

    @Test
    void uniqueTokenHash_constraintEnforced() {
        // Two distinct tokens for the same user shouldn't share a hash —
        // the DB's UNIQUE constraint on token_hash catches that.
        var userId = aFreshUser("alice@example.com");
        repo.save(RefreshToken.issue(userId, TokenHash.of("same-hash"), TOMORROW, NOW));

        org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () -> repo.save(RefreshToken.issue(userId, TokenHash.of("same-hash"), TOMORROW, NOW))
        );
    }
}
