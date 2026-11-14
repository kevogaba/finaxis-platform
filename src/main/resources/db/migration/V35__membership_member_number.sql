-- Issue #287: the member number (ADR 0040, superseding ADR 0033 decision 6).
--
-- WHAT. One nullable column and two constraints on user_organisation_membership, and a one-off
-- backfill:
--
--   member_number   BIGINT, the membership's number within its organisation, taken from that
--                   organisation's MEMBER reference_sequence counter (V1, seeded at approval and
--                   backfilled for every organisation by V7) the first time the membership
--                   becomes ACTIVE. It is kept for the life of the row: suspension, revocation
--                   and a re-invite (ADR 0037, which reuses the row) leave it alone, so a
--                   returning member keeps their number. It is never edited. NULL means the
--                   membership has never been active (a pending invitation, or one revoked
--                   before approval), which is why no number is burned by an invitation that is
--                   never approved.
--   chk_membership_member_number_positive   member_number > 0.
--   uq_membership_organisation_member_number UNIQUE (organisation_id, member_number): unique per
--                   organisation, so a person who belongs to two tenants has two numbers. A
--                   UNIQUE constraint admits any number of NULLs.
--
-- BACKFILL. Every membership that has ever been active gets a number, in the order it first
-- became active: its earliest transition-log row into ACTIVE, else its joined_at (the V3/V4 seeds
-- have no log row), else its created_at, then its id. "Ever active" is a status of ACTIVE or
-- SUSPENDED, a joined_at, or a log row into ACTIVE (a member revoked, or revoked and re-invited,
-- after being active). Numbering starts after the organisation's highest number and after its
-- counter, so a re-run numbers only what is still NULL and never repeats a number. Each MEMBER
-- counter is then moved past the highest number its organisation holds, and created where it is
-- missing. Only member_number is written on a membership, not updated_at or row_version: the
-- backfill records a fact, it is not an edit.
--
-- ROLLING DEPLOY. An instance of the previous release activates a membership without a number.
-- The table is locked against writes while this runs (SHARE ROW EXCLUSIVE, the V21 precedent, with
-- a bounded wait), so nothing is activated half-way through the backfill; a membership an old
-- instance activates after it gets its number the next time it becomes ACTIVE, and a later
-- migration adds the CHECK that an ACTIVE membership has a number once no old instance remains.
--
-- No trigger (ADR 0024 does not apply). Idempotent (IF NOT EXISTS, guarded constraints, NULL-only
-- backfill), with its preconditions and post-conditions asserted below.

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass('user_organisation_membership') IS NULL THEN
        RAISE EXCEPTION 'Expected the V1 user_organisation_membership table to exist';
    END IF;
    IF to_regclass('user_organisation_membership_transition_log') IS NULL THEN
        RAISE EXCEPTION 'Expected the V1 membership transition log to exist';
    END IF;
    IF to_regclass('reference_sequence') IS NULL THEN
        RAISE EXCEPTION 'Expected the V1 reference_sequence table to exist';
    END IF;
END $$;

LOCK TABLE user_organisation_membership IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE user_organisation_membership ADD COLUMN IF NOT EXISTS member_number BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_membership_member_number_positive'
    ) THEN
        ALTER TABLE user_organisation_membership
            ADD CONSTRAINT chk_membership_member_number_positive CHECK (member_number > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_membership_organisation_member_number'
    ) THEN
        ALTER TABLE user_organisation_membership
            ADD CONSTRAINT uq_membership_organisation_member_number
                UNIQUE (organisation_id, member_number);
    END IF;
END $$;

COMMENT ON COLUMN user_organisation_membership.member_number IS
    'The membership''s number within its organisation, from the MEMBER reference_sequence at its '
    'first activation; kept through suspension, revocation and re-invite, never edited (#287).';

WITH first_activation AS (
    SELECT log.organisation_id, log.entity_id, MIN(log.created_at) AS activated_at
    FROM user_organisation_membership_transition_log log
    WHERE log.status_to = 'ACTIVE'
    GROUP BY log.organisation_id, log.entity_id
),
ever_active AS (
    SELECT m.id,
           m.organisation_id,
           COALESCE(fa.activated_at, m.joined_at, m.created_at) AS activated_at
    FROM user_organisation_membership m
    LEFT JOIN first_activation fa
        ON fa.organisation_id = m.organisation_id AND fa.entity_id = m.id
    WHERE m.member_number IS NULL
      AND (
          m.membership_status IN ('ACTIVE', 'SUSPENDED')
          OR m.joined_at IS NOT NULL
          OR fa.entity_id IS NOT NULL
      )
),
floor_per_organisation AS (
    SELECT e.organisation_id,
           GREATEST(
               COALESCE((SELECT MAX(x.member_number)
                         FROM user_organisation_membership x
                         WHERE x.organisation_id = e.organisation_id), 0),
               COALESCE((SELECT s.next_value - 1
                         FROM reference_sequence s
                         WHERE s.organisation_id = e.organisation_id
                           AND s.sequence_code = 'MEMBER'), 0)
           ) AS taken
    FROM (SELECT DISTINCT organisation_id FROM ever_active) e
),
numbered AS (
    SELECT e.id,
           f.taken + ROW_NUMBER() OVER (
               PARTITION BY e.organisation_id ORDER BY e.activated_at, e.id
           ) AS member_number
    FROM ever_active e
    JOIN floor_per_organisation f ON f.organisation_id = e.organisation_id
)
UPDATE user_organisation_membership m
SET member_number = numbered.member_number
FROM numbered
WHERE m.id = numbered.id;

-- Move every MEMBER counter past the highest number its organisation now holds, creating the
-- counter where an organisation has numbered members and none (V7 backfilled every organisation
-- that existed then, and approval seeds one since, so this is a guard rather than a path).
INSERT INTO reference_sequence (organisation_id, sequence_code, next_value, created_at, updated_at)
SELECT m.organisation_id, 'MEMBER', MAX(m.member_number) + 1, NOW(), NOW()
FROM user_organisation_membership m
WHERE m.member_number IS NOT NULL
GROUP BY m.organisation_id
ON CONFLICT (organisation_id, sequence_code) DO UPDATE
SET next_value = GREATEST(reference_sequence.next_value, EXCLUDED.next_value),
    updated_at = CASE
        WHEN EXCLUDED.next_value > reference_sequence.next_value THEN NOW()
        ELSE reference_sequence.updated_at
    END;

DO $$
DECLARE
    unnumbered BIGINT;
    behind BIGINT;
BEGIN
    SELECT COUNT(*) INTO unnumbered
    FROM user_organisation_membership
    WHERE membership_status IN ('ACTIVE', 'SUSPENDED') AND member_number IS NULL;
    IF unnumbered > 0 THEN
        RAISE EXCEPTION 'V35: % active or suspended membership(s) still have no member number',
            unnumbered;
    END IF;

    SELECT COUNT(*) INTO behind
    FROM (
        SELECT m.organisation_id, MAX(m.member_number) AS highest
        FROM user_organisation_membership m
        WHERE m.member_number IS NOT NULL
        GROUP BY m.organisation_id
    ) numbered
    LEFT JOIN reference_sequence s
        ON s.organisation_id = numbered.organisation_id AND s.sequence_code = 'MEMBER'
    WHERE s.next_value IS NULL OR s.next_value <= numbered.highest;
    IF behind > 0 THEN
        RAISE EXCEPTION 'V35: % MEMBER counter(s) would hand out a number already taken', behind;
    END IF;
END $$;
