-- Issue #208: branch.approve is the one permission that approves a branch; branch.activate is
-- deprecated.
--
-- WHAT. Moves approval of a PENDING_APPROVAL branch from branch.activate to branch.approve, and
-- retires the old code. Four data changes, all in one DO block so they share one decision:
--
--   1. branch.approve is made ACTIVE;
--   2. every pre-existing branch.approve grant and override with no branch.activate counterpart
--      is DISCARDED (it conferred nothing);
--   3. approval authority is REBUILT by copying every grant and override of branch.activate, so
--      the two codes agree row for row;
--   4. branch.activate is marked DEPRECATED.
--
-- WHY. V2 seeded both codes ("Approve a branch." / "Activate an approved branch.") but the
-- application only ever checked branch.activate: it gated POST /branches/{id}/activate, the
-- platform checker route, and the checker's half of POST /branches/{id}/return, while
-- branch.approve was granted to TENANT_ADMIN (it is in the baseline bundle), BRANCH_MANAGER and
-- PLATFORM_SUPER_ADMIN and checked nowhere. The owner's rule is that approval needs an explicit
-- permission and that a permission replaced by another is deprecated. From this release the three
-- routes above, and the target-branch and platform checks in BranchProvisioningService.activate
-- and returnForChanges, require branch.approve and no longer accept branch.activate. The ADR 0028
-- platform-checker window and the creator/submitter rules are unchanged, and so are the audit
-- actions (branch.activate, branch.activate_as_platform_checker): an audit action names what
-- happened, not the permission. Reactivating a SUSPENDED branch keeps branch.reactivate, suspend
-- branch.suspend, close branch.close and a maker's withdrawal branch.create.
--
-- THE GUARANTEE (the part to read before operating this). Before this release a principal could
-- approve a branch exactly when branch.activate reached them, through a role grant or a direct
-- ALLOW and not cancelled by a direct DENY. branch.approve reached nobody, because nothing read
-- it: every role_permission row and every membership_permission override that already carried it
-- (ALLOW and DENY alike) was inert. So that nobody is widened (a role or user that merely held the
-- dead branch.approve) and nobody narrowed (a lone branch.approve DENY beside a branch.activate
-- grant), approval authority is not merged but REBUILT from branch.activate alone. When
-- branch.activate is ACTIVE at migration time, afterwards, for every organisation:
--
--   * A role holds branch.approve if and only if it held branch.activate. role_permission has no
--     scope column - a role's TENANT or BRANCH scope lives on user_role_assignment, which is
--     untouched - so a copy keeps exactly the audience the original had. The system roles of
--     every existing tenant are included.
--   * A membership has a branch.approve override if and only if it had a branch.activate
--     override, with the SAME effect, ALLOW or DENY. Where it already had a branch.approve
--     override of the other effect, the branch.activate one replaces it.
--   * Every pre-existing branch.approve row with no matching branch.activate row is deleted. That
--     is deleted data: an administrator who composed an approver role, or granted a user an
--     override, around the dead code did not give anyone approval power and now has not, and must
--     re-grant it knowingly (role.assign_permission on branch.approve). Rows for both codes are
--     left as they are, not deleted and re-created.
--   * The branch.activate rows are NOT touched: they stay in place, harmless once DEPRECATED.
--
-- Effective approval ability is therefore identical before and after, principal by principal.
-- Nothing else is promised, and nothing more is true: a principal that could not approve cannot
-- approve after, and one that could still can. To take approval away from a role, revoke
-- branch.approve from it (the role.remove_permission route); revoking branch.activate no longer
-- does anything, because nothing reads it. Roles created for a new organisation afterwards get
-- branch.approve and NOT branch.activate (OrganisationBootstrapDefaults, TENANT_ADMIN and
-- BRANCH_MANAGER); an owner who composes a new role chooses branch.approve independently.
--
-- BEFORE DEPLOYING (informational, writes nothing). These are the rows step 2 deletes, the dead
-- branch.approve rows that have no branch.activate counterpart. Review them if an operator ever
-- composed a role or override around branch.approve:
--
--   SELECT 'role' AS kind, rp.organisation_id, rp.role_id AS holder_id, NULL AS effect
--   FROM role_permission rp
--   JOIN permission a ON a.id = rp.permission_id AND a.permission_code = 'branch.approve'
--   WHERE NOT EXISTS (SELECT 1 FROM role_permission o
--                     JOIN permission d ON d.id = o.permission_id
--                                      AND d.permission_code = 'branch.activate'
--                     WHERE o.organisation_id = rp.organisation_id AND o.role_id = rp.role_id)
--   UNION ALL
--   SELECT 'membership', mp.organisation_id, mp.membership_id, mp.effect
--   FROM membership_permission mp
--   JOIN permission a ON a.id = mp.permission_id AND a.permission_code = 'branch.approve'
--   WHERE NOT EXISTS (SELECT 1 FROM membership_permission o
--                     JOIN permission d ON d.id = o.permission_id
--                                      AND d.permission_code = 'branch.activate'
--                     WHERE o.organisation_id = mp.organisation_id
--                       AND o.membership_id = mp.membership_id);
--
-- (Membership overrides whose branch.activate counterpart has the other effect are not listed:
-- they are overwritten, not discarded. The seeded holders - TENANT_ADMIN, BRANCH_MANAGER,
-- PLATFORM_SUPER_ADMIN - hold both codes, so on a stock installation the query returns nothing.)
--
-- DEPRECATION. Runtime resolution honours only ACTIVE permissions (IamRepositories filters
-- permission.status = 'ACTIVE' on both the role and the direct-override path), so a DEPRECATED
-- branch.activate confers nothing, wherever it is still held. Its role_permission and
-- membership_permission rows are deliberately left in place: deleting an owner's grants of the
-- code that is being retired is not this migration's business. The permission row itself stays,
-- because permission_code is referenced by history and by the audit registry; it is never reused.
--
-- READINESS COUPLING. Tenant reactivation requires the readiness check
-- (JooqOrganisationAccessStore.hasDefaultRolesAndPermissions) to pass. For each default role it
-- counts the role's ACTIVE permissions that are in the current bundle and requires the count to
-- equal the bundle's size: a superset test over the bundle's own codes, blind to any extra
-- permission a role holds. Two facts follow. First, the new-tenant seed (grantPermissions) skips
-- non-ACTIVE codes, so a bundle that still listed the DEPRECATED branch.activate could never be
-- satisfied again, and every new tenant's reactivation would fail forever; branch.activate
-- therefore leaves the bundle. Second, an existing tenant's roles hold branch.activate AND
-- branch.approve after this file, and the extra, deprecated rows are ignored, so reactivating a
-- suspended tenant keeps working with no change to the check and no row removed. The rebuild
-- above also guarantees the one thing the check does need: every role that held branch.activate
-- (which includes every seeded system role that held it) holds branch.approve.
--
-- HOW TO REVOKE. Approval is revoked per role with role.remove_permission on branch.approve, or
-- per member with a DENY override. Rolling the release back is a forward-only change, like any
-- other: set branch.activate back to ACTIVE and re-ship the old checks. V1-V20 are frozen.
--
-- STATUS (read with the guarantee, not instead of it). Only an ACTIVE branch.activate confers
-- anything, so the file acts only on what it confers, and the discard step belongs to the same
-- branch as the copy: discarding without being able to rebuild would delete authority.
--
--   * ACTIVE: branch.approve is set ACTIVE (it is ACTIVE from V2; this repairs an out-of-band
--     change), the dead branch.approve rows are discarded, the grants are rebuilt from
--     branch.activate, branch.activate becomes DEPRECATED.
--   * DISABLED: approval was switched off on purpose, and an ACTIVE branch.approve would turn it
--     back on for everyone who holds it. branch.approve becomes DISABLED too, nothing is copied
--     and nothing is discarded, and branch.activate stays DISABLED. The pre-existing branch.approve
--     rows therefore survive untouched, and roles holding only branch.activate were never copied.
--     If branch.approve is later re-enabled after this path, the stale branch.approve rows become
--     live and the activate-only roles hold nothing: an owner who wants approval live decides that
--     deliberately, row by row.
--   * DEPRECATED: branch.activate was retired before this file ever ran (an operator, or an earlier
--     run). The previous runtime resolved no approval through a deprecated code, so nobody could
--     approve, and an ACTIVE branch.approve would let every holder approve at once, reversing that
--     shutdown. Row equality cannot show that this file completed earlier, so when branch.approve
--     is ACTIVE the file RAISES whatever the rows say (see RE-RUNNING). When branch.approve is not
--     ACTIVE there is nothing to open and the file changes nothing.
--
-- RE-RUNNING. Flyway applies this file once. A DEPRECATED branch.activate with an ACTIVE
-- branch.approve is a state this file cannot trust: it is what a completed run leaves, and it is
-- also what an operator's pre-deployment shutdown of approval leaves, and the two are not
-- distinguishable from the rows (the stock roles hold both codes either way). Accepting it would
-- silently authorize every branch.approve holder at once where the old runtime authorized nobody.
-- So the file RAISES in that state, always, with a message saying what to do: if approval must
-- stay shut, set branch.approve to DISABLED by hand and let the migration resume; otherwise set
-- branch.activate back to ACTIVE and run it again. Never re-run it by hand on an installation where
-- it already completed: with branch.activate set back to ACTIVE it would (a) discard every
-- branch.approve row that has no branch.activate counterpart, including any an owner deliberately
-- granted after the release, and (b) re-copy branch.approve onto every role and override that still
-- carries a branch.activate row, including ones an owner later revoked branch.approve from, which
-- WIDENS access. A later change of approval authority is a role.assign_permission /
-- role.remove_permission on branch.approve, never a re-run. The migration test re-runs the file to
-- prove the refusal.
--
-- WRITE LOCK. The file reads the permission status and every branch.activate grant and then
-- writes the branch.approve ones, so a grant that changed in between would be copied stale: a
-- revoke committing after the read would leave a revoked approval authority in place, and a
-- concurrent insert or override change could be missed. During a rolling deploy instances of the
-- previous release can still run role-permission mutations. As its first statement, before
-- anything is read, the migration block therefore takes
--
--     LOCK TABLE role_permission, membership_permission IN SHARE ROW EXCLUSIVE MODE
--
-- which blocks every other transaction's INSERT, UPDATE and DELETE on both tables (and a second
-- such lock) but allows reads, so authorization checks keep resolving. Flyway runs the file in one
-- transaction, so the lock holds until the commit. A grant write from an old instance waits until
-- V21 commits and then applies to the already-rebuilt state; that only matters for the deprecated
-- branch.activate, which those instances no longer enforce (see ROLLING DEPLOY), and a write on
-- branch.approve itself is by definition made on the new rows. A writer that waits longer than its
-- own lock_timeout fails and is retried by its caller.
--
-- CACHE. The effective-permission cache is namespaced by schema version and cleared at start by the
-- mechanism introduced with V19, so the rows this file writes are never served from a stale entry.
--
-- ROLLING DEPLOY. While instances of the previous release still run, they check branch.activate,
-- which this file deprecates, so they refuse approvals (403) until they are replaced. That fails
-- safe: nobody approves who could not before, and nobody needs to act beyond completing the
-- rollout. Their grant writes wait for the WRITE LOCK above and apply once V21 has committed.
--
-- Preconditions assert only what this file depends on; the post-conditions check only what it is
-- responsible for, so an out-of-band permission or grant elsewhere never fails the deploy.
--
-- DATA ONLY. Reference data and grants: no table, column, constraint or index changes, and no new
-- permission row, so there is no identifier to allocate. It supersedes the documentation that
-- named branch.activate as the checker's permission (ADR 0028, ADR 0029,
-- docs/security/authorization-model.md, docs/api/foundation-api.md) and V19's remark that a
-- checker-only role "holding branch.activate" gains nothing from branch.update. V1-V20 are frozen
-- and untouched.

