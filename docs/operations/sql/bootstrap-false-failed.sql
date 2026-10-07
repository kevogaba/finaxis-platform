-- False-FAILED initial-administrator bootstrap report (issue #163).
--
-- Before the fix, ANY invitee's failed Keycloak provisioning job could set the tenant's
-- bootstrap record to FAILED (and so overwrite a COMPLETED one), because the failure was written
-- by organisation id with no check that the failing user was the bootstrap administrator. This
-- lists the bootstrap records that are FAILED although the administrator's provisioning did
-- complete, by one of two signatures (column completion_evidence):
--   DISPATCH_SUCCEEDED: the administrator's own Keycloak provisioning dispatch SUCCEEDED. The
--     dispatch log is untouched by the bug, so it still tells the truth.
--   LATE_FAILURE_ACTIVE_MEMBERSHIP: the dispatch is FAILED, but the administrator's membership is
--     ACTIVE and a Keycloak identity is linked. The job links the identity and activates the
--     membership before it completes the bootstrap, so this is the record of a job whose last
--     step (writing the SUCCEEDED dispatch) failed after the bootstrap was COMPLETED: the failure
--     handler then marked the dispatch FAILED and, before the fix, the bootstrap FAILED too.
--
-- Read-only: nothing here writes, and no data is rewritten for you. See
-- docs/operations/bootstrap-false-failed-report.md for how to read a row and what to do.
--
-- Columns: organisation_code, organisation_id, admin_user_id, completion_evidence, attempts,
-- last_failure_code, bootstrap_updated_at, admin_dispatch_updated_at,
-- other_user_failed_dispatches and other_user_last_failed_at. A row with
-- other_user_failed_dispatches > 0 is a false failure with high confidence: another user of the
-- same organisation has a FAILED provisioning dispatch. A row with 0 is a candidate only (the
-- administrator's provisioning completed, but a later retry of the bootstrap itself can also fail
-- genuinely): review it. The count misses an invitee whose failed job later SUCCEEDED on a retry,
-- so for a 0 also check the audit_event rows with action 'user.keycloak_provisioning', outcome
-- FAILURE and another user's entity_id (the resource id) in that organisation.
-- A FAILED record whose administrator dispatch is FAILED without that membership and identity
-- evidence, or absent, is a genuine failure and is not listed. One whose dispatch is still
-- PENDING is not listed either, but it is not necessarily genuine: the job is in progress and
-- the administrator's later success completes the record, so re-check it later.

SELECT o.tenant_code AS organisation_code,
       b.organisation_id,
       b.user_id AS admin_user_id,
       CASE d.status
           WHEN 'SUCCEEDED' THEN 'DISPATCH_SUCCEEDED'
           ELSE 'LATE_FAILURE_ACTIVE_MEMBERSHIP'
       END AS completion_evidence,
       b.attempts,
       b.last_failure_code,
       b.updated_at AS bootstrap_updated_at,
       d.updated_at AS admin_dispatch_updated_at,
       other.failed_dispatches AS other_user_failed_dispatches,
       other.last_failed_at AS other_user_last_failed_at
FROM organisation_initial_administrator_bootstrap b
JOIN organisation o ON o.id = b.organisation_id
JOIN identity_dispatch_log d
  ON d.organisation_id = b.organisation_id
 AND d.user_id = b.user_id
 AND d.dispatch_type = 'KEYCLOAK_PROVISIONING'
 AND (
     d.status = 'SUCCEEDED'
     OR (
         d.status = 'FAILED'
         AND EXISTS (
             SELECT 1
             FROM user_organisation_membership m
             WHERE m.organisation_id = b.organisation_id
               AND m.user_id = b.user_id
               AND m.membership_status = 'ACTIVE'
         )
         AND EXISTS (
             SELECT 1
             FROM keycloak_identity_link k
             WHERE k.user_id = b.user_id
               AND k.unlinked_at IS NULL
         )
     )
 )
CROSS JOIN LATERAL (
    SELECT count(*) AS failed_dispatches,
           max(f.updated_at) AS last_failed_at
    FROM identity_dispatch_log f
    WHERE f.organisation_id = b.organisation_id
      AND f.user_id <> b.user_id
      AND f.dispatch_type = 'KEYCLOAK_PROVISIONING'
      AND f.status = 'FAILED'
) other
WHERE b.status = 'FAILED'
ORDER BY other.failed_dispatches DESC, o.tenant_code;
