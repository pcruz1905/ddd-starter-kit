-- Optimistic concurrency: aggregate version on every row.
-- New rows start at 0; every successful UPDATE bumps it; the application
-- repository's UPDATE includes WHERE version = <loaded_version> so two
-- concurrent writers can't silently clobber each other.

ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Drop the default once the column is materialized — going forward the
-- application must explicitly write a version so we can spot bugs early.
ALTER TABLE users
    ALTER COLUMN version DROP DEFAULT;

ALTER TABLE users
    ADD CONSTRAINT users_version_non_negative CHECK (version >= 0);
