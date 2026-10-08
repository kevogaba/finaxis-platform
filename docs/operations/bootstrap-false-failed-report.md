# False-FAILED bootstrap report

Issue #163. An organisation's initial-administrator bootstrap record
(`organisation_initial_administrator_bootstrap`) could be set to `FAILED` by a Keycloak
provisioning job for **any other user** of the organisation, and a `COMPLETED` record could be
flipped back. The platform tenant routes then showed `bootstrap_status = FAILED` for a healthy
tenant and allowed a pointless bootstrap retry.

## The rule now

- `KeycloakUserProvisioningJobRequestHandler` records a bootstrap failure only when the failing
  job's user **is** the bootstrap administrator (the same correlation the success path uses). An
  unrelated invitee's failure still fails that user's own dispatch and audit row, and nothing else.
- `JooqInitialAdministratorBootstrapStore.updateStatus` never writes `FAILED` over `COMPLETED`
  (`WHERE status <> 'COMPLETED'` for a `FAILED` target), so a failure after the administrator had
  already completed, for example the dispatch audit failing after the record committed, cannot
  undo the completion.

## Finding rows the bug already damaged

One read-only query, [`sql/bootstrap-false-failed.sql`](sql/bootstrap-false-failed.sql):

```shell
psql "$DATABASE_URL" --csv -f docs/operations/sql/bootstrap-false-failed.sql
```

It lists every `FAILED` bootstrap whose administrator's provisioning completed, by one of two
signatures, named in the `completion_evidence` column:

- `DISPATCH_SUCCEEDED`: the administrator's `KEYCLOAK_PROVISIONING` dispatch is `SUCCEEDED`. The
  dispatch log is not written by the bug, so it still holds the truth.
- `LATE_FAILURE_ACTIVE_MEMBERSHIP`: the dispatch is `FAILED`, but the administrator's membership
  is `ACTIVE` and a Keycloak identity is linked. The job links the identity and activates the
  membership before it completes the bootstrap, so this is a job whose last step (writing the
  `SUCCEEDED` dispatch) failed after the bootstrap was `COMPLETED`: the failure handler marked the
  dispatch `FAILED` and, before the fix, the bootstrap too.

| Column | Meaning |
| --- | --- |
| `organisation_code`, `organisation_id` | The tenant. |
| `completion_evidence` | Which signature above listed the row. |
| `admin_user_id`, `attempts`, `last_failure_code` | The bootstrap record. After `V24` an older free-text code reads `UNEXPECTED`, so it does not help tell a false failure apart. |
| `bootstrap_updated_at`, `admin_dispatch_updated_at` | When the record was last written, and when the administrator's dispatch last changed. |
| `other_user_failed_dispatches`, `other_user_last_failed_at` | Other users of the organisation whose provisioning dispatch is `FAILED`. **Greater than 0: a false failure, with high confidence.** `0`: a candidate only, because a later retry of the bootstrap itself can also fail genuinely. Review it. |

A `FAILED` record whose administrator dispatch is `FAILED` without an `ACTIVE` membership and a
linked identity, or that has no dispatch row, is not listed: the administrator's own provisioning
really did fail. One whose dispatch is still
`PENDING` is not listed either, but it is not necessarily genuine: the job is in progress or
retrying, and the administrator's later success completes the record, so re-check it later. An
empty result is the goal.

A row showing `0` in `other_user_failed_dispatches` may still be a false failure: the count only
sees invitees whose dispatch is `FAILED` **now**, and an invitee whose failed job later succeeded
on a retry no longer counts. Cross-check the audit log for the organisation: rows with
`action = 'user.keycloak_provisioning'` and `outcome = 'FAILURE'` whose `entity_id` (the
resource id) is another user's, not `admin_user_id`, show the other invitee failures that
happened.

## Repairing a listed row

**Nothing is rewritten automatically.** For a row you confirm is false, re-run the bootstrap
(`POST /api/v1/platform/tenants/{id}/bootstrap/retry`). It is self-healing: the user and
membership already exist, the dispatch is already `SUCCEEDED`, so the retry completes the record
again. The side effects are one extra `attempts` and a `tenant.bootstrap_retry` audit row. Do not
edit the table by hand.
