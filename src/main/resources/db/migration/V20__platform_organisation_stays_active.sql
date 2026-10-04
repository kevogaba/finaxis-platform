-- Issue #205: the reserved PLATFORM organisation can never leave ACTIVE.
--
-- WHAT. One table CHECK on organisation, chk_organisation_platform_always_active:
--
--     id <> '00000000-0000-0000-0000-000000000000' OR status = 'ACTIVE'
--
-- Read it as "if this is the PLATFORM row, its status is ACTIVE". For every other row the first
-- disjunct is true and the constraint says nothing, so tenants keep the whole lifecycle V1's
-- chk_organisation_status allows. ADD CONSTRAINT validates the existing rows as it adds the
-- constraint (it is not NOT VALID); the precondition below makes a failure there a readable one.
--
-- WHY. The PLATFORM organisation (V2) is the identity every platform principal authenticates
-- against, and the home of PLATFORM_SUPER_ADMIN and every platform membership. Suspending it makes
-- the application refuse every platform principal; deprovisioning it also revokes every platform
-- membership (OrganisationProvisioningService.deprovision). Either leaves a platform nobody can
-- administer, and recovery is only by direct SQL. Until this release nothing stopped
-- POST /api/v1/platform/tenants/{platform id}/suspend or /deprovision from a platform
-- administrator: the lifecycle FSM allows ACTIVE -> SUSPENDED and ACTIVE -> DEPROVISIONING for any
-- organisation, and the one thing that happened to prevent it was an accident of the response
-- read-back. The line is therefore held in three layers, each correct without the others:
--
--   1. OrganisationProvisioningService refuses the platform organisation on every tenant-id
--      method (409, lifecycle.platform_organisation_protected), before it locks or reads;
--   2. the organisation transition graph (FoundationLifecycleDefinitions.organisationGraph) carries
--      a guard on every edge, so a future caller of FoundationLifecycleService is refused too;
--   3. this constraint, so a statement that bypasses the application altogether - a repair
--      script, a console session, an unreviewed code path - is refused by the database.
--
-- HOW IT FAILS. An UPDATE that would leave the PLATFORM row in any status but ACTIVE is rejected
-- with SQLSTATE 23514 (check_violation) naming the constraint; Spring translates that to
-- DataIntegrityViolationException. The statement fails, the row is untouched, and nothing else
-- is affected. A raw violation reaching the web layer is not the 409 above: that answer comes from
-- layers 1 and 2, which fire first. The constraint is the last line, not a user-facing contract.
--
-- WHAT IT DOES NOT DO.
--   * It constrains one row. It does not stop the PLATFORM row being edited in any other column
--     (display_name, status_reason, timestamps), and it does not make the row immutable.
--   * It does not stop the row being deleted. That is not reachable either: no code in this
--     repository deletes an organisation, and the tables that reference organisation(id) directly
--     (V1's branch, user_organisation_membership, role, organisation_setting, business_date,
--     organisation_transition_log, user_account_transition_log, audit_event, reference_sequence,
--     identity_dispatch_log and business_date_history, and the accounting tables of V6-V10 and
--     V14) do so with no cascade, so a DELETE of a row that has any such dependant is refused.
--     (Tables scoped to an organisation only through another table, such as role_permission,
--     user_branch_assignment or the branch and membership transition logs, are not direct
--     dependants and are not relied on.) V2 seeds the PLATFORM_SUPER_ADMIN and PLATFORM_SUPPORT
--     roles under the PLATFORM row, and those are such dependants. (The one cascade, on
--     organisation_initial_administrator_bootstrap, removes a dependent row, not the
--     organisation.) A CHECK is not evaluated on DELETE, so no constraint of this kind could close
--     that path; the foreign keys and the absence of any caller do.
--   * It is a declarative CHECK, not a trigger, so ADR 0024's admission standard for triggers does
--     not apply to it. A delete-guard trigger on organisation is not added either: it would fail
--     ADR 0024 condition one, since "this row is never deleted" is a single-row property that the
--     non-cascading foreign keys above already refuse.
--   * It is not a role or privilege boundary: the application connects as the table owner.
--
-- The UUID and the status literal are written out rather than looked up: a CHECK cannot read
-- another row, and the identifier is a stable, frozen V2 constant (PlatformOrganisation.ID).
--
-- SUPERSEDES NOTHING. It adds a constraint beside V1's chk_organisation_status and changes no
-- column, row or earlier file. V1-V19 are frozen and untouched.

-- Precondition: the PLATFORM row must exist and be ACTIVE, or the constraint would fail to add.
-- Said here, in words, instead of as a bare check_violation from ALTER TABLE.
DO $$
DECLARE
    platform_rows   INT;
    platform_status TEXT;
BEGIN
    SELECT COUNT(*), MAX(status) INTO platform_rows, platform_status
    FROM organisation
    WHERE id = '00000000-0000-0000-0000-000000000000';
    IF platform_rows <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 PLATFORM organisation to exist once but found %', platform_rows;
    END IF;
    IF platform_status <> 'ACTIVE' THEN
        RAISE EXCEPTION
            'The PLATFORM organisation must be ACTIVE before V20 can pin it, but it is %. '
            'Reactivate it (and its memberships) first.',
            platform_status;
    END IF;
END $$;

ALTER TABLE organisation
    ADD CONSTRAINT chk_organisation_platform_always_active CHECK (
        id <> '00000000-0000-0000-0000-000000000000' OR status = 'ACTIVE'
    );

COMMENT ON CONSTRAINT chk_organisation_platform_always_active ON organisation IS
    'The reserved PLATFORM organisation can never leave ACTIVE; see V20 and issue #205.';

-- Post-condition: the constraint is present and validated, so a mistake surfaces in every
-- environment on deployment rather than only in CI.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_organisation_platform_always_active'
          AND conrelid = 'organisation'::regclass
          AND convalidated
    ) THEN
        RAISE EXCEPTION 'chk_organisation_platform_always_active is missing or not validated';
    END IF;
END $$;
