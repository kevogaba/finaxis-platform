# ADR 0008: Namastack-Only Transactional Outbox

## Status

Accepted

Date: 2026-07-15

## Context

ADR 0002 and ADR 0004 already established that lifecycle transitions publish through Spring
Modulith event externalization and Namastack Outbox, never directly via `RabbitTemplate`, and that
JobRunr is background-job infrastructure, not an outbox substitute. This ADR formalizes that
decision for the outbox mechanism itself, and records what was verified by inspecting the actual
`io.namastack:namastack-outbox-*:1.7.1` jars (this sandbox has no pre-populated Gradle cache, so
these facts were confirmed by downloading the jars directly and reading their
`spring-configuration-metadata.json`, schema SQL, and class files — not guessed).

Two gaps existed before this change: the two new non-FSM mutations (organisation settings update,
business date advance) had no service at all, and the project's own `namastack.outbox.*`
configuration was entirely default (only `rabbit.fail-on-unroutable` was set) — retry, backoff,
poll interval, and enable/disable were unconfigured and undocumented.

## Decision

Continue to use Namastack exclusively for outbox durability and publication. No custom outbox
table, poller, publisher, or claim logic is introduced anywhere in this change, matching the
explicit constraint for this work. The two new events
(`finaxis.lifecycle.organisation.settings-updated`, `finaxis.lifecycle.organisation.business-date-advanced`)
are published the same way every other non-FSM externalized event already is in this codebase —
via `TransitionEventPublisher.publish(ExternalizedTransitionEvent(...))` outside a transition,
mirroring `BranchProvisioningService.assignUser`/`revokeUserAssignment` exactly.

`namastack.outbox.*` configuration is now explicit rather than all-default: `enabled` (surfaced so
the worker can be turned off entirely), polling interval/batch size, retry policy (exponential
backoff, 5 max retries, jitter), and processing behavior (`stop-on-first-failure` to preserve
per-record-key ordering, `delete-completed-records: false` to retain completed records for
inspection). Every value is documented in
[transactional-outbox-amqp.md](../architecture/transactional-outbox-amqp.md) alongside what was
actually found in the library, including the real `outbox_record` status model (`NEW` /
`COMPLETED` / `FAILED` — no separate dead-letter state) and the real concurrency mechanism
(partition ownership via `outbox_partition`/`outbox_instance`, not per-row locking).

Every published message now carries an `X-Outbox-Record-Key` header (Namastack's own
`OutboxRoute.Builder.header(...)` extension point), so consumers have a stable key to deduplicate
against under the library's inherent at-least-once delivery.

The Part 2 event-name catalogue (`TenantActivated`, `BranchClosed`, etc.) is documented as a
mapping against existing `target` strings rather than used to rename them, and most catalogue
names without a current externalized event are left unwired — see
[transactional-outbox-amqp.md](../architecture/transactional-outbox-amqp.md) for exactly which
ones and why.

## Consequences

No new schema, worker, or publishing code exists to maintain or diverge from Namastack's own
correctness guarantees. Configuration is explicit and independently verified against the library's
real property names, so it cannot silently rely on defaults nobody chose deliberately. The two new
services demonstrate the reference pattern (`@Transactional` service → store write → externalized
event publish) for any future non-FSM mutation that needs a durable integration event.

Consumers must keep being idempotent regardless of the new header — the header is a convenience,
not a substitute for the deterministic-job-id and dispatch-key patterns already used throughout
`lifecycle`/`notifications`.

## Alternatives Considered

Publish the two new events with `RabbitTemplate` directly, since they're not FSM transitions:

- Rejected. ADR 0002/0004 already reject bypassing the outbox for exactly this reason: it couples
  the settings/business-date transaction to broker availability. Branch assignment already proves
  the outbox path works for non-FSM events; the new services copy it.

Introduce a `DEAD_LETTERED` status distinct from `FAILED` to match the task's literal wording:

- Rejected. Namastack 1.7.1 does not have this status; inventing one in application code would
  require shadow state disconnected from what the library actually tracks. `FAILED` after
  `max-retries` is documented as the dead-letter-equivalent terminal state instead.

Wire externalized events for the entire ~20-name catalogue now:

- Rejected for this change (confirmed with the user). It would touch every lifecycle FSM
  definition for names with no current consumer. Documented as a mapping table instead, with
  specific reserved names called out for when a real consumer needs them.

## Verification

- `OrganisationSettingsServiceTests`, `BusinessDateServiceTests`
- `OrganisationSettingsUpdatedOutboxIntegrationTests`, `BusinessDateAdvancedOutboxIntegrationTests`
- `TransitionModuleConfigurationTests`
- Existing `OrganisationActivationOutboxIntegrationTests`, `BranchActivationOutboxIntegrationTests`,
  `MembershipActivationPipelineIntegrationTests`
- ADR 0002, ADR 0004, ADR 0006
