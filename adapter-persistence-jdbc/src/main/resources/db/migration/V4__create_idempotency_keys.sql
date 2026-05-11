-- JDBC-backed idempotency cache, visible to every instance of the app.
-- A client retry hitting any instance returns the same cached response.
--
-- `payload` carries the serialized successful result (or error variant)
-- so the cached response is byte-identical across retries. `payload_class`
-- captures the deserialization target; the store re-hydrates by reading
-- both columns.
--
-- `expires_at` lets a periodic cleanup job drop stale rows. Tune the TTL
-- to match the longest sensible retry window in your client (typically
-- 24h is plenty for HTTP).
--
CREATE TABLE idempotency_keys (
    key_value       TEXT          PRIMARY KEY,
    payload         JSONB         NOT NULL,
    payload_class   TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    expires_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT idempotency_keys_expires_after_created CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_keys_expires_at_idx
    ON idempotency_keys (expires_at);
