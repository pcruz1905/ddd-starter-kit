-- Add RBAC role to users. Existing rows default to MEMBER (the basic
-- authenticated-user role). Admin promotion happens via a dedicated
-- use case once we wire that flow.
--
-- The role is encoded as a stable TEXT name (ADMIN / MEMBER / VIEWER)
-- — matches the wire form in Role.name() / Role.fromName().
-- VARCHAR(32) leaves headroom for future role names without unbounded
-- input.

ALTER TABLE users
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'MEMBER'
        CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'));
