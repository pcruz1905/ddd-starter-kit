package myfluxo.adapter.auth;

import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2PasswordHasherTest {

    private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

    @Test
    void hash_thenVerify_returnsTrue() {
        var password = Password.of("correct horse battery staple");
        PasswordHash hash = hasher.hash(password);

        assertThat(hasher.verify(password, hash)).isTrue();
    }

    @Test
    void hash_emitsArgon2idEncodedForm() {
        var hash = hasher.hash(Password.of("password123"));
        assertThat(hash.encoded())
            .startsWith("$argon2id$")
            .contains("m=" + Argon2PasswordHasher.MEMORY_KIB)
            .contains("t=" + Argon2PasswordHasher.ITERATIONS)
            .contains("p=" + Argon2PasswordHasher.PARALLELISM);
    }

    @Test
    void verify_returnsFalseForWrongPassword() {
        var hash = hasher.hash(Password.of("correctpassword"));
        assertThat(hasher.verify(Password.of("wrongpassword"), hash)).isFalse();
    }

    @Test
    void verify_returnsFalseForMalformedHash_doesNotThrow() {
        // Defense against information leakage via exception timing —
        // verify must return false for ANY input, never propagate the
        // parsing error to the caller.
        var malformed = PasswordHash.of("not-a-real-argon2-hash");
        assertThat(hasher.verify(Password.of("anything12345"), malformed)).isFalse();
    }

    @Test
    void hash_producesDifferentEncodedFormPerCall_dueToFreshSalt() {
        // Same password, two hashes — must produce different encoded
        // forms (different salts). If they were identical, an attacker
        // with one stolen hash could detect duplicate passwords across
        // accounts.
        var password = Password.of("samepassword123");
        var a = hasher.hash(password);
        var b = hasher.hash(password);

        assertThat(a.encoded()).isNotEqualTo(b.encoded());
        // Both still verify against the original password.
        assertThat(hasher.verify(password, a)).isTrue();
        assertThat(hasher.verify(password, b)).isTrue();
    }

    @Test
    void needsRehash_returnsFalseForCurrentParams() {
        var hash = hasher.hash(Password.of("password123"));
        assertThat(hasher.needsRehash(hash)).isFalse();
    }

    @Test
    void needsRehash_returnsTrueForWeakerParams() {
        // Simulate a hash produced with weaker params (older config).
        var weakHasher = new Argon2PasswordHasher(
            /* memory KiB */ 8192,   // below current 19456
            /* iterations */ 1,
            /* parallelism */ 1,
            /* hash length */ 32
        );
        var oldHash = weakHasher.hash(Password.of("password123"));

        assertThat(hasher.needsRehash(oldHash)).isTrue();
    }

    @Test
    void needsRehash_returnsTrueForUnparseableHash_conservative() {
        // If we can't parse the encoded form (legacy/corrupt data),
        // err on the side of "rehash" — we'd rather waste a hash than
        // keep accepting weak credentials.
        assertThat(hasher.needsRehash(PasswordHash.of("not-argon2"))).isTrue();
    }
}
