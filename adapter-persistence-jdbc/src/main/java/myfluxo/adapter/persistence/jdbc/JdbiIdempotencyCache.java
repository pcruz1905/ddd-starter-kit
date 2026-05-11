package myfluxo.adapter.persistence.jdbc;

import jakarta.inject.Singleton;
import myfluxo.adapter.http.idempotency.CachedResponse;
import myfluxo.adapter.http.idempotency.IdempotencyCache;
import myfluxo.kernel.idempotency.IdempotencyKey;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Postgres-backed implementation of the HTTP-layer
 * {@link IdempotencyCache}. Stores response bytes keyed by
 * {@code Idempotency-Key}; expired rows are not returned on lookup.
 *
 * <p>{@code ON CONFLICT DO NOTHING} on insert: if two retries race past
 * the find/miss check, the first writer wins and the second is a
 * silent no-op (its outcome is identical anyway).
 */
@Singleton
public final class JdbiIdempotencyCache implements IdempotencyCache {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final Jdbi jdbi;
    private final Duration ttl;

    public JdbiIdempotencyCache(Jdbi jdbi) {
        this(jdbi, DEFAULT_TTL);
    }

    JdbiIdempotencyCache(Jdbi jdbi, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.jdbi = jdbi;
        this.ttl = ttl;
    }

    @Override
    public Optional<CachedResponse> find(IdempotencyKey key) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT request_hash, status_code, response_body, content_type
                  FROM idempotency_cache
                 WHERE key = :key AND expires_at > :now
                """)
            .bind("key", key.value())
            .bind("now", Instant.now())
            .map((rs, ctx) -> new CachedResponse(
                rs.getString("request_hash"),
                rs.getInt("status_code"),
                rs.getBytes("response_body"),
                rs.getString("content_type")
            ))
            .findFirst());
    }

    @Override
    public void put(IdempotencyKey key, CachedResponse response) {
        var now = Instant.now();
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO idempotency_cache
                    (key, request_hash, status_code, response_body,
                     content_type, created_at, expires_at)
                VALUES
                    (:key, :requestHash, :statusCode, :responseBody,
                     :contentType, :createdAt, :expiresAt)
                ON CONFLICT (key) DO NOTHING
                """)
            .bind("key", key.value())
            .bind("requestHash", response.requestHash())
            .bind("statusCode", response.statusCode())
            .bind("responseBody", response.body())
            .bind("contentType", response.contentType())
            .bind("createdAt", now)
            .bind("expiresAt", now.plus(ttl))
            .execute());
    }
}
