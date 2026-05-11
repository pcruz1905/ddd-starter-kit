-- Audit log for the "soft delete via outbox" pattern.
--
-- Aggregates that genuinely need to be removed from their primary table
-- (GDPR erasure, expired sessions, abandoned drafts) emit a Deleted
-- domain event (e.g. `UserEvent$Deleted`) in the same transaction as the
-- DELETE. The outbox dispatcher's `EntityArchiveSink` then writes the
-- snapshot here.
--
-- Note on naming: the event is `Deleted` (the row was deleted from the
-- primary table), not `Archived`, because `Archived` is reserved for
-- domain status names (e.g. `ProductStatus.Archived` — a discontinued
-- but still-existing product). The table is `entity_archive` because
-- it archives those deleted snapshots — two distinct concerns.
--
-- Recovery, compliance, and forensics all read from this table. The
-- primary tables stay clean — no `deleted_at` columns, no per-query
-- `WHERE deleted_at IS NULL` filter foot-guns.
--
-- Layout:
--   `entity_type`  — short name of the aggregate, e.g. "User", "Order"
--   `entity_id`    — the aggregate's branded id (UUID v7)
--   `payload`      — full snapshot of the aggregate at archive time
--   `archived_at`  — server clock when the sink wrote the row
--
-- A composite index on (entity_type, entity_id) lets recovery look up
-- the archive snapshot for a given aggregate without scanning.

CREATE TABLE entity_archive (
    id              UUID         PRIMARY KEY,
    entity_type     TEXT         NOT NULL,
    entity_id       UUID         NOT NULL,
    payload         JSONB        NOT NULL,
    archived_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX entity_archive_lookup_idx
    ON entity_archive (entity_type, entity_id);
