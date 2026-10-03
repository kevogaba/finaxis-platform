-- Issue #165 (parts 1 and 3): best-effort backfill of branch.opened_on and branch.closed_on for
-- branches that existed before the release that started writing them.
--
-- From that release on, JooqFoundationLifecyclePersistence.saveBranch stamps opened_on when a
-- branch first enters ACTIVE and closed_on when it enters CLOSED, from the organisation's business
-- date. It can only do so on a future transition. A branch that was already ACTIVE keeps a NULL
-- opened_on, and a branch that was already CLOSED can never be stamped at all - its only remaining
-- transition is to ARCHIVED - so the documented response contract would never hold for it. This
-- file closes that gap for rows the transition log can speak for.
--
-- THE DATES ARE APPROXIMATE, and are meant to be read that way. The source is the created_at of
-- the branch's earliest branch_transition_log row whose status_to is ACTIVE (opened_on) or CLOSED
-- (closed_on), converted to a calendar date in the ORGANISATION's timezone. That is the wall-clock
-- day the transition was recorded, not the business date at that moment: the business date is not
-- logged anywhere, and it can lag or lead the calendar. It is acceptable because there is no
-- better source, because for a tenant that closes its business day daily the business date tracks
-- the calendar, so the difference is at most a day or so, and because the contract is
-- best-effort for pre-release rows. Rows stamped by the application from this release on are exact.
--
-- Rules, each load-bearing:
--   * Only a column that is still NULL is written, so the file is idempotent and never overwrites
--     a date the application or V3 stamped (the V3 seeded branches already carry opened_on).
--   * The EARLIEST matching row wins. A SUSPEND/REACTIVATE cycle re-enters ACTIVE; the first
--     entry is the opening date, and later ones must not move it. A head office that tenant
--     approval activated is covered because it logs the same ACTIVATE transition.
--   * A branch with no matching log row keeps NULL. A date is never invented.
--   * The organisation timezone is organisation.timezone, not branch.timezone: it is the zone the
--     business date is defined in. A value that is not in the IANA zone list (pg_timezone_names)
--     resolves to UTC instead of raising, so one bad row cannot abort the upgrade. That includes
--     offset ids such as +03:00, UTC+03:00 or GMT+3, which are therefore dated in UTC and can be
--     a day off - one more reason the dates are approximate.
--   * chk_branch_dates (closed_on >= opened_on) must never fail the migration. Clock skew or a
--     zone edge can derive a closing date before the opening date, so the derived closed_on is
--     CLAMPED UP to opened_on (rather than skipped): the branch did close, and the opening day is
--     the closest consistent answer. Symmetrically, a derived opened_on later than an already
--     stored closed_on is clamped down to it.
--   * Nothing else changes: not updated_at, updated_by or row_version. This is a correction of
--     derived data, not an edit by an actor, so it must not make every upgraded branch look
--     recently modified or invalidate an optimistic-lock version a client is holding.
--
-- Forward-only, like every migration after V3. There is no down-migration; NULL is always a legal
-- value for both columns.

-- opened_on: the first transition into ACTIVE (ACTIVATE, or REACTIVATE if ACTIVATE was not logged)
WITH organisation_zone AS (
    SELECT o.id AS organisation_id,
           COALESCE(
               (SELECT z.name FROM pg_timezone_names z WHERE lower(z.name) = lower(o.timezone)
                LIMIT 1),
               'UTC'
           ) AS zone_name
    FROM organisation o
),
derived AS (
    SELECT first_entry.organisation_id,
           first_entry.branch_id,
           (first_entry.entered_at AT TIME ZONE tz.zone_name)::DATE AS entered_on
    FROM (
        SELECT l.organisation_id, l.branch_id, MIN(l.created_at) AS entered_at
        FROM branch_transition_log l
        WHERE l.status_to = 'ACTIVE'
        GROUP BY l.organisation_id, l.branch_id
    ) first_entry
    JOIN organisation_zone tz ON tz.organisation_id = first_entry.organisation_id
)
UPDATE branch b
SET opened_on = LEAST(derived.entered_on, COALESCE(b.closed_on, derived.entered_on))
FROM derived
WHERE b.organisation_id = derived.organisation_id
  AND b.id = derived.branch_id
  AND b.opened_on IS NULL;

-- closed_on: the first transition into CLOSED (CLOSE or CLOSE_SUSPENDED). Runs after opened_on so
-- the clamp sees the opening date this file just derived as well as one that was already stored.
WITH organisation_zone AS (
    SELECT o.id AS organisation_id,
           COALESCE(
               (SELECT z.name FROM pg_timezone_names z WHERE lower(z.name) = lower(o.timezone)
                LIMIT 1),
               'UTC'
           ) AS zone_name
    FROM organisation o
),
derived AS (
    SELECT first_entry.organisation_id,
           first_entry.branch_id,
           (first_entry.entered_at AT TIME ZONE tz.zone_name)::DATE AS entered_on
    FROM (
        SELECT l.organisation_id, l.branch_id, MIN(l.created_at) AS entered_at
        FROM branch_transition_log l
        WHERE l.status_to = 'CLOSED'
        GROUP BY l.organisation_id, l.branch_id
    ) first_entry
    JOIN organisation_zone tz ON tz.organisation_id = first_entry.organisation_id
)
UPDATE branch b
SET closed_on = GREATEST(derived.entered_on, b.opened_on)
FROM derived
WHERE b.organisation_id = derived.organisation_id
  AND b.id = derived.branch_id
  AND b.closed_on IS NULL;
