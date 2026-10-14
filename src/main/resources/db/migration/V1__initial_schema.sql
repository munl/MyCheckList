-- V1: initial schema for MyCheckList.
-- Flyway runs this once, in order, on app startup. Never edit a migration that has
-- already run against a real database; add a new V2__..., V3__... file instead.

-- Keeps updated_at current even when rows are edited directly in SQL (e.g. by an admin).
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- users: one row per person, created on their first authenticated request.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid  varchar(128) NOT NULL UNIQUE,
    email         varchar(320) NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name  varchar(200),
    photo_url     text,
    -- App-wide role, separate from per-checklist roles. SUPER_ADMIN bypasses all
    -- checklist permission checks. Driven by the APP_SUPER_ADMIN_EMAILS setting.
    platform_role varchar(20)  NOT NULL DEFAULT 'USER'
                  CHECK (platform_role IN ('USER', 'SUPER_ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- checklists
-- ---------------------------------------------------------------------------
CREATE TABLE checklists (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    title       varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description text,
    created_by  uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    archived_at timestamptz  -- soft delete: non-null means archived
);
CREATE TRIGGER checklists_updated_at BEFORE UPDATE ON checklists
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- checklist_members: who is in a checklist, and with what role.
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_members (
    checklist_id uuid        NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         varchar(10) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    joined_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (checklist_id, user_id)
);
-- At most one OWNER per checklist.
CREATE UNIQUE INDEX ux_checklist_members_one_owner
    ON checklist_members (checklist_id) WHERE role = 'OWNER';
-- "Which checklists am I in?"
CREATE INDEX ix_checklist_members_user ON checklist_members (user_id);

-- ---------------------------------------------------------------------------
-- checklist_invitations: the in-app invite inbox (accept / decline).
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_invitations (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id    uuid         NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    invitee_email   varchar(320) NOT NULL CHECK (invitee_email = lower(invitee_email)),
    invitee_user_id uuid         REFERENCES users (id) ON DELETE SET NULL,
    invited_by      uuid         REFERENCES users (id) ON DELETE SET NULL,
    role            varchar(10)  NOT NULL CHECK (role IN ('EDITOR', 'VIEWER')),
    status          varchar(10)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'EXPIRED')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    responded_at    timestamptz,
    expires_at      timestamptz  NOT NULL DEFAULT now() + interval '30 days'
);
-- No duplicate pending invites for the same person on the same checklist.
CREATE UNIQUE INDEX ux_invitations_one_pending
    ON checklist_invitations (checklist_id, invitee_email) WHERE status = 'PENDING';
-- The invite inbox query: "pending invites for my email".
CREATE INDEX ix_invitations_inbox
    ON checklist_invitations (invitee_email) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- checklist_items
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_items (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id    uuid         NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    title           varchar(500) NOT NULL CHECK (length(btrim(title)) > 0),
    notes           text,
    position        integer      NOT NULL,  -- sort order; leave gaps (1000, 2000, ...) for cheap reordering
    due_at          timestamptz,
    -- V1 only supports ANY (first assignee to check it off completes it for everyone).
    -- A later migration can widen this to ('ANY', 'EACH').
    completion_mode varchar(10)  NOT NULL DEFAULT 'ANY' CHECK (completion_mode IN ('ANY')),
    completed_at    timestamptz,
    completed_by    uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_by      uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,  -- optimistic locking (JPA @Version)
    CHECK (completed_by IS NULL OR completed_at IS NOT NULL),
    UNIQUE (id, checklist_id)  -- target for the composite FK in item_assignees
);
CREATE INDEX ix_items_checklist_position ON checklist_items (checklist_id, position);
CREATE TRIGGER checklist_items_updated_at BEFORE UPDATE ON checklist_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- item_assignees: zero, one or many assignees per item.
-- checklist_id is stored here so the database itself guarantees an assignee is a
-- member of the item's checklist; removing a member automatically unassigns them.
-- ---------------------------------------------------------------------------
CREATE TABLE item_assignees (
    item_id      uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    checklist_id uuid        NOT NULL,
    assigned_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    assigned_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, user_id),
    FOREIGN KEY (item_id, checklist_id)
        REFERENCES checklist_items (id, checklist_id) ON DELETE CASCADE,
    FOREIGN KEY (checklist_id, user_id)
        REFERENCES checklist_members (checklist_id, user_id) ON DELETE CASCADE
);
-- "Show me my items" / "separate the list by user".
CREATE INDEX ix_item_assignees_user ON item_assignees (user_id, checklist_id);

-- ---------------------------------------------------------------------------
-- admin_audit_log: every change a super admin makes to data they don't own.
-- ---------------------------------------------------------------------------
CREATE TABLE admin_audit_log (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid         REFERENCES users (id) ON DELETE SET NULL,
    action        varchar(100) NOT NULL,  -- e.g. 'DELETE_CHECKLIST', 'UPDATE_ITEM'
    target_table  varchar(100) NOT NULL,
    target_id     uuid,
    details       text,                   -- free-form, e.g. JSON of what changed
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_audit_log_created ON admin_audit_log (created_at DESC);
