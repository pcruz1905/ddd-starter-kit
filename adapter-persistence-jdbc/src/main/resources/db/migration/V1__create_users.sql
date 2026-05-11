-- Users table. Status is flattened into columns rather than stored as
-- jsonb so the database (and any reporting / BI tool) can query it
-- without parsing JSON. The variant invariants are enforced by CHECK
-- constraints so a malformed status combo cannot be persisted.

CREATE TABLE users (
    id                          UUID         PRIMARY KEY,
    email                       VARCHAR(320) NOT NULL,
    status_type                 VARCHAR(20)  NOT NULL,
    status_since                TIMESTAMPTZ  NOT NULL,
    status_deactivation_reason  TEXT,
    created_at                  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT users_status_type_check
        CHECK (status_type IN ('PENDING', 'ACTIVE', 'DEACTIVATED')),

    -- deactivation_reason is set if and only if status is DEACTIVATED
    CONSTRAINT users_deactivation_reason_consistency
        CHECK (
            (status_type = 'DEACTIVATED' AND status_deactivation_reason IS NOT NULL)
            OR
            (status_type IN ('PENDING', 'ACTIVE') AND status_deactivation_reason IS NULL)
        )
);

-- Case-insensitive uniqueness for email. We don't trust users to
-- consistently lowercase before saving — the index enforces it.
CREATE UNIQUE INDEX users_email_lower_unique ON users (LOWER(email));
