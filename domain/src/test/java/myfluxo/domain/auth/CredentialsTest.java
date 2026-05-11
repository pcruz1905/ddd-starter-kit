package myfluxo.domain.auth;

import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.users.model.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UserId USER = UserId.newId();
    private static final PasswordHash HASH_A = PasswordHash.of("$argon2id$...HASHA");
    private static final PasswordHash HASH_B = PasswordHash.of("$argon2id$...HASHB");

    @Test
    void create_setsHashAndTimestamps() {
        var c = Credentials.create(USER, HASH_A, NOW);

        assertThat(c.userId()).isEqualTo(USER);
        assertThat(c.passwordHash()).isEqualTo(HASH_A);
        assertThat(c.createdAt()).isEqualTo(NOW);
        assertThat(c.updatedAt()).isEqualTo(NOW);
        assertThat(c.isNew()).isTrue();
        assertThat(c.version()).isZero();
    }

    @Test
    void changePassword_updatesHashAndUpdatedAt() {
        var c = Credentials.create(USER, HASH_A, NOW);
        var later = NOW.plusSeconds(3600);

        c.changePassword(HASH_B, later);

        assertThat(c.passwordHash()).isEqualTo(HASH_B);
        assertThat(c.updatedAt()).isEqualTo(later);
        // createdAt stays — immutable history of when the row appeared.
        assertThat(c.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rehydrate_carriesLoadedVersion() {
        var later = NOW.plusSeconds(60);
        var c = Credentials.rehydrate(USER, HASH_A, NOW, later, 7L);

        assertThat(c.isNew()).isFalse();
        assertThat(c.version()).isEqualTo(7L);
        assertThat(c.updatedAt()).isEqualTo(later);
    }

    @Test
    void id_isTheUserId() {
        // 1:1 with User — same identifier.
        var c = Credentials.create(USER, HASH_A, NOW);
        assertThat(c.id()).isEqualTo(USER);
    }
}
