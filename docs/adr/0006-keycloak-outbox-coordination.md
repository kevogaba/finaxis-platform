# ADR 0006: Keycloak Coordination Uses Outbox and Idempotent Jobs

## Status

Accepted

Date: 2026-07-14

## Context

User approval must coordinate local Finaxis state with Keycloak user provisioning. The local state
includes user lifecycle, organisation membership, branch assignments, role assignments, invite
preferences, identity links, audit, and dispatch logs. Keycloak is an external system and cannot
participate in the application's database transaction.

The platform already uses Spring Modulith, Namastack Outbox, RabbitMQ, and JobRunr. ADR 0004
documents the reference pattern for transition externalization and background work.

## Decision

Do not use a distributed transaction with Keycloak.

The local approval transaction records local lifecycle state, `identity_dispatch_log`, and a
Spring Modulith externalized event. Namastack Outbox durably externalizes that event to RabbitMQ
after commit. A thin RabbitMQ listener validates the event and schedules a JobRunr request with a
deterministic id derived from the dispatch key.

The JobRunr handler is idempotent:

- it returns when the dispatch status is already `SUCCEEDED`;
- it searches Keycloak by email and username before creating a user;
- it links the Keycloak subject locally before advancing local FSM steps;
- it tolerates already-invited users and already-active memberships;
- it marks the dispatch `SUCCEEDED` only after Keycloak and local FSM steps complete.

Keycloak admin calls use `org.keycloak:keycloak-admin-client:26.0.10`, configured for the
Keycloak 26.6.4 server used by this branch. A Spring `RestClient` implementation was considered,
but the official admin client keeps representation and endpoint handling in one dependency for
the current scope.

Admin calls are protected by the Resilience4j circuit breaker named `keycloakAdmin`. Retry is left
to JobRunr and the broker path; local failures mark the dispatch `FAILED` and are rethrown.

## Consequences

The local database transaction remains short and does not depend on Keycloak availability.
Accepted local state can be retried if Keycloak or the worker path is temporarily unavailable.

The dispatch log is the operational source for provisioning status, attempts, last error, and the
external Keycloak subject.

The worker can safely retry user creation because it performs find-before-create and uses a stable
dispatch key. JobRunr deduplicates scheduled work by deterministic job id for the same dispatch.

Known minor edge: if `executeActionsEmail([VERIFY_EMAIL])` succeeds but a later local FSM step
fails, retry may send another required-actions email. Keycloak required-actions email is
re-issuable, and the local state remains idempotent around the dispatch key and identity link.

## Alternatives Considered

XA or distributed transaction with Keycloak:

- Rejected. Keycloak is an external HTTP system and this would add fragility without a true shared
  transactional boundary.

Call Keycloak directly inside `approveUser`:

- Rejected. It would couple user approval to Keycloak availability and hold local transactions
  open around network calls.

Use JobRunr as the outbox:

- Rejected. JobRunr is for durable background work. Modulith plus Namastack Outbox is the selected
  event externalization path.

Implement Keycloak calls directly with Spring `RestClient` now:

- Rejected for the current branch. The pinned Keycloak admin client already provides the required
  user search, create, and required-actions email APIs.

## Verification

- `UserProvisioningService`
- `IdentityProvisioningListener`
- `KeycloakUserProvisioningJobRequestHandler`
- `KeycloakAdminGateway`
- `KeycloakAdminProperties`
- Flyway migrations `V5` and `V6`
- [ADR 0004](0004-membership-activation-notification-pipeline.md)
