-- V1: initial schema for MyCheckList.
-- (Edited in place before any real database existed. From now on, schema changes go in V2, V3, ...)
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
    -- IANA zone, e.g. 'America/Vancouver'. Decides when a "day" ends for everyone on the list.
    time_zone   varchar(64)  NOT NULL DEFAULT 'UTC',
    -- Team streak: consecutive occurrences where every member with items finished them.
    team_current_streak      integer NOT NULL DEFAULT 0 CHECK (team_current_streak >= 0),
    team_longest_streak      integer NOT NULL DEFAULT 0 CHECK (team_longest_streak >= 0),
    team_last_completed_date date,
    -- Last time anyone opened the list or ticked an item. Repeating lists nobody has
    -- touched for app.schedule.auto-pause-after-days (default 14) get paused.
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    archived_at timestamptz  -- soft delete: non-null means archived
);
CREATE TRIGGER checklists_updated_at BEFORE UPDATE ON checklists
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- checklist_schedules: present only for repeating lists (no row = one-time list).
-- A subset of the calendar-standard repeat rule (RFC 5545 RRULE).
--   Daily:              frequency=DAILY,   repeat_interval=1
--   Every 3 days:       frequency=DAILY,   repeat_interval=3
--   Mon, Wed, Sat:      frequency=WEEKLY,  weekday_mask=Mon|Wed|Sat (1+4+32 = 37)
--   Every 2nd Friday:   frequency=WEEKLY,  repeat_interval=2, weekday_mask=Fri (16)
--   15th of the month:  frequency=MONTHLY, month_day=15
--   Last Friday:        frequency=MONTHLY, month_week=-1, month_weekday=5
-- weekday_mask bits: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.
-- Weekdays are ISO numbers: 1=Mon ... 7=Sun.
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_schedules (
    checklist_id    uuid        PRIMARY KEY REFERENCES checklists (id) ON DELETE CASCADE,
    frequency       varchar(10) NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    repeat_interval integer     NOT NULL DEFAULT 1 CHECK (repeat_interval BETWEEN 1 AND 365),
    weekday_mask    integer     NOT NULL DEFAULT 0 CHECK (weekday_mask BETWEEN 0 AND 127),
    month_day       integer     CHECK (month_day BETWEEN 1 AND 31),  -- 31 = last day in shorter months
    month_week      integer     CHECK (month_week IN (-1, 1, 2, 3, 4)),  -- -1 = last
    month_weekday   integer     CHECK (month_weekday BETWEEN 1 AND 7),
    starts_on       date        NOT NULL,
    ends_on         date,                    -- stop after this date, or...
    max_occurrences integer     CHECK (max_occurrences > 0),  -- ...after N occurrences
    -- Set when the list is auto-paused for inactivity: no new occurrences, and missed days
    -- while paused don't break streaks. Cleared when someone opens the list again.
    paused_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CHECK ((month_week IS NULL) = (month_weekday IS NULL)),
    -- Each frequency uses only its own fields:
    CHECK (frequency <> 'DAILY'   OR (weekday_mask = 0 AND month_day IS NULL AND month_week IS NULL)),
    CHECK (frequency <> 'WEEKLY'  OR (weekday_mask > 0 AND month_day IS NULL AND month_week IS NULL)),
    CHECK (frequency <> 'MONTHLY' OR (weekday_mask = 0 AND (month_day IS NULL) <> (month_week IS NULL)))
);
CREATE TRIGGER checklist_schedules_updated_at BEFORE UPDATE ON checklist_schedules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- checklist_members: who is in a checklist, and with what role.
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_members (
    checklist_id uuid        NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         varchar(10) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    joined_at    timestamptz NOT NULL DEFAULT now(),
    -- Cached totals, updated in the same transaction as each star_transactions row.
    -- star_transactions is the source of truth; these make reads fast.
    star_balance        integer NOT NULL DEFAULT 0 CHECK (star_balance >= 0),
    current_streak      integer NOT NULL DEFAULT 0 CHECK (current_streak >= 0),
    longest_streak      integer NOT NULL DEFAULT 0 CHECK (longest_streak >= 0),
    last_completed_date date,  -- occurrence_date of the last occurrence they finished
    -- Soft removal: set when they leave or are removed. The row is kept for star history,
    -- and a rejoin clears it. Removed members are treated like outsiders.
    removed_at          timestamptz,
    PRIMARY KEY (checklist_id, user_id)
);
-- At most one OWNER per checklist (among members who haven't been removed).
CREATE UNIQUE INDEX ux_checklist_members_one_owner
    ON checklist_members (checklist_id) WHERE role = 'OWNER' AND removed_at IS NULL;
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

-- ===========================================================================
-- TEMPLATE: what the list contains. Edits here apply to the NEXT occurrence;
-- an occurrence that already exists keeps its own copy.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- checklist_items: the template items.
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_items (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id uuid         NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    title        varchar(500) NOT NULL CHECK (length(btrim(title)) > 0),
    notes        text,
    position     integer      NOT NULL,  -- sort order; leave gaps (1000, 2000, ...) for cheap reordering
    created_by   uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (id, checklist_id)  -- target for the composite FK in item_assignees
);
CREATE INDEX ix_items_checklist_position ON checklist_items (checklist_id, position);
CREATE TRIGGER checklist_items_updated_at BEFORE UPDATE ON checklist_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- item_assignees: default assignees for a template item (copied into each occurrence).
-- checklist_id is stored so the database guarantees an assignee is a member of the
-- item's checklist; removing a member automatically unassigns them.
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
CREATE INDEX ix_item_assignees_user ON item_assignees (user_id, checklist_id);

-- ===========================================================================
-- OCCURRENCES: one copy of the list per day/week/... it's due.
-- A one-time list has exactly one occurrence (with no deadline).
-- Occurrences are created lazily: only when someone opens or ticks the list that day.
-- A due day with no occurrence row counts as missed (worked out from the schedule).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- checklist_occurrences
-- ---------------------------------------------------------------------------
CREATE TABLE checklist_occurrences (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id      uuid        NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    occurrence_date   date        NOT NULL,  -- the day it's for, in the list's time zone
    starts_at         timestamptz NOT NULL,
    due_at            timestamptz,           -- when it closes; null = one-time list with no deadline
    status            varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at         timestamptz,
    team_completed_at timestamptz,           -- when every member with items had finished
    -- List-wide totals, kept so history still shows "8/10 done" after compaction.
    item_count           integer NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    completed_item_count integer NOT NULL DEFAULT 0 CHECK (completed_item_count >= 0),
    -- Set when occurrence_items for this day were deleted to save space (after
    -- app.history.full-detail-days, default 90). Member results and stars are kept.
    compacted_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (checklist_id, occurrence_date),
    UNIQUE (id, checklist_id),
    CHECK (due_at IS NULL OR due_at > starts_at),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK (completed_item_count <= item_count),
    CHECK (compacted_at IS NULL OR status = 'CLOSED')
);
-- The nightly job: "open occurrences whose deadline has passed".
CREATE INDEX ix_occurrences_open_due ON checklist_occurrences (due_at) WHERE status = 'OPEN';
-- The compaction job: "closed occurrences older than 90 days that still have item detail".
CREATE INDEX ix_occurrences_to_compact ON checklist_occurrences (occurrence_date)
    WHERE status = 'CLOSED' AND compacted_at IS NULL;

-- ---------------------------------------------------------------------------
-- occurrence_items: the items as they were when the occurrence was created.
-- Items can only be ticked while their occurrence is OPEN (enforced by the API).
-- ---------------------------------------------------------------------------
CREATE TABLE occurrence_items (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    occurrence_id    uuid         NOT NULL,
    checklist_id     uuid         NOT NULL,
    template_item_id uuid         REFERENCES checklist_items (id) ON DELETE SET NULL,
    title            varchar(500) NOT NULL CHECK (length(btrim(title)) > 0),
    notes            text,
    position         integer      NOT NULL,
    -- "Whoever gets to it first": anyone allowed to edit the list can complete any item,
    -- and it counts for the item's assignees.
    completed_at     timestamptz,
    completed_by     uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,  -- optimistic locking (JPA @Version)
    FOREIGN KEY (occurrence_id, checklist_id)
        REFERENCES checklist_occurrences (id, checklist_id) ON DELETE CASCADE,
    CHECK (completed_by IS NULL OR completed_at IS NOT NULL),
    UNIQUE (id, checklist_id)
);
CREATE INDEX ix_occurrence_items_occurrence ON occurrence_items (occurrence_id, position);
CREATE TRIGGER occurrence_items_updated_at BEFORE UPDATE ON occurrence_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- occurrence_item_assignees: who each copied item is assigned to.
-- ---------------------------------------------------------------------------
CREATE TABLE occurrence_item_assignees (
    occurrence_item_id uuid NOT NULL,
    user_id            uuid NOT NULL,
    checklist_id       uuid NOT NULL,
    PRIMARY KEY (occurrence_item_id, user_id),
    FOREIGN KEY (occurrence_item_id, checklist_id)
        REFERENCES occurrence_items (id, checklist_id) ON DELETE CASCADE,
    FOREIGN KEY (checklist_id, user_id)
        REFERENCES checklist_members (checklist_id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_occurrence_item_assignees_user ON occurrence_item_assignees (user_id, checklist_id);

-- ---------------------------------------------------------------------------
-- occurrence_member_results: one row per person who had items in an occurrence.
-- No row = they had nothing that day, so their streak is skipped (not broken).
-- Updated instantly as items are ticked; settled when the occurrence closes.
-- ---------------------------------------------------------------------------
CREATE TABLE occurrence_member_results (
    occurrence_id      uuid        NOT NULL REFERENCES checklist_occurrences (id) ON DELETE CASCADE,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    checklist_id       uuid        NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    assigned_count     integer     NOT NULL CHECK (assigned_count > 0),
    completed_count    integer     NOT NULL DEFAULT 0,
    completed_at       timestamptz,          -- when their last item was done (null if not done)
    stars_awarded      integer     NOT NULL DEFAULT 0 CHECK (stars_awarded >= 0),
    team_bonus_awarded integer     NOT NULL DEFAULT 0 CHECK (team_bonus_awarded >= 0),
    streak_after       integer     NOT NULL DEFAULT 0 CHECK (streak_after >= 0),
    PRIMARY KEY (occurrence_id, user_id),
    CHECK (completed_count BETWEEN 0 AND assigned_count)
);
CREATE INDEX ix_member_results_user ON occurrence_member_results (user_id, checklist_id);

-- ===========================================================================
-- REWARDS AND STARS (repeating lists only)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- rewards: created by owners/editors; star_cost can change at any time.
-- Deleting a reward only sets deleted_at, so past redemptions keep their name.
-- ---------------------------------------------------------------------------
CREATE TABLE rewards (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id uuid         NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    title        varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description  text,
    star_cost    integer      NOT NULL CHECK (star_cost BETWEEN 1 AND 100000),
    is_active    boolean      NOT NULL DEFAULT true,
    created_by   uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    UNIQUE (id, checklist_id)
);
CREATE INDEX ix_rewards_checklist ON rewards (checklist_id) WHERE deleted_at IS NULL;
CREATE TRIGGER rewards_updated_at BEFORE UPDATE ON rewards
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- reward_redemptions: a member asks to spend stars; an owner/editor decides.
-- Stars are held (deducted) when requested and refunded if rejected/cancelled.
-- star_cost is copied at request time, so later price changes don't affect it.
-- ---------------------------------------------------------------------------
CREATE TABLE reward_redemptions (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    reward_id    uuid        NOT NULL,
    checklist_id uuid        NOT NULL,
    requested_by uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    star_cost    integer     NOT NULL CHECK (star_cost > 0),
    status       varchar(10) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    decided_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    decided_at   timestamptz,
    note         text,
    FOREIGN KEY (reward_id, checklist_id)
        REFERENCES rewards (id, checklist_id) ON DELETE CASCADE,
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
-- The approver's queue.
CREATE INDEX ix_redemptions_pending ON reward_redemptions (checklist_id) WHERE status = 'PENDING';
CREATE INDEX ix_redemptions_user ON reward_redemptions (requested_by, checklist_id);

-- ---------------------------------------------------------------------------
-- star_transactions: the ledger. Every star earned or spent is one row.
-- Balance = sum(amount) of rows that aren't voided.
-- An award is voided (not deleted) if an item is unticked before the day closes.
-- ---------------------------------------------------------------------------
CREATE TABLE star_transactions (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    checklist_id  uuid        NOT NULL REFERENCES checklists (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount        integer     NOT NULL CHECK (amount <> 0),
    type          varchar(20) NOT NULL CHECK (type IN (
                      'DAILY_COMPLETION', 'TEAM_BONUS', 'ADJUSTMENT', 'REDEMPTION', 'REDEMPTION_REFUND')),
    occurrence_id uuid        REFERENCES checklist_occurrences (id) ON DELETE CASCADE,
    redemption_id uuid        REFERENCES reward_redemptions (id) ON DELETE CASCADE,
    reason        text,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    voided_at     timestamptz,
    CHECK (type NOT IN ('DAILY_COMPLETION', 'TEAM_BONUS') OR (occurrence_id IS NOT NULL AND amount > 0)),
    CHECK (type <> 'ADJUSTMENT' OR (reason IS NOT NULL AND length(btrim(reason)) > 0 AND created_by IS NOT NULL)),
    CHECK (type <> 'REDEMPTION' OR (redemption_id IS NOT NULL AND amount < 0)),
    CHECK (type <> 'REDEMPTION_REFUND' OR (redemption_id IS NOT NULL AND amount > 0))
);
-- At most one live award of each kind per person per occurrence.
CREATE UNIQUE INDEX ux_star_award_once
    ON star_transactions (occurrence_id, user_id, type)
    WHERE type IN ('DAILY_COMPLETION', 'TEAM_BONUS') AND voided_at IS NULL;
-- A redemption is charged once and refunded at most once.
CREATE UNIQUE INDEX ux_star_redemption_once
    ON star_transactions (redemption_id, type)
    WHERE redemption_id IS NOT NULL;
-- "My star history on this list".
CREATE INDEX ix_star_tx_member ON star_transactions (checklist_id, user_id, created_at DESC);

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
