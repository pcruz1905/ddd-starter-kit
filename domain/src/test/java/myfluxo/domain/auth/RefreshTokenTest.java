package myfluxo.domain.auth;

import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TOMORROW = NOW.plusSeconds(86400);
    private static final UserId USER = UserId.newId();
    private static final TokenHash HASH_A = TokenHash.of("hash-A");
    private static final TokenHash HASH_B = TokenHash.of("hash-B");

    @Test
    void issue_startsActiveWithFreshFamily() {
        var t = RefreshToken.issue(USER, HASH_A, TOMORROW, NOW);

        assertThat(t.isActive(NOW)).isTrue();
        assertThat(t.isRotated()).isFalse();
        assertThat(t.revokedAt()).isEmpty();
        assertThat(t.replacedByTokenId()).isEmpty();
        assertThat(t.familyId()).isNotNull();
        assertThat(t.userId()).isEqualTo(USER);
        assertThat(t.tokenHash()).isEqualTo(HASH_A);
        assertThat(t.isNew()).isTrue();
    }

    @Test
    void rotate_revokesOriginal_andReturnsSuccessorInSameFamily() {
        var original = RefreshToken.issue(USER, HASH_A, TOMORROW, NOW);
        var later = NOW.plusSeconds(60);
        var newExpiry = later.plusSeconds(86400);

        var successor = original.rotate(HASH_B, newExpiry, later);

        // Original transitions to rotated state.
        assertThat(original.isActive(later)).isFalse();
        assertThat(original.isRotated()).isTrue();
        assertThat(original.revokedAt()).contains(later);
        assertThat(original.replacedByTokenId()).contains(successor.id());

        // Successor is freshly active, inherits family.
        assertThat(successor.isActive(later)).isTrue();
        assertThat(successor.familyId()).isEqualTo(original.familyId());
        assertThat(successor.tokenHash()).isEqualTo(HASH_B);
        assertThat(successor.id()).isNotEqualTo(original.id());
    }

    @Test
    void rotate_throwsIfAlreadyRevoked() {
        var t = RefreshToken.issue(USER, HASH_A, TOMORROW, NOW);
        t.revoke(NOW.plusSeconds(10));

        // Domain invariant: a revoked token can't be rotated. The use
        // case must check first and treat this as theft/reuse.
        assertThatThrownBy(() -> t.rotate(HASH_B, TOMORROW, NOW.plusSeconds(20)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("revoked");
    }

    @Test
    void revoke_isIdempotent() {
        var t = RefreshToken.issue(USER, HASH_A, TOMORROW, NOW);
        var firstRevoke = NOW.plusSeconds(10);
        var secondRevoke = NOW.plusSeconds(20);

        t.revoke(firstRevoke);
        t.revoke(secondRevoke);

        // Second revoke didn't overwrite — keeps the first revocation
        // time so the audit story is "when did we first decide this
        // token is dead".
        assertThat(t.revokedAt()).contains(firstRevoke);
    }

    @Test
    void isActive_falseWhenExpired() {
        var t = RefreshToken.issue(USER, HASH_A, NOW.plusSeconds(60), NOW);

        assertThat(t.isActive(NOW.plusSeconds(30))).isTrue();
        assertThat(t.isActive(NOW.plusSeconds(120))).isFalse();
    }

    @Test
    void isActive_falseAfterRevoke_evenIfNotExpired() {
        var t = RefreshToken.issue(USER, HASH_A, TOMORROW, NOW);
        t.revoke(NOW.plusSeconds(10));

        assertThat(t.isActive(NOW.plusSeconds(20))).isFalse();
    }

    @Test
    void rehydrate_restoresAllState() {
        var familyId = myfluxo.domain.auth.model.RefreshTokenFamilyId.newId();
        var id = myfluxo.domain.auth.model.RefreshTokenId.newId();

        var t = RefreshToken.rehydrate(
            id, USER, HASH_A, familyId,
            TOMORROW, NOW,
            null, null, // not revoked, not rotated
            3L
        );

        assertThat(t.id()).isEqualTo(id);
        assertThat(t.familyId()).isEqualTo(familyId);
        assertThat(t.version()).isEqualTo(3L);
        assertThat(t.isNew()).isFalse();
        assertThat(t.isActive(NOW.plusSeconds(60))).isTrue();
    }
}
