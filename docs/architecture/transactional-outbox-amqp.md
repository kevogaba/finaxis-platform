# Transactional Outbox and AMQP Publication (Namastack)

Namastack (`io.namastack:namastack-outbox-*:1.7.1`, via `spring-modulith-starter-namastack`) is the
**only** transactional outbox mechanism in this codebase, and must remain the only one. There is no
custom poller, publisher, or claim logic anywhere in `com.finaxis.platform`, and there must never
be one — see [ADR 0008](../adr/0008-transactional-outbox-over-direct-amqp.md). Read this with
[FSM transitions](fsm-transitions.md), [ADR 0002](../adr/0002-fsm-transition-infrastructure.md),
and [ADR 0004](../adr/0004-membership-activation-notification-pipeline.md), which establish the
`transition -> Spring application event -> Modulith externalization -> Namastack outbox ->
RabbitMQ -> thin listener` pipeline this document assumes.

## Transactional guarantee

A lifecycle service (or, for the two non-FSM mutations below, `@AuditedAction`-audited service)
persists its aggregate and publishes a Spring application event inside the same `@Transactional`
boundary. Spring Modulith's `EventExternalizationConfiguration` (`TransitionModuleConfiguration`)
selects `ExternalizedTransitionEvent` instances for externalization; Namastack's outbox
infrastructure persists the externalized event as an `outbox_record` row in the **same database
transaction** as the aggregate write. A separate, independent process (Namastack's own scheduler)
polls `outbox_record`, publishes to RabbitMQ, and marks the outcome — publishing never happens
inside the business transaction, so a broker outage cannot roll back or block a lifecycle
transition.

## Event naming convention

Every externalized event today is an instance of the single generic `ExternalizedTransitionEvent`
envelope (`common.transitions`), distinguished by its `target` string — a lowercase,
dot-separated name that is simultaneously the Namastack routing target **and** the RabbitMQ
exchange name (see `RabbitOutboxRouting` in `TransitionModuleConfiguration`). This table maps that
convention onto the human-readable, PascalCase catalogue used elsewhere in project discussions:

| `target` | Catalogue name | Wired today? |
| --- | --- | --- |
| `finaxis.lifecycle.organisation.approval-requested` | *(submit-for-approval; no exact catalogue match)* | Yes |
| `finaxis.lifecycle.organisation.activated` | `TenantActivated` | Yes |
| `finaxis.lifecycle.organisation.rejected` | *(no exact catalogue match)* | Yes |
| `finaxis.lifecycle.organisation.suspended` | `TenantSuspended` | Yes |
| `finaxis.lifecycle.organisation.reactivated` | *(no exact catalogue match)* | Yes |
| `finaxis.lifecycle.organisation.deprovisioned` | *(catalogue lists `TenantDeprovisioningRequested`, the start; this is completion)* | Yes |
| `finaxis.lifecycle.organisation.settings-updated` | `TenantSettingsUpdated` | **Yes (new)** |
| `finaxis.lifecycle.organisation.business-date-advanced` | `BusinessDateAdvanced` | **Yes (new)** |
| `finaxis.lifecycle.branch.approval-requested` | *(submit-for-approval; no exact catalogue match)* | Yes |
| `finaxis.lifecycle.branch.activated` | `BranchActivated` | Yes |
| `finaxis.lifecycle.branch.suspended` | `BranchSuspended` | Yes |
| `finaxis.lifecycle.branch.reactivated` | *(no exact catalogue match)* | Yes |
| `finaxis.lifecycle.branch.closed` | `BranchClosed` | Yes |
| `finaxis.lifecycle.branch.user-assigned` | `BranchAssignedToUser` | Yes |
| `finaxis.lifecycle.branch.user-revoked` | *(no exact catalogue match)* | Yes |
| `finaxis.lifecycle.user.keycloak-provisioning-requested` | `KeycloakUserProvisioningRequested` | Yes |
| `finaxis.lifecycle.user.application-invite-requested` | `ApplicationInviteRequested` | Yes |
| `finaxis.lifecycle.user.deactivation-assignment-revoked` | *(no exact catalogue match)* | Yes |
| `finaxis.iam.user.role-assigned` | `RoleAssignedToUser` | Yes |
| `finaxis.iam.user.role-revoked` | *(no exact catalogue match)* | Yes |
| — | `BranchCreated` | **No** — branch creation is `createDraft`, not an FSM transition; no externalized event exists for it. |
| — | `TenantProvisioningRequested` | **No** — `START_PROVISIONING` is internal-only today. |
| — | `TenantDeprovisioningRequested` | **No** — deprovisioning start is internal-only today. |
| — | `UserProvisioningRequested` | **No** |
| — | `UserInvited`, `UserActivated`, `UserSuspended`, `UserDeactivated` | **No** — these user FSM transitions currently use `InternalTransitionEvent` only. |
| — | `PermissionAssignedToRole` | **No** — audited (`role.assign_permission`) but not externalized. |
| — | `KeycloakUserProvisioned` | **No, deliberately** — Keycloak provisioning success is now audited (`recordExternalDispatch`), but has no externalized event; nothing downstream consumes it yet. Add one when a real consumer needs it. |

This change deliberately closes only the gaps needed for the two new audited mutations
(`TenantSettingsUpdated`, `BusinessDateAdvanced`); wiring the rest of the catalogue would touch
every lifecycle FSM definition for no current consumer. Existing `target` strings are exchange
names real consumers and tests already depend on — they are not renamed to match the catalogue.
New events add a `metadata["eventType"]` entry with the catalogue name (see
`OrganisationSettingsService`/`BusinessDateService`); existing events are not retrofitted with
this key, since that would mean editing already-stable, already-tested FSM definition code for a
purely cosmetic addition — this table is the source of truth for the existing mapping instead.

## Outbox mechanics (verified against the actual Namastack 1.7.1 jars)

Namastack's `outbox_record` status model has exactly three values: `NEW`, `COMPLETED`, `FAILED`.
There is no separate `PUBLISHING`/`DEAD_LETTERED` state — `FAILED` is reached once
`retry.max-retries` attempts are exhausted and **is** the terminal dead-letter state for this
library version; a record does not return to `NEW` after that. `processing.delete-completed-records`
is `false` here, so `COMPLETED` rows are retained (not deleted) for inspection — query them via
Namastack's own `OutboxRecordRepository.findCompletedRecords()`, exactly as the existing outbox
integration tests already do.

Concurrency safety across multiple running instances is **partition-based**, not a per-row
`SELECT ... FOR UPDATE` claim: `outbox_partition` assigns each partition number to exactly one
`instance_id` (tracked in `outbox_instance` via heartbeats), and `outbox_record.partition_no`
determines which instance may process a given record. Instances heartbeat
(`instance.heartbeat-interval`), are considered stale after `instance.stale-instance-timeout`, and
partitions rebalance on `instance.rebalance-interval` — this is what actually prevents two
instances from double-publishing, not row-level locking.

### Configuration (`application.yaml`)

```yaml
namastack:
  outbox:
    enabled: true                                  # disables outbox auto-configuration entirely
    polling:
      trigger: fixed
      fixed:
        interval: 2s
      batch-size: 10
    retry:
      max-retries: 5
      policy: exponential
      exponential:
        initial-delay: 2s
        max-delay: 5m
        multiplier: 2.0
      jitter: 500ms
    processing:
      stop-on-first-failure: true                  # preserves per-record-key ordering
      delete-completed-records: false               # retain COMPLETED rows for inspection
    rabbit:
      fail-on-unroutable: true
      publisher-confirm-timeout: 10s
```

Setting `namastack.outbox.enabled: false` (or the `FINAXIS_OUTBOX_ENABLED` env var) disables the
outbox worker entirely, satisfying "outbox worker can be disabled/enabled by configuration."
`retry.jitter` adds up to ±500ms of randomization to each backoff delay to avoid a
thundering-herd retry pattern across instances after a shared dependency (e.g. RabbitMQ) recovers.

## At-least-once delivery and consumer deduplication

Namastack (like virtually every outbox implementation) provides **at-least-once** delivery: a
crash between a successful RabbitMQ publish and marking the record `COMPLETED` results in the
same record being retried and republished on the next poll. Consumers must be idempotent.

Every AMQP message published through `RabbitOutboxRouting` now carries an
`X-Outbox-Record-Key` header (`TransitionModuleConfiguration.rabbitOutboxRouting()`), set from
Namastack's own `OutboxRecordMetadata.key` — the same key Namastack uses to order and partition
records. Consumers can use it to deduplicate redelivered messages.

This is not a new pattern: every existing consumer downstream of the outbox is already idempotent
by construction —

- `IdentityProvisioningListener` schedules JobRunr jobs with a deterministic id derived from the
  dispatch key, so redelivery does not enqueue a duplicate job;
- `MembershipActivatedNotificationListener` → `JobRunrWelcomeEmailScheduler` uses a deterministic
  job id derived from membership id, transition, and occurrence time;
- `identity_dispatch_log.dispatch_key` is a unique constraint, and both JobRunr handlers
  short-circuit when a dispatch is already `SUCCEEDED`.

New consumers of the two new events should follow the same pattern rather than relying solely on
the new header.

## Observability

`namastack-outbox-observability` (already a dependency) auto-configures Micrometer
`Observation`-based instrumentation with no additional code required:

- `outbox.record.process` — span/timer around each handler invocation, tagged with handler kind,
  channel, handler id (low-cardinality) and record key, record id, delivery attempt
  (high-cardinality);
- `outbox.record.schedule` — span/timer around each polling cycle;
- gauges: `outbox.records` (by status and channel), `outbox.instance.partitions.assigned`,
  `outbox.instance.records.pending`, `outbox.cluster.instances.active`,
  `outbox.cluster.partitions.unassigned`.

These are exported through the existing OpenTelemetry/Micrometer wiring already configured in
`application.yaml`; no custom logging or metrics code was added for this change, since duplicating
what the library already emits would be redundant.

## Tests

- `OrganisationActivationOutboxIntegrationTests`, `BranchActivationOutboxIntegrationTests`,
  `MembershipActivationPipelineIntegrationTests` — already prove transactional persist and
  eventual `PUBLISHED`-equivalent (`COMPLETED`) delivery for the pre-existing events.
- `OrganisationSettingsUpdatedOutboxIntegrationTests`,
  `BusinessDateAdvancedOutboxIntegrationTests` — the same proof for the two new events, plus an
  assertion that exactly one `COMPLETED` record exists for the mutation, not more than one.
- `OutboxTransactionRollbackIntegrationTests` — reuses the real `OrganisationSettingsService`,
  injecting a failure after publish via a `TransitionEventPublisher` test double, and asserts the
  outbox row does not survive. It does not (yet) assert the settings write itself rolled back —
  see [issue #12](https://github.com/kevogaba/finaxis-platform/issues/12): no test in this codebase
  has verified `@Transactional` rollback-on-exception against the real database, and three
  different mechanisms all showed a jOOQ write surviving despite the triggering exception
  correctly propagating.
- `TransitionModuleConfigurationTests` — parses `application.yaml` and asserts the exact
  `namastack.outbox.*` values above.

Genuine multi-instance concurrent-claim testing (two real application instances racing to claim
the same partition) is out of scope for this test suite: it exercises Namastack's own partition
coordination internals (`outbox_partition`/`outbox_instance`), which is that library's testing
responsibility, not this application's. What this suite verifies is the guarantee that matters to
this codebase's consumers — that one logical mutation durably produces exactly one outbox record
and one delivery, never a duplicate.
