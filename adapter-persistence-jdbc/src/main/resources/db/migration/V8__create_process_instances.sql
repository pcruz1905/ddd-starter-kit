-- Long-running process instances (sagas / process managers).
--
-- One row per running workflow. The kernel has no opinion about the
-- shape of `state` — each concrete process type (CheckoutProcess,
-- RefundProcess, …) owns its own JSON shape and a corresponding
-- ProcessHandler that interprets it.
--
-- `correlation_key` is whatever the caller uses to find the instance
-- back (e.g. the order id for a checkout process). A partial unique
-- index on RUNNING instances enforces "at most one running process per
-- (type, key)" — a second start attempt for the same key while one is
-- already running is a client bug; reusing the key after completion is
-- allowed (e.g. retried checkout after refund).
--
-- `version` is optimistic-concurrency for the row itself: two
-- dispatchers handling the same instance concurrently race on
-- UPDATE ... WHERE version = N; only one wins.

CREATE TABLE process_instances (
    id              UUID         PRIMARY KEY,
    process_type    TEXT         NOT NULL,
    correlation_key TEXT         NOT NULL,
    status          TEXT         NOT NULL,
    state           JSONB        NOT NULL,
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT process_instances_status_valid
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT process_instances_version_non_negative
        CHECK (version >= 0)
);

-- One running instance per (type, correlation_key).
-- Completed / Failed instances stay around for audit but do not block
-- a fresh run with the same correlation_key.
CREATE UNIQUE INDEX process_instances_one_running_per_key
    ON process_instances (process_type, correlation_key)
    WHERE status = 'RUNNING';

-- Dispatcher queries RUNNING instances in chronological order.
CREATE INDEX process_instances_running_idx
    ON process_instances (process_type, updated_at)
    WHERE status = 'RUNNING';
