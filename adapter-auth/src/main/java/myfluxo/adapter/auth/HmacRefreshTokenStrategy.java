package myfluxo.adapter.auth;

import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.model.TokenHash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Production-grade {@link RefreshTokenStrategy} backed by a CSPRNG and
 * HMAC-SHA256.
 *
 * <h2>Token generation</h2>
 * 32 random bytes (256 bits) from {@link SecureRandom}, base64url-encoded
 * without padding. Result is ~43 ASCII chars, fits cleanly in JSON
 * bodies, no padding ambiguity.
 *
 * <h2>Hashing</h2>
 * HMAC-SHA256 with a server-side secret. The secret never appears in
 * any database or log; it's loaded from environment at boot. If the
 * secret leaks, every refresh token issued under it must be invalidated
 * — refresh-token theft via a config leak is no different from any
 * other key-material compromise.
 *
 * <h2>Thread safety</h2>
 * {@link SecureRandom} is thread-safe. {@link Mac} is NOT thread-safe —
 * we instantiate a fresh {@code Mac} per hash. The throwaway cost is
 * negligible (microseconds) for the request frequencies refresh tokens
 * see.
 */
public final class HmacRefreshTokenStrategy implements RefreshTokenStrategy {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int TOKEN_BYTES = 32;  // 256 bits of entropy

    private final SecretKeySpec hmacKey;
    private final SecureRandom random;

    /** Production constructor — secret from env. */
    public HmacRefreshTokenStrategy(byte[] hmacSecret) {
        Objects.requireNonNull(hmacSecret, "hmacSecret");
        if (hmacSecret.length < 32) {
            throw new IllegalArgumentException(
                "HMAC secret must be >= 32 bytes (256 bits); got "
                + hmacSecret.length);
        }
        this.hmacKey = new SecretKeySpec(hmacSecret, HMAC_ALGORITHM);
        this.random = new SecureRandom();
    }

    /** Test constructor with explicit randomness — for deterministic IT. */
    HmacRefreshTokenStrategy(byte[] hmacSecret, SecureRandom random) {
        Objects.requireNonNull(hmacSecret, "hmacSecret");
        Objects.requireNonNull(random, "random");
        if (hmacSecret.length < 32) {
            throw new IllegalArgumentException(
                "HMAC secret must be >= 32 bytes (256 bits); got "
                + hmacSecret.length);
        }
        this.hmacKey = new SecretKeySpec(hmacSecret, HMAC_ALGORITHM);
        this.random = random;
    }

    @Override
    public String generatePlaintext() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public TokenHash hash(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] digest = mac.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return TokenHash.of(
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (java.security.GeneralSecurityException e) {
            // HMAC-SHA256 is required by every JDK — this can't happen
            // outside a profoundly misconfigured runtime.
            throw new IllegalStateException(
                "HMAC-SHA256 unavailable; JVM is broken?", e);
        }
    }
}
