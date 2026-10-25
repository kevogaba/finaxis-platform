# Audit Logging

> **Architecture overview.** This document covers *what* to audit and how the pieces fit together.
> For the implementation reference — redaction mechanics, the `AuditService` API, and the query
> service — see [audit logging](../security/audit-logging.md).

Audit trails are for security-sensitive and business-critical actions. They are not a replacement
for access logs, metrics, traces, or event logs.

## What To Audit

Audit these actions when they are implemented:

- admin changes;
- role, permission, membership, and scope changes;
- critical state transitions;
- manual overrides;
- security-sensitive actions;
- rate-limit policy changes;
- critical configuration changes.

Do not persist every normal successful `GET` request as an audit event. The one read-like route
that audits is the audit CSV export (`GET /api/v1/tenant/audit-events/export`, #167): it is a bulk
extraction of the log itself, so each request writes one `audit.export` row (see
[Audit export](#audit-export-167) and [ADR 0039](../adr/0039-global-search-and-audit-export.md)).
The policy is unchanged for every other read: a search, a page or a single event writes nothing.

## Event Shape

The common audit foundation records:

- actor type and actor ID;
- tenant/organisation ID and branch ID;
- the subject user ID: the user the event is about, never the actor by default (see
  "The subject user" below);
- action, and the event type derived from it (`event_type` is the action itself, the domain
  event name such as `organisation.activate`; it no longer repeats the resource type);
- resource type and resource ID (stored as `entity_type`/`entity_id`; the ID only when it is a
  UUID);
- outcome and severity;
- reason/comment;
- before/after state summaries;
- metadata (including source module, command name, and external system reference);
- request/correlation ID;
- source IP and user agent;
- timestamp.

Redaction is enforced structurally, not by convention: `AuditService.record(...)` redacts
metadata, before, and after before an event reaches its repository. Do not store passwords,
bearer tokens, session cookies, API keys, raw authorization headers, or sensitive PII in audit
metadata regardless — see [audit logging](../security/audit-logging.md) for the full redaction
policy, the seven `AuditService` methods, and the query service.

### One row per transition (#186, ADR 0031)

A user or membership transition writes one audit row, the lifecycle FSM's row. It is keyed by the
aggregate the transition acts on: `USER_ACCOUNT` with the account id for a user, `MEMBERSHIP` with
the membership id for a membership. Its action is `<aggregate>.<transition>` (for example
`user_account.suspend`, `membership.suspend`, `membership.revoke_pending`) and it carries the
from-state and to-state. An application service never repeats that row. It may add a companion
row under its own action name, only for a fact the FSM row cannot carry (a checker marker, an
approval intent, cascade counts); ADR 0031 Decision 1 lists them.

A membership revoke also writes `membership.assignments_revoked` (`MEMBERSHIP`, the membership id),
after the FSM row. It carries `userId`, `branchAssignmentsRevoked` and `roleAssignmentsRevoked` in
`metadata_jsonb`, the number of assignments the revoke ended.

These rows are about a user or a membership, so `branch_id` is null on each of them (see "Reading
the log" for the branch rule). Rows written before this release keep the older shape and are not
rewritten; ADR 0031 lists the old and new keys for every consumer that must switch.

### The subject user (#186)

An event about a user names that user in `subject_user_id`. The writer sets it from the typed
`AuditCommand.subjectUserId`, never from metadata. The column is nullable and has no foreign key,
because an audit row outlives the user it names (`V29`; its partial index is `V30`). The user is
an identity Keycloak owns and `user_account` mirrors, so the column is a plain reference to
`user_account.id`, indexed only where it is not `NULL` (owner ruling). The subject
query returns the events the writers name as being about one user, one row per transition
(ADR 0031). It is not a complete history of a user; the exclusion below and "Subject and actor"
say where it stops.

**Known exclusion (pre-existing, a follow-up).** The roles that `user.invite` grants to the
invitee are written through the store (`store.assignRole`) and write no audit row. The
`user.invite` row (subject = the invitee) records the membership id only, not the role ids
granted, so those grants appear in no subject query. The invitee's branch assignments are
audited (`branch.assign_user`, subject = the invitee). Auditing the invitation's role grants as
`user.assign_role` rows is a follow-up change, not part of this one.

The rule:

- **The subject is the user the event is about, and never the actor by default.** When an
  administrator assigns a role, the row's actor is the administrator and its subject is the
  assignee. A revocation names the user whose access it ends.
- **A user-account row names the account; a membership row names the member.** A membership row
  is keyed by the membership, but the member is its subject, so the member's history is one query
  on the subject.
- **An event about no user carries `NULL`.** That covers organisations, branches (their
  lifecycle, amendments and refusals), tenant settings, business dates, role definitions, and the
  accounting records (fiscal periods, GL accounts, posting rules, journals, reconciliations and
  balance rebuilds).
- **A subject that is not a UUID is stored as `NULL`**, like a non-UUID branch or entity id.

**Subject and actor are different questions.** The actor (`actor_user_id`, and
`actor_external_subject`, which the `actor_subject` search filter matches) answers "who did it".
The subject answers "who is it about". A user's own manual-journal activity (create, amend,
approve, reject) carries no subject, because each of those events is about a journal; it is found
by the actor filter, not by the subject query. `tenant.bootstrap_retry` is about the organisation,
so it carries no subject either. The administrator that bootstrap invites has their own
`user.invite` row, whose subject is that administrator.

The writers, by event family (the action names are the ones the writer records):

| Event family | Actions | Subject |
| --- | --- | --- |
| User account transitions (FSM, `USER_ACCOUNT`) | `user_account.submit`, `.start_idp_provisioning`, `.invite`, `.activate`, `.suspend`, `.reactivate`, `.start_deactivation`, `.complete_deactivation`, and their `DENIED` or `FAILURE` rows | the account (its id) |
| Membership transitions (FSM, `MEMBERSHIP`) | `membership.activate`, `.suspend`, `.reactivate`, `.revoke`, `.revoke_pending`, `.revoke_suspended`, and their `DENIED` or `FAILURE` rows | the member (the membership's user) |
| Membership revoke cascade | `membership.assignments_revoked` | the member |
| Invitation and approval | `user.invite`, `user.approve` | the invitee or approved user (the invite's role grants: see the exclusion) |
| Identity and invitation dispatch (success and failure) | `user.keycloak_provisioning`, `user.application_invite`, `user.welcome_email` | the user the dispatch is for |
| First login | `user.first_login_activation` (and the `user_account.activate` row beside it) | the user |
| Branch assignment | `branch.assign_user`, `branch.revoke_user` | the target user, not the actor |
| Role assignment | `user.assign_role`, `user.revoke_role` | the target user, not the actor (not the roles `user.invite` grants: see the exclusion) |
| Deactivation cascade | `user.deactivation_assignment_revoked` | the deactivated user |
| Deprovisioning cascade | `organisation.deprovision_assignment_revoked` | the user whose assignment was revoked |
| Organisation, branch lifecycle, role definitions, tenant settings, business date, accounting, bootstrap | every other action | none (`NULL`) |

A deprovisioning revocation keeps the organisation as its resource, as before. Its row also names
the user whose assignment it ended, so that user's rows are found by the subject query.

Rows written before `V29` keep a `NULL` `subject_user_id`, and nothing is backfilled: a subject
recovered from metadata would be a guess, not evidence. A query by subject therefore finds only
the rows written from the release that contains `V29`. Older user rows are still found by their
older keys (`entity_id` for the account and membership rows, `metadata_jsonb.userId` for the
cascades, by SQL: no API filter reads metadata), and ADR 0031 lists the old shapes. The cut-over is
recorded in
[audit index migrations](../operations/audit-index-migrations.md). The `subject_user_id` search
filter answers that query on the tenant route and on both platform routes (see
[search filters](#search-filters-183)). A user's platform lifecycle rows (`user_account.suspend`,
`.reactivate`,
`.start_deactivation`, `.complete_deactivation`) are written under the PLATFORM organisation, so
the tenant log never holds them and the subject query finds them on the platform log only. The
onboarding rows (`user_account.submit`, `.start_idp_provisioning`, `.invite`, `.activate`) are
written under the tenant and are found by a tenant subject search.

## Severity policy (#184)

A writer that knows the severity of its event passes it (`AuditCommand.severity`; accounting does
for its own actions). A writer that names none gets `AuditSeverityPolicy.resolve(action, outcome)`,
so lifecycle and IAM rows are classified in one place instead of all staying `INFO`:

| Outcome | Action | Severity |
| --- | --- | --- |
| `FAILURE` | any | `HIGH`: what the caller asked for broke (a bootstrap retry, an FSM transition that errored) |
| `DENIED` | any | `MEDIUM`: a rule refused it (an FSM guard, a maker-checker refusal) |
| `DENIED` | `api.access_denied` | `HIGH`: the authorization refusal itself, below |
| `SUCCESS` | verb `suspend*`, `close*`, `lock`, `deactivate`, `start_deactivation`, `complete_deactivation`, `deprovision`, `*_deprovisioning`, `revoke*`, `assign_permission`, `remove_permission`, `*_revoked`, `self_approve`, `archive` | `HIGH`: access is removed or ended, or authority is granted |
| `SUCCESS` | verb `approve`, `activate`, `reactivate`, `unlock`, `reject`, `return_for_changes`, `invite`, `assign_role`, `assign_branch`, `assign_user` | `MEDIUM`: access is decided or granted |
| `SUCCESS` | anything else (reads, edits, drafts, submissions, selections) | `INFO` |

The verb is the part of the action after the last `.`. An explicit severity always wins: the
maker-checker switch and platform-organisation refusals stay `HIGH`, and accounting keeps its own.
Rows written before this policy keep the `INFO` they were stored with.

## Denied access (#184)

An authenticated caller's `403` writes one `api.access_denied` row, `DENIED` / `HIGH`, through
`recordSecurityEvent` (`DeniedAccessAuditor`). It is written from the three places that answer
`403`: the application's `ForbiddenOperationException` (`MissingPermissionException` included),
method security's `AccessDeniedException`, and the filter chain's access-denied handler.

- **Content.** The actor, the tenant, the request id and client address (from the request
  context), `reason` = the problem code, and metadata `method`, `route` (the matched pattern such
  as `/api/v1/memberships/{id}`, never the raw path, which can carry ids), `permission` (the
  first missing code, when the refusal names one) and `tenant_context=none` when there was none.
  Never a header, a token, a query string or a body.
- **Tenancy.** A denial with no tenant context is recorded under the platform organisation
  explicitly, instead of through the repository's warned fallback (#157), and says so in metadata.
- **Not twice.** A refusal that already writes its own durable `DENIED` row
  (`lifecycle.approver_is_*`) is not written again. An unauthenticated request is a `401` and is
  not audited here; neither is the active-organisation filter's own `403` for a tenant the
  caller does not belong to, which runs before the request context exists.
- **Before a context exists.** A JWT-only caller choosing an organisation has no request context
  yet; the selection service's refusal carries the user it is about
  (`ForbiddenOperationException.actorUserId`), so that `403` is audited too, under the platform
  organisation with `tenant_context=none`.
- **Flood bound.** A caller (actor and tenant) is limited to `finaxis.audit.denied-access.capacity`
  rows per `refill-period` (default 10 per minute) through the same distributed Bucket4j bucket as
  request rate limiting, so a hostile client collects `403`s without filling the log; the request
  rate limiter bounds the requests themselves. A skipped row is logged at `DEBUG` only. The
  capacity and refill period are validated when bound (a non-positive value fails the start). With
  `finaxis.rate-limit.enabled=false` there is no bucket, so every denial is written and a startup
  `WARN` says so; no per-instance fallback is used, and the rows can be turned off with
  `finaxis.audit.denied-access.enabled=false`.
- **Never changes the answer.** A failed write or an unavailable bucket is a message-free `WARN`;
  the `403` is returned as it would have been. Set `finaxis.audit.denied-access.enabled=false` to
  turn the rows off.
- **Platform audit routes.** A tenant admin refused on `/api/v1/platform/audit-events` or a
  platform tenant route is written under the caller's own tenant (reason
  `platform_context_required`), so the row shows in that tenant's log, and a platform
  operator finds it there with `outcome=DENIED`; a refusal with no tenant context lands in the
  platform log. `PlatformAuditSupportReadIntegrationTests` covers this, and `PLATFORM_SUPPORT`
  reading both logs, which writes no row of its own.

## Current Adapter

`AuditService` writes to an `AuditEventRepository` port. `JooqAuditEventRepository` is the durable
adapter, backed by the append-only `audit_event` table; the logging-only repository in
`AuditConfiguration` is a `@ConditionalOnMissingBean` fallback used only if no durable adapter is
registered. Domain modules call the application-level audit service directly — that is the only
mechanism; there is no annotation-driven alternative.

### Retention (#255)

Audit rows are also a **control input**, not only evidence. The maker-checker amender rules read
them: branch approval refuses anyone with a successful `branch.update` event on the branch
(`lifecycle.approver_is_branch_modifier`, ADR 0028), and approving or rejecting a pending tenant
refuses anyone with a successful `organisation.amend_draft` event on it
(`lifecycle.approver_is_tenant_modifier`, #221). A purged amendment row makes its amender eligible
again, so the rule fails open.

- **Today nothing purges.** The `audit_retention_days` setting has no job behind it (#164), so no
  code changes with this note.
- **Any future retention or purge job must keep** the `branch.update` events of every branch, and
  the `organisation.amend_draft` events of every tenant, that is still in progress (not yet
  `ACTIVE` or terminal), or first move the amender fact into a dedicated table the rules read
  instead (a durable column or table on `branch` and `organisation` is the long-term alternative).
- **That job's tests must prove it**: a purge run past the retention window leaves those rows of an
  in-progress branch and tenant in place, and the amender is still refused afterwards.

## Request Correlation

`HttpAccessLogFilter` preserves an inbound `X-Request-Id` of 8 to 64 characters of
`[A-Za-z0-9._-]` or generates one (a rejected value is never logged or stored). It returns the
header to clients and stores `requestId` in MDC for request logs. `ActiveOrganisationContextFilter`
separately installs `RequestContexts`, which carries tenant, branch, actor, correlation, user-agent
and the client address. The audit adapter falls back on the tenant, actor, correlation, user-agent
and client address when a caller does not supply them explicitly. It never falls back on the
branch: the context still holds the selected branch (for MDC), but `branch_id` comes only from
`AuditCommand.branchId` (#186). The correlation id is the client's `X-Correlation-Id` only when it
has the same 8 to 64 character `[A-Za-z0-9._-]` shape (#252); otherwise, as when the header is
absent, it is the request id, so `audit_event.correlation_id` and the `correlationId` MDC field
never hold a rejected value. The same holds for the request metadata made ambient while the
principal loads.

### Client address (#185)

`audit_event.ip_address` is the client address of the request that wrote the row. The rule is in
one place, `ClientIpResolver` (`iam.adapter.inbound.security`), called by
`ActiveOrganisationContextFilter` where the user agent is read; the result travels on
`RequestContext.clientIp`, and `JooqAuditEventRepository` stores `AuditCommand.sourceIp` when a
caller set one and the context's address otherwise. A row written outside an HTTP request (a
JobRunr handler, a listener) has no context, so its address is the command's or `NULL`.

- **Trust is configured, never assumed.** `finaxis.security.client-ip.trusted-proxies`
  (`FINAXIS_CLIENT_IP_TRUSTED_PROXIES`, `ClientIpProperties`) lists the reverse proxies as
  addresses or CIDR ranges, IPv4 or IPv6. It is **empty by default**: then `X-Forwarded-For` is
  ignored and the direct peer is the client. An entry that is not an address or a range, or a
  range of every address (`0.0.0.0/0`, `::/0`, `::ffff:0:0/96`), fails startup naming the
  property and the entry. An IPv4-mapped entry (`::ffff:10.0.0.0/104`) is the IPv4 range it maps.
- **The peer is the container's.** The resolver reads the remote address and the header from the
  innermost (container) request, past every wrapper, so no filter's view is ever used. Spring's
  `ForwardedHeaderFilter` (`server.forward-headers-strategy: framework`), which rewrote
  `getRemoteAddr()` to the left-most entry of any peer, is gone: every strategy but `native` is
  refused at startup (#256).
- **Tomcat has already applied the same list** (`forward-headers-strategy: native`, #256): from a
  listed peer its `RemoteIpValve` walks `X-Forwarded-For` from the right past the listed hops,
  makes the entry it stops at the container's remote address and leaves only the entries to its
  left in the header; from any other peer it changes nothing. The resolver continues that one walk
  with the same list, so it never trusts more than the list: an unlisted remote address is the
  client. It is kept as defence in depth and for what the valve does not do (strict parsing,
  normalisation, the hop cap, an entry with a port or brackets, where the valve stops). An entry
  that is no address at all, or an empty entry, which only a listed hop could have written as its
  own peer, leaves that text as the remote address and the row stores `NULL`. A listed IPv6 hop
  written with a `%zone` is passed over by the valve where the resolver alone would stop; its
  address is listed either way. See
  `docs/security/production-hardening.md` ("Forwarded headers").
- **Behind a trusted peer**, `X-Forwarded-For` is walked lazily from the right (several header
  lines are one list, in order): the client is the first entry that is not itself a trusted
  proxy; if every entry is trusted, the left-most. Entries to the left of the client are never
  read, so a forged left-most entry is neither believed nor able to veto the real one, however
  long or malformed it is. Only `X-Forwarded-For` is read; the RFC 7239 `Forwarded` header is not.
- **Bad input stops the walk, never an error.** A blank or malformed entry (not an IP literal,
  optionally with a port) reached during the walk, or more than 16 entries (`MAX_HOPS`), stops it
  and the trusted peer is the client. A peer address with an IPv6 zone id is read without it.
- **Normalised text**: IPv4 dotted quad; an IPv4-mapped IPv6 address as IPv4; other IPv6 in
  RFC 5952 form (lower case, longest zero run compressed), no zone or port; at most 39
  characters. The column is unbounded `TEXT`, so no migration was needed. A peer that is not an IP
  literal stores `NULL`.
- **Personal data.** The address is only stored on the audit row. It is not put into MDC, logs or
  metric tags by the resolver (the access log's `remoteAddress` and MDC `client.address` are
  `getRemoteAddr()`, which since #256 is the client the valve resolved, so the access log and the
  audit row name the same address whenever the listed proxy writes plain canonical IP literals, as
  Traefik does; an entry with a port, `::ffff:` or other non-canonical text is the access log's
  address verbatim while the row holds the normalised address). Only the tenant audit
  routes and the platform detail route of the platform's own log return it; both platform pages
  withhold it (owner ruling, #247). How long rows, and with them addresses, are kept is not yet
  defined: the `audit_retention_days` setting has no purge job behind it (#164).

### User agent (#185)

`audit_event.user_agent` is the `User-Agent` header of the request that wrote the row. It is read
in `ActiveOrganisationContextFilter` (`requestMetadata`) into `RequestContext.userAgent`, and
`JooqAuditEventRepository` stores the command's value when one is set and the context's otherwise,
as it does for the address. No caller sets the command's value today.

- **Capped at 512 characters.** V1 declares the column unbounded `TEXT`, so the cap is an
  application rule, `JooqAuditEventRepository.MAX_USER_AGENT_LENGTH`. A longer agent is cut to 512
  characters silently: the audited action is never refused or failed for it, and nothing is
  logged. `UserAgentAuditIntegrationTests` proves that a 614-character agent sent through a real
  mutation is stored as its first 512 characters while the request succeeds.
- **Known limit: no principal, no context.** `installRequestContext` installs the request metadata
  only when the request carries an `AppPrincipal`. A request without one (anonymous, a JWT with no
  resolved active-organisation context, or any point before the principal is loaded) runs with the
  context cleared, so a row written during it has `ip_address` and `user_agent` `NULL`. Nothing
  else fills them. The one exception is the first-login activation, which runs inside the load:
  the loader makes the metadata ambient for it, so that row keeps both.

## Reading the log

Audit events are read through `GET /api/v1/tenant/audit-events` (tenant users, active tenant
only) and `GET /api/v1/platform/audit-events` plus
`GET /api/v1/platform/tenants/{tenant_id}/audit-events` (platform operators: the PLATFORM
organisation's log and any tenant's). A page item and a detail response have the same fields
under the same names. Both reuse `audit.view`; the platform routes require it in
the PLATFORM organisation. See
[audit logging](../security/audit-logging.md#rest-read-endpoints-and-the-platform-permission-model).

### Audit export (#167)

The export is the exception to "do not audit normal reads" because it is a bulk extraction, the
one read whose purpose is to move data out of the system, and "who exported what, when" has no
other answer ([ADR 0039](../adr/0039-global-search-and-audit-export.md), points 12 to 17). It reads
through the same `AuditSearchParameters` and `JooqAuditEventQueries.searchCondition` as the search,
adds `occurred_from` (required) and a 31-day window, refuses more than 10,000 matching rows with
`400 audit.export_too_large`, and reads in keyset batches of 1,000 on `(event_time, id)` against
`idx_audit_event_organisation_time` (`AuditQueryPlanTests`). The columns are fixed and exclude the
address, user agent, actor subject, the JSON documents, the reason, the correlation id and the event
id; they are not selected at all.

**The audit row.** One per request, written through `AuditService` after the stream (so it is not in
the file it describes): action `audit.export`, resource type `AUDIT_EVENT` (the row is about the
export request, not the events in it, ADR 0031), no resource id, and metadata of exactly
`{filters, row_count}` plus `error_class` for an aborted stream.

- **`filters`** holds the filter parameters the caller sent, under their snake_case names and as
  sent (a repeated parameter's values joined by commas; a value is cut at 256 characters); never
  `page`, `size` or the pinned `occurred_to`. **`q` and `actor_subject` are stored as
  `[redacted]`**: `q` may be an email address and `actor_subject`
  is an identity subject, and audit metadata holds no sensitive personal data. The rest are kept,
  so a reader can still tell what window and filters were exported.
- **`row_count`** is always present: the rows of the batches written to the response.
- **`SUCCESS`** (severity `MEDIUM`, passed explicitly: the policy would say `INFO` for this verb) is
  written once the last row has been flushed. **`FAILURE`** (`HIGH`) is written for a stream that
  failed after it began (a client disconnect, a read error), with `error_class` the exception's
  simple class name, never its message (the V24 rule), and for the too-large refusal, which is
  written with `recordIndependently` so the `400` cannot roll it back, with `row_count` 0.
- **The audit write never changes the answer.** A failed write after the bytes were sent is one
  message-free `WARN` (class names and frames) and the response completes; a failed write for the
  refusal still answers `400`. Nothing is retried.
- **Request thread capture.** The stream runs on the virtual-thread application executor. Everything
  it needs (the organisation, the filter, the actor, and the request id, address, user agent and
  context snapshot) is captured as plain values on the request thread first, and the row carries
  them although it is written on the stream's thread.
- **No export row** for a `403` (it writes `api.access_denied`, as every refusal does) or for a
  `400` caused by a bad filter or a missing `occurred_from`. The HTTP access log redacts this
  route's query string, as it does the global search's.

### Search filters (#183)

The three searches share one filter set, parsed by `AuditSearchParameters` and bounded by
`AuditEventFilter.requireValid` in `AuditQueryService` (a bad value is a 400 naming the
parameter, never a 500). The owner delegated these defaults to the builder; they are the design:

- **`outcome`**: the closed set `SUCCESS`, `FAILURE`, `DENIED` (`AuditOutcome`), exact match.
- **`severity` and `min_severity`**: both, as `AuditSeverity`; `severity` is exact and
  `min_severity` is that value or above in the order `INFO` < `LOW` < `MEDIUM` < `HIGH` <
  `CRITICAL`. Supplying both is a 400.
- **`branch_id`**: the branch the event is **about**, and nothing else. It comes ONLY from
  `AuditCommand.branchId`; the audit adapter never reads a branch from the ambient
  `RequestContexts` (#186), so the caller's selected branch is never stamped. A row about one
  branch carries that branch even when the caller has another branch selected: its lifecycle,
  amendment or refusal (a refused branch approval's `DENIED` row, #251, names the target branch),
  its assignments, the head office draft, a branch-scoped role assignment, and each assignment a
  user deactivation or organisation deprovisioning revokes that is a branch assignment or a
  branch-scoped role; a journal reversal (the original journal's branch); a control-account
  reconciliation resolve (the reconciliation run's branch); and a daily-balance rebuild that names
  one branch (a rebuild of every branch carries none). Rows about a branch name it, so a
  branch-scoped `branch_id` search finds them.
  Every other row carries none: user, membership, organisation settings or lifecycle, tenant-scoped
  role, business date, external dispatch, and a tenant-scoped role assignment revoked by either
  cascade. A manual journal created without an explicit branch takes the caller's selected branch
  as its own branch, so its audit rows carry that branch; the writer itself still never stamps the
  caller's context. Rows written before #186 may carry the selected branch, stamped by the old
  request-context fallback; they are not rewritten, so a `branch_id` search can return a tenant-wide
  row from that period. Known limit: the prior-period posting authority audit written by
  `PostingPeriodResolver` carries no branch, because its resource is the fiscal period. The filter
  only narrows: `audit.view` is checked tenant-wide, as before, so no caller sees a row it could not
  already read.
- **`action_prefix`**: a literal prefix of `action` (LIKE wildcards escaped), 2 to 64 characters.
  It is the event-type filter, because `event_type` is the action (rows from before #187 keep the
  resource type in `event_type`, and filtering on `action` finds them too). `action` stays exact.
- **`q`**: a case-insensitive substring of `action`, `entity_type` or `reason` (the text columns
  the row has), 3 to 64 characters, wildcards escaped, bound as a parameter. It cannot use a
  B-tree index, so it **requires `occurred_from` no more than 31 days before `occurred_to` (or
  now)**, a window that bounds its cost on the tenant's `(organisation_id, event_time)` index. No
  trigram index.
- **`actor_type`**: `USER` or `SYSTEM` (`AuditActorType`), the two values the application
  writes, so system actors are findable. **`actor_subject`**: exact match on
  `actor_external_subject`, 1 to 255 characters.
- **`subject_user_id`** (#186): exact match on `audit_event.subject_user_id`, the user the event is
  about (see [the subject user](#the-subject-user-186)), a UUID bound like `actor_id`. It is
  accepted on the tenant route and on both platform routes, because it reveals no withheld
  field. A malformed value is a 400 naming `subject_user_id`. Rows written before `V29` keep a
  `NULL` subject and are not found by it.
- **`sort_dir`**: `ASC` or `DESC` on `event_time`, with the id as tie-break; `DESC` by default.
- **No side channel.** There is no filter over `before_jsonb`, `after_jsonb`, `metadata_jsonb`,
  `user_agent` or `ip_address`, and the platform searches refuse `actor_subject` with a 400,
  because their pages withhold `actor_external_subject` and a filter would reveal it one guess at
  a time.

Index support (`V25`-`V28` and `V30`, one migration per index): the selective filters (`branch_id`,
`actor_subject`, `subject_user_id`, an `outcome` other than `SUCCESS`, a severity above `INFO`) each
have a partial index after `organisation_id`, so their page count is an index-only scan of the
matching rows rather than a pass over the tenant's log. `actor_type`, `action_prefix`, `SUCCESS` and
`INFO` match too much of the log for an index to help and use `idx_audit_event_organisation_time`.
`JooqAuditEventQueries` renders the outcome and severity values as SQL literals, so the planner can
prove the partial predicates in a cached generic plan as well. `V30` (#186) adds the subject-user
index on `(organisation_id, subject_user_id, event_time DESC)`, partial on `subject_user_id IS NOT
NULL`, for the subject user the event is about. The subject filter's equality is a strict operator,
so the planner proves the partial predicate from it in a cached generic plan as well
(`AuditQueryPlanTests`). A row written before `V29` has no subject and is not in it; see the [audit
index migrations](../operations/audit-index-migrations.md) cut-over.

The five indexes are built `CONCURRENTLY` by non-transactional migrations (owner ruling), so
deploying them never blocks the audited writes of the running instances; the convention and the
recovery from a failed build are in
[audit index migrations](../operations/audit-index-migrations.md).
