package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;

import java.util.Objects;

/**
 * Server-side stored form of a refresh token — the result of HMAC-SHA256
 * over the plaintext token, base64-url-encoded.
 *
 * <h2>Why not store plaintext, why not Argon2</h2>
 * <ul>
 *     <li><b>Not plaintext</b>: a DB dump would let an attacker
 *         impersonate every active session.</li>
 *     <li><b>Not Argon2</b>: refresh tokens are high-entropy random
 *         bytes (~256 bits), not user-chosen passwords. Memory-hard
 *         hashing offers no security benefit and would add ~50ms per
 *         refresh call. HMAC is deterministic (so we can index by
 *         hash) and uses a keyed function (the HMAC secret is required
 *         to compute the hash — so a DB dump alone doesn't help).</li>
 *     <li><b>Indexable</b>: deterministic hash lets us look up tokens
 *         by their HMAC value via a unique index. Argon2 (random salt
 *         per call) can't be indexed.</li>
 * </ul>
 *
 * <p>Equality is by encoded value. Used as the primary lookup key on
 * the {@code refresh_tokens} table.
 */
public final class TokenHash implements ValueObject {

    private final String encoded;

    private TokenHash(String encoded) {
        this.encoded = encoded;
    }

    public static TokenHash of(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("encoded token hash cannot be blank");
        }
        return new TokenHash(encoded);
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public String toString() {
        // Don't dump the raw hash in logs — it's the lookup key in the
        // refresh_tokens table. Leaking it could give DB-dump attackers
        // a usable handle if our HMAC secret also leaked.
        return "TokenHash{REDACTED}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TokenHash h)) return false;
        return encoded.equals(h.encoded);
    }

    @Override
    public int hashCode() {
        return encoded.hashCode();
    }
}
