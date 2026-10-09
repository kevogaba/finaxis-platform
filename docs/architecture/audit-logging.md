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

Do not persist every normal successful `GET` request as an audit event.

## Event Shape

The common audit foundation records:

- actor type and actor ID;
- tenant/organisation ID and branch ID;
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

## Current Adapter

`AuditService` writes to an `AuditEventRepository` port. `JooqAuditEventRepository` is the durable
adapter, backed by the append-only `audit_event` table; the logging-only repository in
`AuditConfiguration` is a `@ConditionalOnMissingBean` fallback used only if no durable adapter is
registered. Domain modules call the application-level audit service directly — that is the only
mechanism; there is no annotation-driven alternative.

Audit rows are also a **control input**, not only evidence: branch approval refuses anyone with a
successful `branch.update` event on the branch (`lifecycle.approver_is_branch_modifier`, ADR 0028).
Any future audit retention or purge job (the `audit_retention_days` setting) must therefore exclude
the `branch.update` events of branches that are not `ACTIVE` or terminal, or the rule fails open. A
durable column on `branch` is the long-term alternative. The same holds for tenants (#221):
approving or rejecting a pending tenant refuses anyone with a successful `organisation.amend_draft`
event on it (`lifecycle.approver_is_tenant_modifier`), so a purge must also keep those events of
every tenant that is not `ACTIVE` or terminal.

## Request Correlation

`HttpAccessLogFilter` preserves an inbound `X-Request-Id` of 8 to 64 characters of
`[A-Za-z0-9._-]` or generates one (a rejected value is never logged or stored). It returns the
header to clients and stores `requestId` in MDC for request logs. `ActiveOrganisationContextFilter`
separately installs `RequestContexts`, which carries tenant/branch/actor/correlation/user-agent
and the client address for the audit adapter to fall back on when a caller does not supply them
explicitly.

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

## Reading the log

Audit events are read through `GET /api/v1/tenant/audit-events` (tenant users, active tenant
only) and `GET /api/v1/platform/audit-events` plus
`GET /api/v1/platform/tenants/{tenant_id}/audit-events` (platform operators: the PLATFORM
organisation's log and any tenant's). A page item and a detail response have the same fields
under the same names. Both reuse `audit.view`; the platform routes require it in
the PLATFORM organisation. See
[audit logging](../security/audit-logging.md#rest-read-endpoints-and-the-platform-permission-model).

### Search filters (#183)

The three searches share one filter set, parsed by `AuditSearchParameters` and bounded by
`AuditEventFilter.requireValid` in `AuditQueryService` (a bad value is a 400 naming the
parameter, never a 500). The owner delegated these defaults to the builder; they are the design:

- **`outcome`**: the closed set `SUCCESS`, `FAILURE`, `DENIED` (`AuditOutcome`), exact match.
- **`severity` and `min_severity`**: both, as `AuditSeverity`; `severity` is exact and
  `min_severity` is that value or above in the order `INFO` < `LOW` < `MEDIUM` < `HIGH` <
  `CRITICAL`. Supplying both is a 400.
- **`branch_id`**: the row's branch. It only narrows: `audit.view` is checked tenant-wide, as
  before, so no caller sees a row it could not already read.
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
- **`sort_dir`**: `ASC` or `DESC` on `event_time`, with the id as tie-break; `DESC` by default.
- **No side channel.** There is no filter over `before_jsonb`, `after_jsonb`, `metadata_jsonb`,
  `user_agent` or `ip_address`, and the platform searches refuse `actor_subject` with a 400,
  because their pages withhold `actor_external_subject` and a filter would reveal it one guess at
  a time.

Index support (`V25`-`V28`, one migration per index): the selective filters (`branch_id`,
`actor_subject`, an `outcome` other than `SUCCESS`, a severity above `INFO`) each have a partial
index after `organisation_id`, so their page count is an index-only scan of the matching rows rather
than a pass over the tenant's log. `actor_type`, `action_prefix`, `SUCCESS` and `INFO` match too
much of the log for an index to help and use `idx_audit_event_organisation_time`.
`JooqAuditEventQueries` renders the outcome and severity values as SQL literals, so the planner can
prove the partial predicates in a cached generic plan as well.

The four indexes are built `CONCURRENTLY` by non-transactional migrations (owner ruling), so
deploying them never blocks the audited writes of the running instances; the convention and the
recovery from a failed build are in
[audit index migrations](../operations/audit-index-migrations.md).
