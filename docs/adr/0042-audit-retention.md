# ADR 0042: Audit Retention, The One Delete Path Of `audit_event`

## Status

Accepted, **implemented** in the release containing this change (issue #278, and #255's
exemptions).

Date: 2026-11-13

Amends [ADR 0007](0007-append-only-audit-log-and-redaction-policy.md) ("no code path issues
`UPDATE` or `DELETE`"). Builds on [ADR 0028](0028-platform-checker-for-first-tenant-approvals.md)
and #221 (the amender rules that read the audit trail) and ADR 0005 (which deferred retention
schedules until each had its own design).

## Context

`audit_retention_days` (INT, platform-only, unset by default) has been in the settings catalogue
since #164, writable by a platform administrator since #273, and nothing read it. #272 decided to
enforce it rather than drop it. The constraints:

- **Append-only by convention, not by privilege.** No migration grants, revokes or triggers anything
  on `audit_event`, and the application connects as the table owner, so a `DELETE` is technically
  allowed. The V1 table comment and ADR 0007 both say append-only.
- **Audit rows are a control input.** The branch and tenant amender rules (`hasAmended`,
  `hasAmendedOrganisation`) ask whether an actor has a successful `branch.update` or
  `organisation.amend_draft` row on the target. Deleting one lets its amender approve (#255).
- **`0` was a valid value** (`canonicalizeInt` refused only negatives), which would purge
  everything.
- **No retention period was on record.** The international anti-money-laundering standard (FATF
  Recommendation 11) requires transaction records to be kept for at least five years; seven is
  the period most often cited for financial records. The jurisdiction is a deployment's.
- **The table is not partitioned.** `idx_audit_event_organisation_time (organisation_id,
  event_time DESC)` serves a per-tenant time range.

## Decision

1. **A batched delete per tenant behind a recurring job.** `AuditRetentionJob` (daily, 02:30 UTC
   by default) runs `AuditRetention`, which deletes through `JooqAuditRetentionStore.deleteBatch`:
   one `DELETE ... WHERE id IN (SELECT ... ORDER BY event_time LIMIT n)` per batch, at most
   `max-batches-per-run` batches per tenant per run. Each batch is `AuditRetentionBatch.purgeBatch`,
   a transaction of its own that bounds its lock wait, takes the tenant's retention advisory lock
   (`pg_try_advisory_xact_lock`, so two runs never delete side by side), reads the period again
   under the per-key lock every writer of `audit_retention_days` takes (so a period raised or
   removed after the run listed the tenant is the one that applies, and a write of it waits for the
   batch to commit), deletes, and writes the batch's record (decision 6). At most
   `max-organisations-per-run` tenants per run, in organisation-id order: when more store a period,
   each UTC day takes the next window of them (`BoundedRotation`), so no tenant is skipped for good.
   Rejected: archive-then-delete (no archive target exists, nor an integrity story for one) and
   partitions (a tenant's own number of days cannot be a global partition boundary, and the primary
   key would have to change).
2. **Unset keeps everything.** Only a tenant that stores a period loses rows; there is no platform
   default.
3. **A floor of 2555 days (seven years), a ceiling of 36500.** The catalogue refuses a write
   outside that range, on every path (`PUT`, the platform route, initial settings), and the job
   applies `max(stored, floor)` whatever is stored, logging a `WARN` when it raises a value. The
   floor is a reviewed constant (`TenantSettingCatalog.AUDIT_RETENTION_MINIMUM_DAYS`), not
   configuration: lowering it is a compliance decision.
4. **Only routine rows go.** A row is deletable only when its severity is `INFO` **and** its outcome
   `SUCCESS`. Every refusal (`DENIED`), failure, and `LOW`, `MEDIUM`, `HIGH` or `CRITICAL` row is
   kept: they are the rows an investigation needs, and they are few.
5. **Amendments the rules still read are kept** (#255): a `branch.update` row of a branch in
   `DRAFT`, `PENDING_APPROVAL` or `SUSPENDED`, and an `organisation.amend_draft` row of an
   organisation in `DRAFT`, `PENDING_APPROVAL` or `PROVISIONING`, the states from which an approval
   can still come. Once the branch or tenant has moved on, its amendments age out like any row.
6. **One record per batch, in the batch's transaction, which the job cannot delete.** Each batch
   that deleted rows writes one `audit.retention_purge` row in the same transaction as the delete,
   actor type `SYSTEM` (the search filter knows `SYSTEM`; `SCHEDULER` it does not), severity `HIGH`,
   metadata the cutoff and the count, so rows never leave without their record: a failed record
   rolls its delete back. Rejected: one row per tenant per run written afterwards, which a crash or
   a failed insert between the deletes and the record would lose for good.
7. **The application role deletes, through one adapter.** `AuditRetentionDeletePathTests` allows
   exactly one method that names `AUDIT_EVENT` and calls a jOOQ `delete`, `deleteFrom` or `update`.
   A separate database role without `DELETE` for the application is the stronger control, and it
   needs role management the deployment does not have yet; it is left as later work. `V36`
   restates the table comment.
8. **Off until the operator turns it on.** `finaxis.audit.retention.mode` is `OFF` by default;
   `DRY_RUN` counts and logs what each tenant would lose; `ON` deletes.

## Consequences

- A tenant's routine audit history is bounded once a period is stored and the job is on; its
  security and control history is not.
- A deleted row is gone from every read (`audit_event_not_found`, smaller totals and exports) and
  can come back only from a backup. That is why the default is `OFF` and the first run should be
  `DRY_RUN`.
- IP addresses and user agents on routine rows now have an end date.
- Large first runs are spread over days by the batch cap, and leave dead tuples for autovacuum.
- Not done: a legal hold, anonymisation instead of deletion, a separate retention period for
  security rows (they are simply kept), and a dedicated database role.
