# Organisation Provisioning, IAM, and Runtime Access Design

## Decision

Finaxis uses **organisation** as the primary domain term and database boundary. `tenant_code`
remains the stable external compatibility identifier on an organisation; no parallel `tenant`
aggregate, table, or module will be created.

Lifecycle mutations remain in the existing `lifecycle` module and use the common FSM executor.
Every accepted lifecycle command persists its aggregate update, a transition log, an audit event,
and selected externalised transition events in one local transaction. Namastack Outbox remains the
only durable integration publication mechanism.

## Lifecycle Decisions

- Organisation drafts are created in `DRAFT`; submission requires complete metadata.
- Approval transitions `PENDING_APPROVAL -> PROVISIONING`, creates all local setup atomically,
  then transitions to `ACTIVE`. The head-office branch is `ACTIVE` because it is mandatory local
  setup and cannot be operational before the organisation activation succeeds.
- New branches start in `DRAFT`, submit to `PENDING_APPROVAL`, and activate only when their
  organisation is `ACTIVE`.
- Deprovisioning is metadata-only: suspend branches, revoke memberships and dependent assignments,
  block application access, then mark the organisation `DEPROVISIONED`. Data is retained.
- `ORGANISATION` is the audit and event scope. API/command names retain `tenant` only where a
  caller-supplied `tenant_code` requires it.

## IAM and Runtime Authorization

Flyway seeds the global, stable permission catalogue. Default roles are created for each
organisation during activation, which preserves the existing organisation-scoped role schema.
Runtime decisions check permission codes, never Keycloak or local role names. The authorization
component resolves only active user, organisation, membership, branch, role, and permission state.

The request permission cache is scoped to a request and backed by the existing cache abstraction;
there is no long-lived authorization decision cache until mutation invalidation is complete.

## Keycloak Coordination

Keycloak authenticates only. A locally-approved invitation causes an externalised provisioning
event; an idempotent worker finds by email or username before creating a Keycloak user, records the
external subject link, then sends required-action email through Keycloak when configured. A locally
unlinked Keycloak subject is rejected unless a matching pending invitation already exists. This
avoids just-in-time access creation and tenant enumeration.

No distributed transaction is used. Local state and event publication are one transaction; each
external attempt is recorded with an idempotency key and retries are safe.

## Scope and Verification

The implementation delivers application services and query methods first. It keeps public REST
surface limited to the existing API governance pattern and does not introduce domain endpoints
that would need a separate API-product design. Focused unit tests and Testcontainers integration
tests cover lifecycle guards, outbox events, assignments, provisioning idempotency, and runtime
prechecks. `./gradlew qualityGate` is the final repository gate.
