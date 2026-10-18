-- Retire legacy organisation_setting rows (#272): two the application no longer reads, and the
-- stored `false` of two switches it now enforces.
--
-- WHAT. Every CURRENT (effective_to IS NULL) row whose key is `base_currency` or
-- `business-date.timezone`, or whose key is a maker-checker switch
-- (`require_maker_checker_for_user_invites`, `require_maker_checker_for_branch_creation`) holding
-- `false`, is closed, the way the application closes any superseded setting: effective_to is set,
-- never a DELETE, so the history stays.
--
-- THE SWITCHES. Until this release both switches were in the catalogue with a default of `false`
-- and read by nothing, so any client that saved the value it displayed left a current `false`. The
-- release makes the switch real, and a `false` now means self-approval is allowed. Nobody chose
-- that: those rows were written while the setting did nothing, so they must not become an opt-out
-- the day it starts to. Closing them keeps segregation on for every tenant, as it was in effect;
-- a tenant that wants the opt-out sets it again, after the upgrade, which is audited HIGH. A `true`
-- row, or no row, is already required and is left alone.
--
-- WHY. `base_currency` is a read-only mirror of organisation.base_currency_code, which is the one
-- copy the ledger reads; the settings view derives it from the column and lifecycle refuses to
-- write it, so a stored row can only drift from the column. `business-date.timezone` was seeded
-- once, by V3, for the bootstrap tenant; nothing reads it, the organisation's timezone column is
-- the source. A current row for either is a second answer to a question with one owner.
--
-- HOW. effective_to is GREATEST(NOW(), effective_from + 1 second): chk_organisation_setting_range
-- requires effective_to > effective_from, and a row seeded with a future
-- effective_from must not make the statement fail. Only effective_to and updated_at are written.
-- Idempotent: a closed row no longer matches, so a re-run changes nothing.
--
-- Data only: no schema, no permission, no trigger (ADR 0024 does not apply).

DO $$
DECLARE
    still_current INT;
BEGIN
    UPDATE organisation_setting
    SET effective_to = GREATEST(NOW(), effective_from + INTERVAL '1 second'),
        updated_at = NOW()
    WHERE effective_to IS NULL
      AND (
          setting_key IN ('base_currency', 'business-date.timezone')
          OR (
              setting_key IN (
                  'require_maker_checker_for_user_invites',
                  'require_maker_checker_for_branch_creation'
              )
              AND setting_value #>> '{}' = 'false'
          )
      );

    SELECT COUNT(*) INTO still_current
    FROM organisation_setting
    WHERE effective_to IS NULL
      AND (
          setting_key IN ('base_currency', 'business-date.timezone')
          OR (
              setting_key IN (
                  'require_maker_checker_for_user_invites',
                  'require_maker_checker_for_branch_creation'
              )
              AND setting_value #>> '{}' = 'false'
          )
      );
    IF still_current <> 0 THEN
        RAISE EXCEPTION
            'V31 expected no current legacy or false maker-checker setting rows but found %',
            still_current;
    END IF;
END
$$;
