-- Transactional outbox. Events are persisted in the SAME transaction as
-- the aggregate save, so either both commit or both roll back — no event
-- can ever describe a write that didn't happen.
--
-- A separate dispatcher process polls this table, claims pending rows
-- atomically via FOR UPDATE SKIP LOCKED, forwards them to the real bus
-- (Kafka / webhook / email service / whatever), and marks them dispatched.

CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,
    event_type      TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    dispatched      BOOLEAN      NOT NULL DEFAULT FALSE,
    dispatched_at   TIMESTAMPTZ,
    attempt_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT outbox_events_attempt_non_negative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_events_dispatched_at_consistency
        CHECK ((dispatched IS TRUE) = (dispatched_at IS NOT NULL))
);

-- Pending events only — the dispatcher reads from here on every poll.
-- Partial index keeps it tiny even after years of dispatched rows.
CREATE INDEX outbox_events_pending_idx
    ON outbox_events (occurred_at)
    WHERE dispatched = FALSE;
