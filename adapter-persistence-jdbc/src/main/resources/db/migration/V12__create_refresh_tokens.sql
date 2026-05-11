-- Refresh tokens with family tracking for theft detection.
--
-- token_hash: HMAC-SHA256 of the plaintext token, NEVER the plaintext.
--   Unique index lets us look up by hash on every refresh call.
--
-- family_id: groups every token in a rotation chain. When we detect
--   reuse of a token that has already been rotated (replaced_by_token_id
--   is set), the entire family is revoked.
--
-- replaced_by_token_id: when a token is rotated, this points at its
--   successor. ON DELETE SET NULL: if the successor is somehow gone,
--   we lose the rotation linkage but the rest of the row survives.
--
-- revoked_at: NULL while active; timestamp once revoked (explicit logout
--   OR family revocation after theft detection OR ChangePassword).
--
-- See domain.auth.RefreshToken.

CREATE TABLE refresh_tokens (
    id                     UUID PRIMARY KEY,
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash             TEXT NOT NULL UNIQUE,
    family_id              UUID NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    replaced_by_token_id   UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    version                BIGINT NOT NULL DEFAULT 0
);

-- "Find every token in this family" — used on theft detection to
-- revoke the whole rotation chain in one statement.
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);

-- "Find every active token for this user" — used on ChangePassword
-- to revoke all sessions, and on listing-sessions admin paths.
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
