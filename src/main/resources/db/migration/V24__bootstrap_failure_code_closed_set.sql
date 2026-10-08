-- ADR 0030 follow-up: organisation_initial_administrator_bootstrap.last_failure_code becomes a
-- CLOSED set of codes, and the free text already stored in it is destroyed.
--
-- THE LEAK. Until this release InitialAdministratorBootstrapFailureRecorder stored the failing
-- exception's own message, truncated to 100 characters, in last_failure_code, from a catch of any
-- Exception. That text can be SQL, identity-provider (Keycloak) output, or an email address.
-- GET /api/v1/tenant returns the column as bootstrap_failure_code to every member holding
-- tenant.view, and the platform tenant routes return it to platform operators, so a message from
-- the bootstrap flow reached readers who were never meant to see it. The documentation called the
-- field "safe"; it was only short.
--
-- THE CLOSED SET. last_failure_code now holds exactly one of
--
--     IDENTITY_PROVIDER_FAILED, CONFLICT, NOT_FOUND, INVALID_STATE, DATABASE_ERROR, UNEXPECTED
--
-- (InitialAdministratorBootstrapFailureCode, mapped from the exception's TYPE, never its text), or
-- NULL when the bootstrap has not failed. The raw failure goes to the application log at ERROR
-- with its stack trace, the organisation id and the code; a failed retry's audit row carries the
-- code and the exception class name (the Keycloak-job failure row, the class name only). No
-- response carries a message.
--
-- WHAT THIS FILE DOES, in order:
--   1. Rewrites every non-NULL last_failure_code outside the set to 'UNEXPECTED'. THIS IS ONE-WAY:
--      the raw text is destroyed on purpose, because keeping it anywhere in the database would
--      keep the leak, and the original exception is not recoverable from the column afterwards.
--      An operator who needs the cause of a past failure finds it in the application log from
--      the time it happened, or retries the bootstrap (POST /api/v1/platform/tenants/{id}/
--      bootstrap/retry), which clears the column and records a fresh, mapped code if it fails
--      again. A value already in the set is left exactly as it is; only last_failure_code is
--      written, and not even updated_at or row_version (the row's state did not change, only
--      what is shown of it).
--   2. Adds chk_bootstrap_failure_code, a declarative table CHECK restricting the column to the
--      set, with NULL allowed. It is a CHECK, not a trigger, so ADR 0024's admission standard for
--      triggers does not apply. A write of free text by any path (a repair script, a future code
--      path that forgets the enum) fails with SQLSTATE 23514 naming the constraint.
--
-- IDEMPOTENT. The rewrite is a no-op on a set member, and the constraint is dropped if present
-- before it is added, so running the file again changes nothing. Pre- and post-conditions are
-- asserted below. The column keeps its VARCHAR(100) type: a narrower type would add nothing the
-- CHECK does not already enforce, and V1 is frozen.
--
-- SUPERSEDES. V1's bare comment-free column. V1-V23 are frozen and untouched.

-- Precondition: the table and column exist as V1 created them.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'organisation_initial_administrator_bootstrap'
          AND column_name = 'last_failure_code'
    ) THEN
        RAISE EXCEPTION
            'organisation_initial_administrator_bootstrap.last_failure_code does not exist';
    END IF;
END $$;

-- 1. Destroy the free text. Every value outside the closed set becomes UNEXPECTED.
UPDATE organisation_initial_administrator_bootstrap
SET last_failure_code = 'UNEXPECTED'
WHERE last_failure_code IS NOT NULL
  AND last_failure_code NOT IN (
      'IDENTITY_PROVIDER_FAILED', 'CONFLICT', 'NOT_FOUND', 'INVALID_STATE', 'DATABASE_ERROR',
      'UNEXPECTED'
  );

-- 2. Hold the line in the database.
ALTER TABLE organisation_initial_administrator_bootstrap
    DROP CONSTRAINT IF EXISTS chk_bootstrap_failure_code;

ALTER TABLE organisation_initial_administrator_bootstrap
    ADD CONSTRAINT chk_bootstrap_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'IDENTITY_PROVIDER_FAILED', 'CONFLICT', 'NOT_FOUND', 'INVALID_STATE',
            'DATABASE_ERROR', 'UNEXPECTED'
        )
    );

COMMENT ON COLUMN organisation_initial_administrator_bootstrap.last_failure_code IS
    'Closed failure code of the last failed bootstrap attempt (see chk_bootstrap_failure_code), '
    'NULL when none failed. Never an exception message: the detail is in the application log. V24.';

-- Post-condition: no row holds text outside the set, and the constraint is present and validated,
-- so a mistake surfaces in every environment on deployment rather than only in CI.
DO $$
DECLARE
    stray INT;
BEGIN
    SELECT COUNT(*) INTO stray
    FROM organisation_initial_administrator_bootstrap
    WHERE last_failure_code IS NOT NULL
      AND last_failure_code NOT IN (
          'IDENTITY_PROVIDER_FAILED', 'CONFLICT', 'NOT_FOUND', 'INVALID_STATE', 'DATABASE_ERROR',
          'UNEXPECTED'
      );
    IF stray <> 0 THEN
        RAISE EXCEPTION 'V24 left % bootstrap rows with a free-text last_failure_code', stray;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_bootstrap_failure_code'
          AND conrelid = 'organisation_initial_administrator_bootstrap'::regclass
          AND convalidated
    ) THEN
        RAISE EXCEPTION 'chk_bootstrap_failure_code is missing or not validated';
    END IF;
END $$;
