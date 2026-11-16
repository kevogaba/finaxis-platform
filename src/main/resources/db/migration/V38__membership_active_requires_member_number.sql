-- Issue #332: every ACTIVE or SUSPENDED membership has a member number (ADR 0040, #287).
--
-- WHAT. One declarative CHECK on user_organisation_membership, after a backfill of what V35 could
-- not number:
--
--   chk_membership_active_has_member_number
--       membership_status NOT IN ('ACTIVE', 'SUSPENDED') OR member_number IS NOT NULL
--
-- V35 added member_number and numbered every membership ever active, but deliberately added no
-- such CHECK: during the rolling deploy that carried it, instances of the previous release still
-- activated memberships without a number, and the CHECK would have failed those activations. This
-- is the later migration V35's header promises. A suspended membership can only have come from
-- ACTIVE, so it is held to the same rule (V35 already asserted both). A pending or revoked one may
-- have none: no number is burned by an invitation that is never approved.
--
-- BACKFILL. First, any membership that has ever been active and still has no number (one an old
-- instance activated after V35 ran) is numbered exactly as V35 numbered: in the order it first
-- became active (its earliest transition-log row into ACTIVE, else its joined_at, else its
-- created_at, then its id), after its organisation's highest number and after its counter. Each
-- MEMBER counter is then moved past the highest number its organisation holds. Only
-- member_number is written on a membership, not updated_at or row_version: the backfill records a
-- fact, it is not an edit. On a database V35 left complete, it numbers nothing.
--
-- DEPLOY. Ship only once no instance older than #287 runs. An older instance activates without a
-- number, and with this CHECK in place that activation fails with SQLSTATE 23514 (a 500) until the
-- instance is replaced: nothing goes unnumbered, but the caller sees an error.
--
-- The table is locked against writes while this runs (SHARE ROW EXCLUSIVE, the V21 and V35
-- precedent, with a bounded wait), so nothing is activated between the backfill and the CHECK.
-- Adding the CHECK validates every existing row. A declarative CHECK, not a trigger, so ADR 0024
-- does not apply. Idempotent (NULL-only backfill, guarded constraint), with its preconditions and
-- post-conditions asserted below.

SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_organisation_membership' AND column_name = 'member_number'
    ) THEN
        RAISE EXCEPTION 'V38: expected V35''s user_organisation_membership.member_number to exist';
    END IF;
    IF to_regclass('user_organisation_membership_transition_log') IS NULL
        OR to_regclass('reference_sequence') IS NULL THEN
        RAISE EXCEPTION 'V38: expected the V1 membership transition log and reference_sequence';
    END IF;
END $$;

LOCK TABLE user_organisation_membership IN SHARE ROW EXCLUSIVE MODE;

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
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_membership_active_has_member_number'
    ) THEN
        ALTER TABLE user_organisation_membership
            ADD CONSTRAINT chk_membership_active_has_member_number
                CHECK (
                    membership_status NOT IN ('ACTIVE', 'SUSPENDED')
                    OR member_number IS NOT NULL
                );
    END IF;
END $$;

COMMENT ON COLUMN user_organisation_membership.member_number IS
    'The membership''s number within its organisation, from the MEMBER reference_sequence at its '
    'first activation; required while ACTIVE or SUSPENDED (V38); kept through suspension, '
    'revocation and re-invite, never edited (#287).';

DO $$
DECLARE
    behind BIGINT;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_membership_active_has_member_number' AND convalidated
    ) THEN
        RAISE EXCEPTION 'V38: chk_membership_active_has_member_number is missing or not validated';
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
        RAISE EXCEPTION 'V38: % MEMBER counter(s) would hand out a number already taken', behind;
    END IF;
END $$;
