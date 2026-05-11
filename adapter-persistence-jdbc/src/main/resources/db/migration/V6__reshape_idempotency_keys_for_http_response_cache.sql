-- Idempotency moves from the use-case layer to the HTTP middleware
-- (Stripe / IETF "Idempotency-Key" RFC pattern). The cache now stores
-- the HTTP response bytes keyed by Idempotency-Key, not a typed domain
-- result. This eliminates generic-erasure deserialization bugs and
-- removes idempotency boilerplate from every use case.
--
-- The previous table stored Jackson-serialized typed results
-- (`payload`/`payload_class`). That doesn't survive generic types like
-- `Result<User, UserError>`, and would have forced every use case to
-- maintain its own flat cache DTO.

DROP TABLE idempotency_keys;

CREATE TABLE idempotency_keys (
    key             TEXT         PRIMARY KEY,
    request_hash    TEXT         NOT NULL,   -- SHA-256 hex of request body
    status_code     INT          NOT NULL,
    response_body   BYTEA        NOT NULL,
    content_type    TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT idempotency_keys_status_valid
        CHECK (status_code >= 100 AND status_code < 600),
    CONSTRAINT idempotency_keys_expires_after_created
        CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_keys_expires_at_idx
    ON idempotency_keys (expires_at);