-- Preconditions: both codes exist exactly once, which is all this file relies on.
DO $$
DECLARE
    activate_codes INT;
    approve_codes  INT;
BEGIN
    SELECT COUNT(*) INTO activate_codes FROM permission WHERE permission_code = 'branch.activate';
    IF activate_codes <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 branch.activate permission to exist once but found %',
            activate_codes;
    END IF;

    SELECT COUNT(*) INTO approve_codes FROM permission WHERE permission_code = 'branch.approve';
    IF approve_codes <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 branch.approve permission to exist once but found %', approve_codes;
    END IF;
END $$;

-- The migration proper. One block, so the status read at the top decides every step below.
DO $$
DECLARE
    source_status TEXT;
    activate_roles INT;
    approve_roles  INT;
    activate_overrides INT;
    approve_overrides  INT;
BEGIN
    -- Serialize grant writes before anything is read (see WRITE LOCK in the header): the status
    -- below and every grant the rebuild copies come from one state that no other transaction can
    -- change until this one commits. Reads continue.
    LOCK TABLE role_permission, membership_permission IN SHARE ROW EXCLUSIVE MODE;

    SELECT status INTO source_status FROM permission WHERE permission_code = 'branch.activate';

    IF source_status = 'ACTIVE' THEN
        UPDATE permission
        SET status = 'ACTIVE', updated_at = NOW(), row_version = row_version + 1
        WHERE permission_code = 'branch.approve' AND status <> 'ACTIVE';

        -- Discard: branch.approve conferred nothing before this release, so a role grant with no
        -- branch.activate beside it must not become approval authority.
        DELETE FROM role_permission rp
        USING permission approver
        WHERE approver.id = rp.permission_id
          AND approver.permission_code = 'branch.approve'
          AND NOT EXISTS (
              SELECT 1
              FROM role_permission o
              JOIN permission activator ON activator.id = o.permission_id
                                       AND activator.permission_code = 'branch.activate'
              WHERE o.organisation_id = rp.organisation_id
                AND o.role_id = rp.role_id
          );

        -- Likewise a direct override, ALLOW or DENY: an ALLOW would widen, and a DENY would
        -- narrow a member whose role grants branch.activate. Overrides that do have a
        -- branch.activate counterpart are reconciled by the copy below.
        DELETE FROM membership_permission mp
        USING permission approver
        WHERE approver.id = mp.permission_id
          AND approver.permission_code = 'branch.approve'
          AND NOT EXISTS (
              SELECT 1
              FROM membership_permission o
              JOIN permission activator ON activator.id = o.permission_id
                                       AND activator.permission_code = 'branch.activate'
              WHERE o.organisation_id = mp.organisation_id
                AND o.membership_id = mp.membership_id
          );

        -- Rebuild: every role that holds branch.activate also holds branch.approve.
        -- granted_by and created_by stay NULL: this is the migration, not a person.
        INSERT INTO role_permission (
            organisation_id, role_id, permission_id, granted_at, created_at, updated_at
        )
        SELECT
            rp.organisation_id,
            rp.role_id,
            approver.id,
            NOW(),
            NOW(),
            NOW()
        FROM role_permission rp
        JOIN permission activator ON activator.id = rp.permission_id
                                 AND activator.permission_code = 'branch.activate'
        CROSS JOIN permission approver
        WHERE approver.permission_code = 'branch.approve'
        ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

        -- The same rule for direct membership overrides, carrying the effect across. A membership
        -- that kept a branch.approve override (because it also has one on branch.activate) takes
        -- the branch.activate effect, so the pair always agrees.
        INSERT INTO membership_permission (
            organisation_id, membership_id, permission_id, effect, granted_at, created_at,
            updated_at
        )
        SELECT
            mp.organisation_id,
            mp.membership_id,
            approver.id,
            mp.effect,
            NOW(),
            NOW(),
            NOW()
        FROM membership_permission mp
        JOIN permission activator ON activator.id = mp.permission_id
                                 AND activator.permission_code = 'branch.activate'
        CROSS JOIN permission approver
        WHERE approver.permission_code = 'branch.approve'
        ON CONFLICT ON CONSTRAINT uq_membership_permission DO UPDATE
        SET effect = EXCLUDED.effect,
            updated_at = NOW(),
            row_version = membership_permission.row_version + 1
        WHERE membership_permission.effect <> EXCLUDED.effect;

        UPDATE permission
        SET status = 'DEPRECATED', updated_at = NOW(), row_version = row_version + 1
        WHERE permission_code = 'branch.activate';

        -- Post-conditions for this branch: the migration enforces what the tests assert, so a
        -- mistake surfaces in every environment on deployment rather than only in CI. Together
        -- they say the branch.approve rows are exactly the branch.activate rows.
        IF (SELECT status FROM permission WHERE permission_code = 'branch.approve')
               <> 'ACTIVE' THEN
            RAISE EXCEPTION 'branch.approve is not ACTIVE after this migration';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM role_permission rp
            JOIN permission activator ON activator.id = rp.permission_id
                                     AND activator.permission_code = 'branch.activate'
            WHERE NOT EXISTS (
                SELECT 1
                FROM role_permission copy
                JOIN permission approver ON approver.id = copy.permission_id
                                        AND approver.permission_code = 'branch.approve'
                WHERE copy.organisation_id = rp.organisation_id
                  AND copy.role_id = rp.role_id
            )
        ) THEN
            RAISE EXCEPTION
                'a role holds branch.activate but not branch.approve after this migration';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM role_permission rp
            JOIN permission approver ON approver.id = rp.permission_id
                                    AND approver.permission_code = 'branch.approve'
            WHERE NOT EXISTS (
                SELECT 1
                FROM role_permission o
                JOIN permission activator ON activator.id = o.permission_id
                                         AND activator.permission_code = 'branch.activate'
                WHERE o.organisation_id = rp.organisation_id
                  AND o.role_id = rp.role_id
            )
        ) THEN
            RAISE EXCEPTION
                'a role holds branch.approve without branch.activate after this migration';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM membership_permission mp
            JOIN permission activator ON activator.id = mp.permission_id
                                     AND activator.permission_code = 'branch.activate'
            WHERE NOT EXISTS (
                SELECT 1
                FROM membership_permission copy
                JOIN permission approver ON approver.id = copy.permission_id
                                        AND approver.permission_code = 'branch.approve'
                WHERE copy.organisation_id = mp.organisation_id
                  AND copy.membership_id = mp.membership_id
                  AND copy.effect = mp.effect
            )
        ) THEN
            RAISE EXCEPTION
                'a branch.activate override was not copied to branch.approve by this migration';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM membership_permission mp
            JOIN permission approver ON approver.id = mp.permission_id
                                    AND approver.permission_code = 'branch.approve'
            WHERE NOT EXISTS (
                SELECT 1
                FROM membership_permission o
                JOIN permission activator ON activator.id = o.permission_id
                                         AND activator.permission_code = 'branch.activate'
                WHERE o.organisation_id = mp.organisation_id
                  AND o.membership_id = mp.membership_id
                  AND o.effect = mp.effect
            )
        ) THEN
            RAISE EXCEPTION
                'a branch.approve override has no matching branch.activate override left';
        END IF;

    ELSIF source_status = 'DISABLED' THEN
        UPDATE permission
        SET status = 'DISABLED', updated_at = NOW(), row_version = row_version + 1
        WHERE permission_code = 'branch.approve' AND status = 'ACTIVE';

    ELSIF source_status = 'DEPRECATED'
          AND (SELECT status FROM permission WHERE permission_code = 'branch.approve')
              = 'ACTIVE' THEN
        -- Retired before this file ran, with approval switched on: see RE-RUNNING. Row equality
        -- proves nothing about whether the file completed earlier, so this raises unconditionally.
        SELECT COUNT(*) INTO activate_roles
        FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
        WHERE p.permission_code = 'branch.activate';
        SELECT COUNT(*) INTO approve_roles
        FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
        WHERE p.permission_code = 'branch.approve';
        SELECT COUNT(*) INTO activate_overrides
        FROM membership_permission mp JOIN permission p ON p.id = mp.permission_id
        WHERE p.permission_code = 'branch.activate';
        SELECT COUNT(*) INTO approve_overrides
        FROM membership_permission mp JOIN permission p ON p.id = mp.permission_id
        WHERE p.permission_code = 'branch.approve';

        RAISE EXCEPTION
            'V21 must not run when branch.activate is already DEPRECATED and branch.approve is '
            'ACTIVE (role grants: % branch.activate, % branch.approve; overrides: % '
            'branch.activate, % branch.approve): the previous runtime authorized nobody through '
            'a deprecated code, so every branch.approve holder would be able to approve at once. '
            'If approval must stay shut, set branch.approve to DISABLED by hand and let the '
            'migration resume; otherwise set branch.activate back to ACTIVE and run it again. '
            'Do not re-run V21 by hand on an installation where it already completed.',
            activate_roles, approve_roles, activate_overrides, approve_overrides;
    END IF;
END $$;

-- Post-condition for every branch: whatever the file did, branch.activate no longer confers
-- anything. It is checked outside the block so the DEPRECATED and DISABLED paths are held to it
-- too.
DO $$
BEGIN
    IF (SELECT status FROM permission WHERE permission_code = 'branch.activate') = 'ACTIVE' THEN
        RAISE EXCEPTION 'branch.activate is still ACTIVE after this migration';
    END IF;
END $$;
