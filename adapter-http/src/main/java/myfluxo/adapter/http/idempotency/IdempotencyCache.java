package myfluxo.adapter.http.idempotency;

import myfluxo.kernel.idempotency.IdempotencyKey;

import java.util.Optional;

/**
 * HTTP-layer idempotency cache: stores response bytes keyed by
 * {@code Idempotency-Key}.
 *
 * <p>This is the Stripe / IETF "Idempotency-Key" RFC pattern: cache the
 * <em>HTTP response</em>, not the typed use-case result. The middleware
 * uses this port; an adapter module provides the implementation
 * (Postgres-backed in production, anything else in tests).
 *
 * <p>Implementations must:
 * <ul>
 *     <li>Honour the TTL on stored entries — expired entries should not
 *         be returned.</li>
 *     <li>Be safe under concurrent reads/writes; first writer wins on
 *         simultaneous inserts.</li>
 * </ul>
 */
public interface IdempotencyCache {

    /** Latest cached response for this key, if any (and not expired). */
    Optional<CachedResponse> find(IdempotencyKey key);

    /**
     * Persist the response for this key. {@code requestHash} is captured
     * so the middleware can reject reuse of the same key with a different
     * request body (422 Unprocessable Entity).
     */
    void put(IdempotencyKey key, CachedResponse response);
}
