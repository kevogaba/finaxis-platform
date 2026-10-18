# User provisioning and Keycloak coordination

User provisioning is local-first. The application owns users, memberships, branch assignments,
role assignments, lifecycle state, and authorization. Keycloak remains the external identity
provider used for authentication only.

Read this with [authorization model](authorization-model.md),
[login prechecks](login-prechecks.md), and
[ADR 0006](../adr/0006-keycloak-outbox-coordination.md).

## Local invitation lifecycle

The implemented flow is:

1. `inviteUser` validates an active organisation, email, username, display name, active branch
   prerequisites, role prerequisites, and assignment requirements.
2. The service creates or reuses a local `user_account`.
3. It creates a `user_organisation_membership` in `PENDING_APPROVAL`.
4. It saves invite preferences on the membership:
   `pending_keycloak_invite` and `pending_application_invite`.
5. It creates staged branch assignments and role assignments locally.
6. It records `user.invite` audit.
7. `approveUser` verifies the pending membership and active access prerequisites.
8. If the user has no Keycloak identity link, it transitions the local user through
   `DRAFT -> PENDING_APPROVAL -> PROVISIONING_IDP` as needed and records a Keycloak dispatch.
9. If the user already has an identity link, it activates the membership immediately when the
   user is `ACTIVE` or `INVITED`.
10. Optional application-invite dispatch is also recorded and externalized.

The Keycloak path then continues asynchronously:

```mermaid
sequenceDiagram
    participant Admin as Approver
    participant App as UserProvisioningService
    participant Outbox as Modulith/Namastack outbox
    participant Worker as JobRunr worker
    participant KC as Keycloak
    participant FSM as Lifecycle FSM

    Admin->>App: approveUser
    App->>FSM: user START_IDP_PROVISIONING
    App->>App: record identity_dispatch_log
    App->>Outbox: keycloak provisioning requested
    Outbox->>Worker: RabbitMQ listener schedules deterministic job
    Worker->>KC: find by email or username
    Worker->>KC: create user when not found
    Worker->>App: link keycloak_identity_link
    Worker->>KC: execute required-actions email when requested
    Worker->>FSM: user INVITE
    Worker->>FSM: membership ACTIVATE
    Worker->>App: mark dispatch SUCCEEDED
```

After provisioning, the user is `INVITED` and the membership is `ACTIVE`. On the first successful
login, `UserFirstLoginActivationService` transitions the user from `INVITED` to `ACTIVE`, records
`user.first_login_activation` audit, and updates `last_login_at`. Already-active users only get
their last-login marker refreshed.

## Durable outbox and idempotent worker

There is no distributed transaction with Keycloak. The local approval transaction records local
state, `identity_dispatch_log`, and externalized transition events. Spring Modulith and Namastack
Outbox own reliable externalization to RabbitMQ. The listener remains thin: it validates the event
metadata and schedules a JobRunr job.

Idempotency is implemented at several layers:

- `identity_dispatch_log.dispatch_key` is unique;
- Keycloak dispatch key is `<organisation>:<user>:KEYCLOAK_PROVISIONING`;
- application-invite dispatch key is `${userId}:${organisationId}:APPLICATION_INVITE`;
- JobRunr job id is deterministic from the dispatch key;
- the worker returns immediately when the dispatch status is already `SUCCEEDED`;
- `KeycloakAdminGateway` searches by email and username before creating a user;
- local identity linking stores the Keycloak subject in `keycloak_identity_link`.

Failures mark the dispatch `FAILED` and increment its attempts (`markDispatchFailed`). The handlers
then throw a `SanitisedJobFailureException` to JobRunr for the failures they catch. It carries the
closed code as its message (the invite handler's retryable failures carry the simple class name
instead) and no cause, except a message-free `InterruptedException` when the original chain held
one. JobRunr therefore retries under its runtime configuration, and the original message does not
reach `jobrunr_jobs`. As of the real email transport (see
[email-delivery.md](../architecture/email-delivery.md)), a *permanent* email-delivery failure for
the organisation-invite email short-circuits that retry instead:
`ApplicationInviteJobRequestHandler` throws `JobRunrException(<simple class name>, doNotRetry = true)`
with no cause, so JobRunr stops retrying immediately rather than exhausting its default retry policy
against an address that will never succeed. A retryable delivery failure, a missing
membership/organisation, or a persistence failure all still retry as before. A failed Keycloak
provisioning job is re-run on re-approval under its existing id: see
[Failed Keycloak provisioning](../operations/tenant-provisioning.md#failed-keycloak-provisioning).

## Keycloak admin gateway

The Keycloak admin adapter is enabled only when
`finaxis.keycloak.admin.enabled=true`; the default is disabled. Configuration keys are under
`finaxis.keycloak.admin.*`:

- `server-url`;
- `realm`;
- `client-id`;
- `client-secret`;
- `enabled`;
- `connect-timeout-millis`;
- `read-timeout-millis`.

The gateway uses the pinned `org.keycloak:keycloak-admin-client:26.0.10` dependency. It wraps
admin calls in the Resilience4j circuit breaker named `keycloakAdmin`, configured in
`application.yaml`.

The gateway creates enabled Keycloak users with unverified email and no credentials, links the
returned subject locally, and optionally emails the user their required actions (#274): by default
`VERIFY_EMAIL` and `UPDATE_PASSWORD` (a user created with no credentials cannot sign in until they
set one), with a link that lives 72 hours and, when a redirect is configured, lands the user there.
They are configured under `finaxis.keycloak.admin`:

- `invitation-required-actions` (`FINAXIS_KEYCLOAK_INVITATION_REQUIRED_ACTIONS`, comma separated);
- `invitation-lifespan` (`FINAXIS_KEYCLOAK_INVITATION_LIFESPAN`, a duration such as `72h`);
- `invitation-redirect-uri` and `invitation-redirect-client-id`
  (`FINAXIS_KEYCLOAK_INVITATION_REDIRECT_URI` and `..._REDIRECT_CLIENT_ID`): both or neither; the
  client must allow the URI as a valid redirect, or Keycloak refuses the email.

Recovery is documented in the foundation API (memberships): the `invitation` status on a membership
detail, `POST /memberships/{id}/invitation/retry` for a failed job, and
`POST /memberships/{id}/invitation/resend` for the email (rate limited per account by
`finaxis.invitation.resend-cooldown`, 5 minutes by default). A membership revoked while its job
waited ends the job without calling Keycloak and leaves the dispatch `FAILED` with the reason
`CANCELLED:MembershipRevoked` (a dispatch that had already succeeded stays `SUCCEEDED`). A resend
is refused (`lifecycle.invitation_resend_not_applicable`) for a revoked or suspended membership.

Known minor edge: if the required-actions email is sent and a later local FSM step fails, a retry
may send another required-actions email. Keycloak required-actions email is re-issuable, and the
worker keeps local state idempotent around the dispatch key and identity link.
