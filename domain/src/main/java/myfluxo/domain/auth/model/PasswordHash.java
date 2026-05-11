package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;

import java.util.Objects;

/**
 * Encoded password hash, opaque to the rest of the domain. The string
 * format includes the algorithm, parameters, salt, and hash —
 * self-describing, so re-hashing with new parameters can detect old
 * formats and upgrade in place.
 *
 * <p>Example Argon2id encoded form:
 * <pre>$argon2id$v=19$m=19456,t=2,p=1$&lt;salt-b64&gt;$&lt;hash-b64&gt;</pre>
 *
 * <p>The hash itself is non-secret in the sense that an attacker with a
 * DB dump still has to break Argon2 to recover passwords. It is still
 * sensitive — never logged, never returned in responses.
 *
 * <p>Equality is by encoded form. Two semantically-equivalent hashes
 * of the same password produce different encodings (different salt) and
 * therefore are unequal — exactly what we want, because comparing
 * hashes directly is never the right way to verify a password.
 */
public final class PasswordHash implements ValueObject {

    private final String encoded;

    private PasswordHash(String encoded) {
        this.encoded = encoded;
    }

    /**
     * Construct from the canonical encoded form. Validates that the
     * input is non-blank; format validation is delegated to the hasher
     * on verify.
     */
    public static PasswordHash of(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("encoded hash cannot be blank");
        }
        return new PasswordHash(encoded);
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public String toString() {
        // Don't dump the full hash in logs — its parameters reveal our
        // server-side config and the salt could aid targeted dictionary
        // attacks if combined with other leakage.
        return "PasswordHash{REDACTED}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PasswordHash h)) return false;
        return encoded.equals(h.encoded);
    }

    @Override
    public int hashCode() {
        return encoded.hashCode();
    }
}
