package myfluxo.domain.auth;

import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;

/**
 * Port for hashing and verifying passwords. The concrete implementation
 * lives in {@code adapter-auth} (Argon2id via Password4j). The domain
 * layer references only this interface so the hashing algorithm is
 * swappable and testable.
 *
 * <h2>Implementations must</h2>
 * <ul>
 *     <li>use Argon2id (or stronger) with OWASP-recommended parameters
 *         (minimum 19 MiB memory, 2 iterations, 1 parallelism);</li>
 *     <li>generate a fresh per-password salt — never reuse;</li>
 *     <li>return a self-describing encoded form
 *         ({@code $argon2id$v=19$m=...$salt$hash}) so future re-hashing
 *         can detect old parameters;</li>
 *     <li>make {@link #verify} run in time that doesn't reveal whether
 *         the password matched (Password4j does this; ad-hoc string
 *         comparison does not).</li>
 * </ul>
 */
public interface PasswordHasher {

    /** Hash a plaintext password and return the encoded form. */
    PasswordHash hash(Password password);

    /**
     * Verify a plaintext password against a stored hash. Constant-time
     * within the algorithm's bounds; safe to call when the password is
     * wrong.
     *
     * <p>Returns {@code false} for any mismatch, malformed hash, or
     * algorithm mismatch — never throws on wrong input. Throwing would
     * leak information through exception timing.
     */
    boolean verify(Password password, PasswordHash hash);

    /**
     * Returns {@code true} if the given hash was produced with weaker
     * parameters than the current configuration recommends. Callers can
     * use this on successful login to opportunistically re-hash the
     * password with stronger params.
     */
    boolean needsRehash(PasswordHash hash);
}
