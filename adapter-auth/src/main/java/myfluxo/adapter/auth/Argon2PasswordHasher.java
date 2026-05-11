package myfluxo.adapter.auth;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import jakarta.inject.Singleton;
import myfluxo.domain.auth.PasswordHasher;
import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;

/**
 * Production-grade password hasher backed by Password4j's Argon2id.
 *
 * <h2>Parameters</h2>
 * OWASP's recommended minimum for Argon2id as of 2024-2025:
 * <ul>
 *     <li>memory:      19 MiB (19,456 KiB) — memory-hard cracking deterrent</li>
 *     <li>iterations:  2 — time cost</li>
 *     <li>parallelism: 1 — single execution thread per hash</li>
 *     <li>hash length: 32 bytes (256 bits)</li>
 * </ul>
 * Tune up for more security at the cost of per-login CPU/RAM.
 * (See {@code docs/AUTH.md} when it exists.)
 *
 * <h2>Verify is constant-time</h2>
 * Password4j's verify path always runs the full Argon2 derivation
 * regardless of result — no timing oracle on whether the password
 * matched.
 *
 * <h2>Rehash detection</h2>
 * Hashes encode their parameters in the canonical form. If the stored
 * hash was produced with weaker parameters than the current config,
 * {@link #needsRehash} returns {@code true} so callers can
 * opportunistically re-hash on successful login.
 */
@Singleton
public final class Argon2PasswordHasher implements PasswordHasher {

    static final int MEMORY_KIB = 19_456;   // 19 MiB
    static final int ITERATIONS = 2;
    static final int PARALLELISM = 1;
    static final int HASH_LENGTH = 32;       // bytes
    static final int SALT_LENGTH = 16;       // bytes — Password4j default; OWASP-aligned

    private final Argon2Function argon2;

    public Argon2PasswordHasher() {
        this(MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_LENGTH);
    }

    Argon2PasswordHasher(int memoryKiB, int iterations, int parallelism, int hashLength) {
        this.argon2 = Argon2Function.getInstance(
            memoryKiB,
            iterations,
            parallelism,
            hashLength,
            Argon2.ID
        );
    }

    @Override
    public PasswordHash hash(Password password) {
        // Password4j auto-generates a fresh per-password salt.
        String encoded = com.password4j.Password
            .hash(password.value())
            .addRandomSalt(SALT_LENGTH)
            .with(argon2)
            .getResult();
        return PasswordHash.of(encoded);
    }

    @Override
    public boolean verify(Password password, PasswordHash hash) {
        try {
            return com.password4j.Password
                .check(password.value(), hash.encoded())
                .with(extractFunctionFromHash(hash.encoded()));
        } catch (Exception ignored) {
            // Malformed hash, unknown algorithm, anything weird —
            // treat as "not verified". Never throw. Doing so would
            // leak information about the stored hash through exception
            // timing or error-rate analysis.
            return false;
        }
    }

    @Override
    public boolean needsRehash(PasswordHash hash) {
        Argon2Function stored;
        try {
            stored = extractFunctionFromHash(hash.encoded());
        } catch (Exception e) {
            // Can't parse → conservative answer: yes, rehash.
            return true;
        }
        return stored.getMemory() < argon2.getMemory()
            || stored.getIterations() < argon2.getIterations()
            || stored.getParallelism() < argon2.getParallelism();
    }

    private static Argon2Function extractFunctionFromHash(String encoded) {
        return Argon2Function.getInstanceFromHash(encoded);
    }
}
