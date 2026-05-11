-- Background jobs queue. One row per scheduled unit of work.
--
-- Inspired by sellhub's async-channel + background-jobs pattern, but
-- backed by Postgres rather than Cloudflare Queues — same idea, same
-- typed-handler shape, different transport.
--
-- The runner polls for rows where:
--   * status = 'PENDING'
--   * run_after <= now()
-- using FOR UPDATE SKIP LOCKED, so multiple runner instances can poll
-- concurrently without stepping on each other.

CREATE TABLE jobs (
    id              UUID         PRIMARY KEY,
    name            TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    status          TEXT         NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    run_after       TIMESTAMPTZ  NOT NULL,
    last_error      TEXT,
    enqueued_at     TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    CONSTRAINT jobs_status_valid
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT jobs_attempt_non_negative CHECK (attempt_count >= 0),
    CONSTRAINT jobs_started_at_consistency
        CHECK ((status IN ('PENDING')) = (started_at IS NULL)),
    CONSTRAINT jobs_completed_at_consistency
        CHECK ((status IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL))
);

-- Runner's primary query: pending rows whose run_after is past.
CREATE INDEX jobs_pending_run_after_idx
    ON jobs (run_after)
    WHERE status = 'PENDING';

-- Operator queries by name (e.g., "how many failed daily-report jobs?").
CREATE INDEX jobs_name_status_idx
    ON jobs (name, status);
