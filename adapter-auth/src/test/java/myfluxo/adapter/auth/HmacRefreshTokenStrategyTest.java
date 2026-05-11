package myfluxo.adapter.auth;

import myfluxo.domain.auth.model.TokenHash;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacRefreshTokenStrategyTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] DIFFERENT_SECRET = "fedcba9876543210fedcba9876543210".getBytes();

    private final HmacRefreshTokenStrategy strategy = new HmacRefreshTokenStrategy(SECRET);

    @Test
    void generatePlaintext_producesUrlSafeBase64String() {
        String token = strategy.generatePlaintext();

        // 32 random bytes → base64url-no-padding → 43 chars
        assertThat(token).hasSize(43);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatePlaintext_producesUniqueTokens() {
        // Birthday paradox check — with 256 bits of entropy, 1000
        // tokens should never collide. If they do, our randomness is
        // broken.
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(strategy.generatePlaintext());
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hash_isDeterministic_sameInputSameOutput() {
        // The lookup invariant: same plaintext must always produce the
        // same hash, otherwise refresh-token lookup is broken.
        String token = strategy.generatePlaintext();

        TokenHash first = strategy.hash(token);
        TokenHash second = strategy.hash(token);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void hash_isKeyDependent_differentSecretsProduceDifferentHashes() {
        // If the HMAC secret leaks, all refresh tokens become forgeable
        // by an attacker. This test pins the contract that the secret
        // is actually used (not silently ignored, not constant-zero).
        var other = new HmacRefreshTokenStrategy(DIFFERENT_SECRET);
        String token = strategy.generatePlaintext();

        TokenHash ours = strategy.hash(token);
        TokenHash theirs = other.hash(token);

        assertThat(ours).isNotEqualTo(theirs);
    }

    @Test
    void hash_outputIsUrlSafeBase64NoPadding() {
        // 256-bit HMAC → 32 bytes → base64url-no-padding → 43 chars
        var hash = strategy.hash("any plaintext");

        assertThat(hash.encoded()).hasSize(43);
        assertThat(hash.encoded()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void hash_rejectsNull() {
        assertThatThrownBy(() -> strategy.hash(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ctor_rejectsShortSecret() {
        assertThatThrownBy(() -> new HmacRefreshTokenStrategy(new byte[16]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void ctor_acceptsExactly32ByteSecret() {
        new HmacRefreshTokenStrategy(new byte[32]);
        // No throw.
    }

    @Test
    void packagePrivateCtor_acceptsCustomRandom_forITs() {
        // Sanity: the test-constructor with a caller-supplied
        // SecureRandom compiles and produces valid output. Java's
        // SecureRandom semantics make it non-trivial to force fully
        // deterministic behaviour, so we don't pin specific bytes —
        // we just confirm the constructor path works.
        var withCustom = new HmacRefreshTokenStrategy(SECRET, new SecureRandom());
        String token = withCustom.generatePlaintext();

        assertThat(token).hasSize(43);
        assertThat(withCustom.hash(token)).isNotNull();
    }
}
