package myfluxo.domain.auth;

import myfluxo.domain.auth.model.TokenHash;

/**
 * Generates and hashes refresh-token material.
 *
 * <h2>Implementation requirements (production-grade)</h2>
 * <ul>
 *     <li>{@link #generatePlaintext} must use a cryptographically secure
 *         random source ({@code SecureRandom}). 256 bits minimum.</li>
 *     <li>{@link #hash} must use a keyed deterministic function
 *         (HMAC-SHA256) so the same plaintext always hashes to the same
 *         value (for indexed lookup) but a DB dump alone doesn't reveal
 *         the function output for arbitrary inputs.</li>
 *     <li>The HMAC key lives in environment configuration, never in
 *         the database, never in code.</li>
 * </ul>
 */
public interface RefreshTokenStrategy {

    /**
     * Cryptographically random opaque token, URL-safe encoded. Issued
     * exactly once — given to the user, then thrown away on the server
     * side (only the hash is persisted).
     */
    String generatePlaintext();

    /**
     * Keyed deterministic hash of the plaintext. Same input always
     * produces the same hash (so we can look up by hash) but the key
     * is required to compute it (so a DB-only leak doesn't help).
     */
    TokenHash hash(String plaintext);
}
