# ADR 0005: Organisation Lifecycle Uses Metadata-Only Deprovisioning

## Status

Accepted

Date: 2026-07-14

## Context

Organisation, branch, user, membership, role, permission, and assignment records are audit and
authorization evidence. The platform also has lifecycle transition logs, audit events, identity
links, dispatch logs, settings, business dates, and reference sequences that must remain
diagnosable after access is blocked.

The repository already uses explicit FSM transitions and a transactional outbox pattern for
state changes. Hard-deleting an organisation would bypass that model, break historical references,
and make partial cleanup hard to investigate.

Global platform roles also need a durable model. They should not become hard-coded runtime role
names because runtime authorization evaluates permission codes.

## Decision

Organisation, branch, user, and membership deprovisioning is metadata-only. The application blocks
access by lifecycle state and assignment revocation, not by physical deletion.

Organisation deprovisioning transitions `ACTIVE` or `SUSPENDED` organisations to
`DEPROVISIONING`, suspends non-terminal branches, revokes non-revoked memberships, revokes active
branch and role assignments one record at a time, and completes the organisation as
`DEPROVISIONED`.

Branch closure moves active or suspended branches to `CLOSED` only after guards reject active
assignments and active child branches. User deactivation uses the user FSM and revokes active local
assignments. Membership revocation uses membership FSM transitions and revokes local grants.

The system retains records and logs needed for audit, investigation, support, recovery, and future
retention/export work.

Global roles are modelled under the reserved platform organisation
`00000000-0000-0000-0000-000000000000` with `tenant_code=PLATFORM`. The initial system roles are
`PLATFORM_SUPER_ADMIN` and `PLATFORM_SUPPORT`. They grant permission codes from the same catalogue
as organisation roles.

## Consequences

Runtime access is blocked because inactive organisation and membership states fail login prechecks,
branch-scoped access requires active assigned branches, and deprovisioning revokes active branch
and role assignments.

Historical data remains queryable for audit and operational investigation. Transition logs and
audit events remain attributable to an actor, reason, request id, and old/new state.

No current workflow should bypass lifecycle records with bulk status changes or hard deletes.
Cleanup must remain explainable even when it partially fails.

The policy intentionally defers customer export package format, retention schedules, legal hold,
anonymisation, encryption-key destruction, and physical deletion. Each requires a separate design,
approval, and implementation before destructive operations are introduced.

## Alternatives Considered

Hard-delete organisations and child data:

- Rejected. It would remove audit evidence, break foreign-key history, and make authorization
  support issues harder to investigate.

Soft-delete only the organisation row:

- Rejected. Access can also be granted through memberships, branch assignments, role assignments,
  and branch selection. The implementation must block those paths explicitly.

Hard-code platform role names in runtime checks:

- Rejected. Runtime authorization checks permission codes. Platform roles should remain normal
  system roles under a reserved organisation.

## Verification

- `OrganisationProvisioningService` deprovisioning path
- `BranchProvisioningService` close and assignment guards
- `UserProvisioningService` membership revocation and user deactivation paths
- `FoundationLifecycleDefinitions` organisation, branch, user, and membership FSM graphs
- Flyway migration `V1__foundation_schema.sql` (schema) and
  `V2__platform_reference_data.sql` (permission catalogue and platform roles). These were
  originally `V1`, `V4`, and `V5`; see [ADR 0010](0010-greenfield-migration-reset-and-schema-rewrite.md).
