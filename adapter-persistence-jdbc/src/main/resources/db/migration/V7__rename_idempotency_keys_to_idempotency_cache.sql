-- Rename `idempotency_keys` → `idempotency_cache`.
--
-- The table's purpose changed in V6 from "use-case-level dedup keys
-- with typed payloads" to "HTTP response cache keyed by Idempotency-Key
-- header" (Stripe / IETF RFC pattern). The new name matches:
--   - `IdempotencyCache` port in adapter-http
--   - `JdbiIdempotencyCache` implementation
--   - what the table actually stores (response bytes, status, hash)

ALTER TABLE idempotency_keys RENAME TO idempotency_cache;

ALTER INDEX idempotency_keys_expires_at_idx
    RENAME TO idempotency_cache_expires_at_idx;

ALTER TABLE idempotency_cache
    RENAME CONSTRAINT idempotency_keys_status_valid
                   TO idempotency_cache_status_valid;

ALTER TABLE idempotency_cache
    RENAME CONSTRAINT idempotency_keys_expires_after_created
                   TO idempotency_cache_expires_after_created;
