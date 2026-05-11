-- Credentials: password hash 1:1 with User. The aggregate's identity
-- is the UserId; the row's primary key references users(id) with
-- cascade-delete so hard-deleting a user also removes credentials.
--
-- See domain.auth.Credentials.

CREATE TABLE credentials (
    user_id        UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash  TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT credentials_password_hash_not_blank CHECK (length(password_hash) > 0)
);
