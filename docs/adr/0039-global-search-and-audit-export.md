# ADR 0039: Tenant Global Search And Audit CSV Export

## Status

Accepted. This is the **design record** for issue #167, written before the code it describes:
the search (`GET /api/v1/tenant/search`) and the export
(`GET /api/v1/tenant/audit-events/export`) are implemented by two later changes that read this
record and decide nothing further. Nothing here is implemented by the change that adds it, which
is documentation only: no code, no test, no migration.

Date: 2026-10-25

Builds on [ADR 0003](0003-api-governance-rate-limiting-versioning-pagination.md) (versioned,
paginated, rate-limited API), [ADR 0030](0030-mutation-permission-implies-view-permission.md) (a
mutation implies its view; reads are gated; the catalogue's `kind`, `grant_scope` and view
requirements), [ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md) (what an audit
row is about) and [ADR 0035](0035-role-archive-and-assignment-lock.md) (an archived role is hidden
from the default role list). It supersedes nothing.

## Context

Issue #167 asks for a global search and for export endpoints, and says a design note must come
first. What exists today, checked against the code:

- **No `/search` route and no export endpoint.** Every resource has its own `q` filter: branches
  (`BranchController`), roles (`RoleController`), the permission catalogue, tenant users
  (`TenantUserController`), memberships (`MembershipController`), and the platform user and tenant
  lists. A client that wants "everything called Nairobi" makes one call per resource and merges
  them.
- **Those `q` filters do not escape LIKE wildcards.** The users, memberships, roles and branches
  stores build `"%$q%"` and pass it to `likeIgnoreCase`
  (`JooqIamAdministrationQueries`, `JooqFoundationQueryStore`), so a `%` or `_` typed by a caller
  is a wildcard there. The audit `q` is different: `containsIgnoreCase` escapes them
  (`JooqAuditEventQueries.textCondition`).
- **The audit text search is already bounded.** `q` is 3 to 64 characters and requires
  `occurred_from` and a window of at most 31 days (`AuditEventFilter.requireValid`,
  `AUDIT_TEXT_SEARCH_MAX_WINDOW`; #183, #249, #269), because it cannot use an index.
- **The tenant audit route is `GET /api/v1/tenant/audit-events`** (`ApiPaths.AUDIT_EVENTS`
  = `$TENANT/audit-events`, `AuditEventController`), gated by `audit.view`. Its sibling `GET
  /{event_id}` takes a UUID path variable.
- **`audit.view` is not coupled to a mutation by the audit guard.** `AuditPermissionGuardAdapter`
  calls `AuthorizationService.requirePermission`, which asks for the one code named. The
  "mutation implies its view" check (ADR 0030, decision 4) is `requirePermissionWithViews`, used by
  the lifecycle and accounting guard adapters, not by this one.
- **`accounting_report.export` exists but nothing checks it.** `V5` seeded it (`MUTATION`,
  `TENANT`) and `V22` pairs it with `accounting_report.view`; no route uses it, and
  `docs/security/authorization-model.md` withholds it from `TENANT_AUDITOR` until an export route
  exists. Accounting has no REST adapters yet (#52).
- **Rate limiting is first-match path rules.** `RateLimitKeyResolver` picks the first rule in
  `finaxis.rate-limit.paths.rules` whose method and path match; `RateLimitFilter` answers 429
  `rate_limit_policy_unavailable` only when **no** rule matches. `GET /api/v1/**` matches both new
  routes today, so both would already run under `tenant-read` (600 a minute). The policy set is a
  closed enum (`RateLimitPolicyId`), and `RateLimitProperties` refuses to start unless the
  configured policies are exactly its entries.
- **Module direction.** `iam` may depend on `lifecycle::query`; `lifecycle` may not depend on
  `iam` (`iam/package-info.java`). Users, roles and memberships are read by `IamQueryService`,
  branches by `FoundationQueryService`; all four search methods are already `@GatedRead`.
- **`ReadGateRules`** (ADR 0030, decision 6): every GET handler calls an application bean only
  through a `@GatedRead` method, and a marked method asks a permission guard (or a marked
  delegate) before its first store call.

## Decision

### Scope

1. **Tenant directory and tenant audit now; nothing else.**
   - Search covers four tenant types: users, branches, roles, memberships.
   - Export covers the tenant audit log.
   - **Ledger, sub-ledger and every other accounting export is deferred** until accounting REST
     exists (#52). `accounting_report.export` stays unused and stays withheld from
     `TENANT_AUDITOR`, as the authorization model already says.
   - **Platform-level search and export (`/api/v1/platform/...`) are out of scope.** A platform
     operator uses the existing platform lists.

### Global search

2. **Route and placement: `GET /api/v1/tenant/search?q=<text>`, in the `iam` module.** One
   fan-out read. The reason it is `iam` and not `lifecycle` is the module rule above: the search
   reads branches (lifecycle) as well as users, roles and memberships (iam), and only `iam` may
   depend on the other. A new `ApiPaths` constant (`$TENANT/search`), a controller in
   `iam.adapter.inbound.web` and a `*QueryService` in `iam.application.query` (so `ReadGateRules`
   recognises it as a query service) carry it; the class names are the implementer's. The caller is
   `CallerContextResolver.getTenantCaller()`, so the organisation is always the active context;
   there is no organisation parameter. The route carries no `@PreAuthorize`: no single authority
   gates it, so, as for the branch reads, the application-layer checks are its only authorisation
   and a coarse gate must not be added.
3. **Request: `q` only.** `q` is required, trimmed, and 2 to 64 characters after trimming; a
   missing, blank or out-of-range value is a 400 `invalid_parameter` naming `q`
   (`InvalidPageRequestException`, as the audit `q` bounds are). The handler declares the
   parameter `required = false` and the service validates it, because a `required = true`
   parameter would answer with Spring's own missing-parameter error and not that problem.
   **`q` is validated before the fan-out, so a bad `q` is a 400 even for a caller who may view
   none of the types**: which types are viewable is decided inside each delegate (point 5), and
   deciding it first would need the service to hold its own copy of the four view rules, or to
   run reads before validating. The bounds carry no tenant data and are published in the OpenAPI
   document. (The audit routes check permission first because theirs is one code.) The minimum is
   2, not the audit
   filter's 3, because directory values are short (a branch code such as `HQ`) and the tables
   searched are small and tenant-bounded, unlike the audit log. There is no `page`, `size` or sort
   parameter and none is documented.
4. **Response: one object, one bounded page per type, no cross-type pagination.**
   `{users: [...], branches: [...], roles: [...], memberships: [...]}`. Each array holds **at most
   10 hits**, in the order the reused per-resource query already returns by default (no relevance
   ranking). The response carries **no counts** and no `has_more`: 10 hits means "10 shown", and
   a caller who wants more uses that resource's own list with the same `q`. An item is a small
   summary built only from fields the per-resource summaries already expose (`UserInTenantSummary`,
   `BranchSummary`, `RoleSummary`, `MembershipSummary`); no field is invented:

   | Type | `id` | `label` | `status` | Secondary field |
   | --- | --- | --- | --- | --- |
   | `users` | the user id | `display_name` | `user_status` | `email` |
   | `branches` | the branch id | `branch_name` | `status` | `branch_code` |
   | `roles` | the role id | `role_name` | `status` | `role_code` |
   | `memberships` | the membership id | `display_name` | `membership_status` | `membership_type` |

   The secondary field's JSON name is the one in the last column (snake_case). A user and a
   membership of the same person are two hits with two different ids; both are returned when the
   caller may see both types.
5. **A type the caller cannot view is omitted, not empty.** Each type is gated by the permission
   its per-resource list already uses, at the scope that list already applies:

   | Type | Permission | Scope rule (unchanged, from the reused query) |
   | --- | --- | --- |
   | `users` | `user.view` | tenant-wide |
   | `branches` | `branch.view` | target-aware: branch visibility, restricted in the store query |
   | `roles` | `role.view` | tenant-wide; the default list rule hides `ARCHIVED` (ADR 0035) |
   | `memberships` | `membership.view` | tenant-wide |

   - **Omission.** A type the caller may not view has **no key** in the response, so a caller
     learns nothing it did not already know (its own permissions are in `/auth/me`). An empty
     array always means "viewable, no match".
   - **Branches respect branch visibility.** A branch-scoped caller sees only the branches it
     holds `branch.view` on while they are `ACTIVE` (#242), exactly as `GET /branches` does, because
     the branch type is the existing `FoundationQueryService.searchBranches`, which restricts the
     store query.
   - **At least one type must be viewable.** A caller who may view none of the four gets the named
     403 for the first code in the fixed order users, branches, roles, memberships, which is always
     `user.view`: `Missing permission: user.view.`. The refusal is raised, and audited as
     `api.access_denied`, like every other refusal of a gated read; the omitted types of a
     caller who may view some write no row.
   - **How omission is decided.** The fan-out calls each type's existing gated query and treats a
     `ForbiddenOperationException` from it as "omit this type". That is the base class of
     `MissingPermissionException` and also what `BranchVisibility.Branches.requireListRestriction`
     throws, unnamed, for a branch-scoped caller who holds the code nowhere, so catching only the
     named subclass would turn that caller's request into a 403. The catch wraps the delegate call
     only; every other exception propagates. When all four refuse, the users refusal is rethrown.
     The permission decision therefore stays in the one place that makes it for each type; the
     search holds no copy of a rule.
6. **Matching: the same columns, case-insensitive, wildcards escaped, no new index.** Each type
   searches the columns its per-resource `q` already searches, through that same query:

   | Type | Columns (`ILIKE '%<q>%'`) |
   | --- | --- |
   | `users` | `user_account.username`, `email`, `display_name` |
   | `branches` | `branch.branch_code`, `branch_name` |
   | `roles` | `role.role_code`, `role_name` |
   | `memberships` | `user_account.username`, `email`, `display_name` |

   - **Escaping.** Because those queries pass the text unescaped, the search service escapes
     `\`, `%` and `_` with a backslash (PostgreSQL's default LIKE escape) before delegating, so
     the caller's text matches literally. The per-resource `q` filters are **not** changed by this
     decision; their wildcard behaviour is a separate matter. The search change proves on
     PostgreSQL that `%` and `_` match only themselves.
   - **Bound.** Each delegate call is page 0, size 10, tenant-filtered by the active
     organisation, so no unbounded read exists. The reused queries also run their `count(*)`, so
     each type costs one filtered scan of one tenant's rows of a small table; the count is not
     returned. If measurement ever shows a problem, a limit-only variant is a later change.
   - **No index, no trigram, no full-text.** At a 10-hit cap and over tenant-sized tables an
     unselective admin lookup does not justify one (see "Alternatives rejected").
   - **`q` is never logged** or put in a metric or span: it may be an email address.
7. **Gating and the build rules.** The controller calls **one** aggregate entry method of the
   search service, which is itself `@GatedRead` and takes the caller. It validates `q`, then calls
   the four per-type fan-out methods, each `@GatedRead`, each taking the caller and delegating to
   the existing `@GatedRead` method of its type (`IamQueryService.searchUsers`,
   `searchMemberships`, `searchRoles`; `FoundationQueryService.searchBranches`), so each asks a
   marked delegate before its first store call, which is what `ReadGateRules` requires of a marked
   method; the entry method's own first store call is one of those marked calls. No allowlist entry
   is added, there is no unmarked GET, and tenant filtering stays from the active context.
8. **No new permission code, no migration, no audit row, no new rate-limit policy.** The route is
   an ordinary GET: it is not audited (`docs/architecture/audit-logging.md`), and it runs under
   the existing `tenant-read` policy through the existing `GET /api/v1/**` rule.

### Audit export

9. **Route: `GET /api/v1/tenant/audit-events/export`, in the existing `AuditEventController`.**
   It is the `ApiPaths.AUDIT_EVENTS` base path plus `/export`. The literal segment wins over
   `/{event_id}`, and `export` is not a UUID, so the two cannot be confused. A GET with a side
   effect only on the audit log, it is not `@IdempotentMutation`. Like the branch reads it
   carries **no `@PreAuthorize`**: the service check below is the only authorisation, so a refusal
   always names the missing code. The OpenAPI `403` text, which the generated document derives from
   `@PreAuthorize` for other routes, is therefore written by hand and names `audit.export` and
   `audit.view`.
10. **Response.** `200` with `Content-Type: text/csv; charset=UTF-8`, `Content-Disposition:
    attachment; filename="audit-events-<dd-MM-yyyy>.csv"` (the date of the request in UTC) and
    `Cache-Control: no-store`, streamed (`StreamingResponseBody` or the equivalent). RFC 4180: a
    header row, then one row per event, records separated by CRLF, no byte-order mark.
11. **Filters: the same as the audit search, and the same code.** The route accepts every filter
    of `GET /api/v1/tenant/audit-events` (`entity_type`, `entity_id`, `actor_id`,
    `subject_user_id`, `action`, `action_prefix`, `occurred_from`, `occurred_to`, `outcome`,
    `severity`, `min_severity`, `branch_id`, `q`, `actor_type`, `actor_subject`, `sort_dir`) and
    none of `page` and `size`. It parses them with `AuditSearchParameters`, builds an
    `AuditEventFilter` and validates it with `requireValid`, then reads through
    `JooqAuditEventQueries`' `searchCondition`, so a filter means the same thing in both places.
    Two bounds are added for an export, whatever else is filtered:
    - **`occurred_from` is required** (400 `invalid_parameter` naming `occurred_from`).
    - **The window, `occurred_from` to `occurred_to` (or now), is at most 31 days**, the bound
      `q` already has (`AUDIT_TEXT_SEARCH_MAX_WINDOW`; the implementer may give the constant a
      neutral name), and `occurred_from` must not be after the end. When `occurred_to` is absent
      the service **fixes it at the request instant**, so the set being counted and the set being
      streamed have the same upper edge.
12. **Hard cap: 10,000 rows, refused, never truncated.** Before any byte is written the service
    counts the matches with a bounded count (stop at 10,001). When more than 10,000 rows match, the
    request is refused with a **400 problem `audit.export_too_large`**, raised as
    `InvalidRequestException("audit.export_too_large", ...)` (`ApiProblemFactory` maps that class
    to 400; `InvalidOperationException` would answer 422). Its message tells the caller to narrow
    the window or add filters and includes no count. Because the check runs
    before the response is committed, the refusal is a real status code, not a broken stream. The
    stream's own read is also limited to 10,000 rows. A row committed late with an event time inside
    the window can make the streamed set differ from the counted one by that row; the limit means
    the cap still holds, and the audit row (point 17) records the rows actually written.
13. **A bounded, tenant-filtered keyset read.** The stream reads in batches of 1,000 by keyset on
    `(event_time, id)` in the requested direction (the order the search already uses), each batch
    one short tenant-filtered statement through a new method on the `AuditEventQueries` port that
    selects only the export columns (point 14). No cursor or transaction is held open across the
    response, and no result set larger than one batch is in memory. The statement is planned
    against `idx_audit_event_organisation_time` and `AuditQueryPlanTests` gains the keyset
    statement. **Everything the stream needs is captured on the request thread before it starts**,
    as plain values: the organisation, the filter, the actor id, the request id, and the source IP
    and user agent that `AuditCommand` carries and that are read from the request context. The
    thread that writes the body (and the audit row, point 17) reads no request-scoped state.
    **Executor and timeout.** Virtual threads are already enabled
    (`spring.threads.virtual.enabled: true`), so the body runs on Boot's virtual-thread
    application executor, and nothing configures MVC async today. 6d sets the async request
    timeout explicitly (`spring.mvc.async.request-timeout`), long enough for 10,000 rows and short
    enough to free a stalled client, and adds no bespoke executor unless measurement says to.
14. **Columns: a fixed, documented list, and personal data stays out.** In this order, with
    the JSON API's snake_case names:

    | Column | Source | Format |
    | --- | --- | --- |
    | `occurred_at` | `event_time` | as the JSON API serialises the `Instant` (see below) |
    | `action` | `action` | text |
    | `outcome` | `outcome` | `SUCCESS`, `FAILURE` or `DENIED` |
    | `severity` | `severity` | `INFO` to `CRITICAL` |
    | `actor_type` | `actor_type` | `USER` or `SYSTEM` |
    | `actor_user_id` | `actor_user_id` | UUID, empty when none |
    | `branch_id` | `branch_id` | UUID, empty when none |
    | `subject_user_id` | `subject_user_id` | UUID, empty when none |
    | `entity_type` | `entity_type` | text |
    | `entity_id` | `entity_id` | UUID, empty when none |
    | `request_id` | `request_id` | text, empty when none |

    `occurred_at` is written with the same serializer as the JSON API's `occurred_at` (an
    ISO-8601 UTC instant such as `2026-07-25T08:12:00Z`, per `docs/api/foundation-api.md`), not a
    formatter of the export's own, so the `Z` suffix and the fractional-second precision are
    whatever the API emits; a test asserts that the export cell equals the search's `occurred_at`
    for the same event.

    **Excluded, by construction:** `ip_address`, `user_agent`, `actor_external_subject`, the
    before, after and metadata JSON, and with them `reason` and `correlation_id` (free text or
    identifiers a client can influence). The excluded columns are not selected at all, not selected
    and dropped. The list is closed: a column is added only by a later decision. The identifiers
    that remain are pseudonymous UUIDs, with no name or email.
15. **CSV encoding and formula-injection defence.** One rule for every cell, applied in this
    order: (a) a cell whose first character is `=`, `+`, `-`, `@`, TAB or CR is prefixed with a
    single quote `'`; (b) a cell containing a comma, a double quote, CR or LF is wrapped in double
    quotes with each inner double quote doubled (RFC 4180); (c) null is an empty cell. `request_id`
    is the practical vector, since it can originate from a caller's `X-Request-Id`; the prefix is a
    lossy change a programmatic consumer must know, and the documentation says so.
16. **Permission: a new code `audit.export`, in `V34`.**
    - **Catalogue row.** `MUTATION`, `grant_scope = 'TENANT'`, risk `HIGH`, module `audit`,
      status `ACTIVE`, id `40000000-0000-0000-0000-000000000068` (the next free id after `…067`,
      `role.archive`), with a `permission_view_requirement` row naming `audit.view`.
      `V22` made `kind` and `grant_scope` `NOT NULL` and `PermissionCatalogueMetadataTests` pairs
      every mutation with a view, so `V34` writes all three in the same file.
    - **Grants.** `PLATFORM_SUPER_ADMIN`, every tenant's seeded `TENANT_ADMIN` and the bootstrap
      `local-admin`, copying `V23`'s steps 1a and 2 as `V33` did (the rule that a later migration
      adding an `ACTIVE` permission grants it to the administrators it belongs to). Nobody else:
      **`TENANT_AUDITOR` holds `audit.view` but not `audit.export`** until a tenant administrator
      grants it, and no non-admin default bundle gets it. `V34` asserts its pre- and
      post-conditions in-file and is idempotent (`ON CONFLICT DO NOTHING`), as `V32` and `V33` are.
    - **Why a new code.** Reusing `audit.view` is rejected: every viewer, including a read-only
      auditor, could then bulk-extract the log. Reusing `accounting_report.export` is rejected:
      it is the accounting reporting export, withheld from auditors, and tying two unrelated
      exports to one code would couple their grants.
    - **Enforcement.** A mutation implies its view (ADR 0030): the check asks `audit.export` first
      and then `audit.view`, and a refusal is `MissingPermissionException` (403 `forbidden`,
      `Missing permission: <first missing code>.`) raised before the count, the read or any
      write. To keep that central, as the lifecycle and accounting adapters do, 6d switches
      `AuditPermissionGuardAdapter` to `AuthorizationService.requirePermissionWithViews`; for
      `audit.view`, a view with no requirements, nothing changes. The export method of
      `AuditQueryService` is `@GatedRead`, takes the actor and organisation, and asks the guard
      before its first store call.
17. **One audit row per export request, never the contents.** Through `AuditService`, action
    `audit.export`, resource type `AUDIT_EVENT` with no resource id, the actor, the active
    organisation, the request id, source IP and user agent (captured as in point 13), and
    metadata of exactly this shape:

    ```
    metadata = {
      filters: { <snake_case parameter>: <value as supplied>, ... },
      row_count: <integer>,
      error_class: "<simple exception class>"   // FAILURE rows from an abort only
    }
    ```

    - **`filters`** holds only the parameters the caller sent, under their snake_case names
      (`occurred_from`, `q`, `action_prefix`, ...), values as supplied (timestamps as sent, UUIDs
      as text); it never holds `page` or `size` (not accepted), and a pinned `occurred_to` is not
      added. It never holds a cell, an event id or any text of an exported row.
    - **`row_count`** is always present: the rows written to the response.
    - **`SUCCESS`**: written when the last row has been flushed, severity `MEDIUM` (the policy
      would give `INFO` for this verb, and a bulk extraction is not informational),
      `row_count` = rows written, no `error_class`.
    - **`FAILURE`, abort**: the stream fails after the response began (a client disconnect or a
      read error); `row_count` = rows written so far, `error_class` = the exception's simple class
      name, never its message (the V24 rule), severity `HIGH` (the policy's value for a
      `FAILURE`, passed explicitly so the row does not depend on it).
    - **`FAILURE`, too large** (`audit.export_too_large`): written with `recordIndependently` so
      the refusal cannot roll it back; `row_count` = `0` (nothing was written), no `error_class`,
      severity `HIGH`; `filters` shows what was asked for.
    - **If the audit write itself fails after the bytes were sent**, the export is not undone and
      nothing is retried: the failure is logged as one message-free `WARN` (exception class and
      frames, no message, the V24 rule) and the response completes as it would have. A write that
      fails before the response begins (the too-large row) must not change the answer either:
      the 400 is still returned.
    - **A 403 writes no `audit.export` row.** The refusal already writes `api.access_denied` /
      `DENIED` through `DeniedAccessAuditor`; a second row would repeat it. A 400 for a bad filter
      or a missing `occurred_from` writes none either, as no other validation failure does.
    - **Why this route audits when GET reads do not.** `docs/architecture/audit-logging.md` says
      not to persist every ordinary successful GET, and that policy **does not change**: a search
      or a page of audit events still writes nothing. This route is the exception because it is a
      bulk extraction of the audit log itself, the one read whose purpose is to move data out of
      the system, and the question "who exported what, when" has no other answer.
    - The export's own row is written after the stream, so it never appears in the file it
      describes; it is found by later searches (`action=audit.export`).
18. **Rate limiting: the export has its own configurable policy.** A new `tenant-export`
    policy: `RateLimitPolicyId.TENANT_EXPORT("tenant-export")`, its entry in `defaultPolicies()`
    (capacity 5, refill 5 per 1 minute) and in `application.yaml` under
    `finaxis.rate-limit.policies`, with the project's pattern of environment overrides
    (`FINAXIS_RATE_LIMIT_TENANT_EXPORT_CAPACITY`, `..._REFILL_TOKENS`, `..._REFILL_PERIOD`; the
    values above are only the defaults). The key is the existing per-user and tenant one
    (`rate-limit:tenant-export:auth:{tenantId}:{userId}`, `docs/architecture/rate-limiting.md`).
    A path rule `GET /api/v1/tenant/audit-events/export` is added **before** `GET /api/v1/**` in
    both `defaultRateLimitPathRules()` and the YAML, because the first match wins; the policy set
    is a closed enum, so adding the enum entry is what lets `RateLimitProperties` start with it.
    The reason is to throttle bulk extraction tighter than ordinary reads: without a rule of its
    own the route would already be covered by `tenant-read` (so no
    `rate_limit_policy_unavailable`), at 600 a minute. The limiter's fail-open default
    (`fail-open: true`) applies to this policy as to every other; a per-policy fail-closed mode is
    not offered here, and during a Redis outage the 31-day window and the 10,000-row cap remain
    the bound. The search route reuses `tenant-read` and gets no policy.
19. **Synchronous streaming; CSV only.** The response is produced while the request is open: no
    JobRunr job, no generated file, no storage, no download protocol, because the row cap keeps
    the work bounded to one short request. The format is CSV only; there is no XLSX and no new
    dependency.

### Test obligations

The two implementing changes carry these tests; they collect what the points above require.

**6c, search (`GlobalSearchIntegrationTests` and unit tests):**

- Each type returns its matches with the fields of point 4, and never more than 10 hits.
- `%` and `_` in `q` match only themselves, on PostgreSQL (point 6); matching is case-insensitive.
- `q` missing, blank, 1 character or 65 characters is a 400 naming `q`, and a bad `q` is a 400
  even for a caller who may view no type (point 3).
- A type the caller cannot view has no key; a caller with a viewable type and an empty match gets
  `[]` for it; a caller who may view none gets 403 `Missing permission: user.view.`.
- A branch-scoped caller gets only the branches it holds `branch.view` on while `ACTIVE`; a
  branch-scoped caller who holds it nowhere has no `branches` key, not a 403.
- A non-refusal exception from a delegate propagates and is not swallowed.
- A tenant's search never returns another tenant's rows; an `ARCHIVED` role is not a hit.
- `PermissionFreeReadRuleTests`, Spring Modulith verification and ArchUnit stay green, with no
  allowlist entry added.
- The route runs under `tenant-read` and writes no audit row.

**6d, export (`AuditExportIntegrationTests` and unit tests):**

- The CSV has the header and the 11 columns of point 14 in order; the excluded columns appear
  nowhere; `occurred_at` equals the search's for the same event; the headers are as in point 10.
- The same filters select the same events as the search; a missing `occurred_from`, a window over
  31 days and `occurred_from` after the end are 400s naming `occurred_from`.
- **The cap at the edge:** exactly 10,000 matching rows export in full; 10,001 is the 400
  `audit.export_too_large`, with no byte of CSV written and an `audit.export` `FAILURE` row with
  `row_count` 0.
- Formula injection: cells starting `=`, `+`, `-`, `@`, TAB and CR are prefixed with `'`; RFC 4180
  quoting of commas, quotes and newlines round-trips through a CSV parser.
- A caller with `audit.view` but not `audit.export` (`TENANT_AUDITOR` as seeded) gets 403
  `Missing permission: audit.export.`, before any count; a caller with `audit.export` but not
  `audit.view` gets `Missing permission: audit.view.`; each writes `api.access_denied` and no
  `audit.export` row.
- One `audit.export` row per successful export holds the metadata shape of point 17 (filters, row
  count) and no cell; an abort writes the `FAILURE` row with `error_class` and no message; a failing
  audit write after the bytes were sent is a message-free `WARN` and does not change the response.
- The keyset statement is added to `AuditQueryPlanTests`; a batch is never larger than 1,000 rows
  in memory.
- The rate limit applies: the export path resolves to `tenant-export` (first match, before
  `GET /api/v1/**`), the 429 carries the headers, and `RateLimitProperties` starts with the new
  enum entry.
- `V34`: the catalogue row, the view requirement, the grants and their idempotence; the three
  catalogue-size tests move from 84 to 85, `PermissionCatalogueMetadataTests` and the `V23` drift
  test pass, and `TENANT_AUDITOR` is unchanged.
- The OpenAPI `403` text names `audit.export` and `audit.view`, written by hand (point 9).

## Consequences

- A client gets one call for the directory and a bounded, auditable way to take a month of audit
  events out of the system, without a new table, a new index, a new dependency or a job.
- **`V34` is reserved for `audit.export`.** Adding it moves the live catalogue from 84 to 85
  codes: the three tests that hard-code the size (`PermissionCatalogueMetadataTests`,
  `FoundationSeedDataTests`, `JooqIamPermissionQueriesTests`) and the catalogue count in
  `CLAUDE.md` move by one again, in the export change, not in this one. The V23 drift test and
  `PermissionCatalogueMetadataTests` cover the new code.
- **`TENANT_AUDITOR` cannot export** until a tenant administrator grants `audit.export` (with
  `audit.view`, which it already holds). Tenant administrators and the platform super administrator
  can from the migration.
- The search never reports totals or a "more" flag; a UI that wants them uses the per-resource
  lists.
- Search text now has two behaviours in the codebase: the global search matches `%` and `_`
  literally and the per-resource `q` filters treat them as wildcards. Aligning the latter is a
  separate change.
- The 10,000-row cap, the 31-day window and the batch size are **the knobs**; a later ADR can
  change them, and a larger export would need the asynchronous design rejected below.
- Ledger and sub-ledger export remains tracked by #52, and issue #167 stays open for it.
- The implementing changes update `docs/api/foundation-api.md`, `docs/architecture/audit-logging.md`
  (the one exporting route that audits), `docs/architecture/rate-limiting.md`,
  `docs/security/authorization-model.md` (`audit.export`, `TENANT_AUDITOR`) and
  `scripts/local-smoke.sh`.

## Alternatives rejected

- **Per-resource `q` only.** It keeps N round trips and gives no cross-type view; the point of
  #167 is the single call.
- **Trigram or full-text indexes.** An unselective admin lookup, capped at 10 hits over
  tenant-sized tables, does not need one, and the audit log's own text search is bounded by a
  window instead of an index (V25-V28). An index per searched column would add write cost to
  tables that are written on every lifecycle change.
- **One page of results across all types** (a single `page` over a merged list). It needs a
  stable global order across four tables with different columns and permissions, and it would
  make the hit count of a type the caller may not view leak through the page boundaries.
  One bounded page per type is simpler and has no such oracle.
- **Asynchronous export through JobRunr.** It needs file storage, a download protocol with its own
  authorisation, expiry and clean-up, and a way to show progress. The 10,000-row cap makes the work
  small enough to stream in one request.
- **XLSX.** It needs a new dependency and a different escaping model for no gain over CSV that a
  spreadsheet opens.
- **Reusing `audit.view`** for the export: every read-only viewer could bulk-extract the log.
- **Reusing `accounting_report.export`:** it is the accounting reporting export, withheld from
  auditors, and a shared code would couple two unrelated grants.
- **Silent truncation at the cap.** A file that looks complete and is not is worse than a refusal
  that says to narrow the request.
- **Including the personal and free-text columns** (IP address, user agent, actor subject,
  before and after state, metadata, reason). An export leaves the system's access controls; the
  columns that can hold personal data or caller-supplied text stay behind them.
- **Searching types the caller cannot view and returning empty arrays.** An empty array would be
  indistinguishable from "no match" and would make a refused type look permitted; omission is the
  clear signal.

## Relationship to other ADRs

- **ADR 0003** set versioning, pagination and rate limiting; this adds one route that is not a
  page (the search, a fixed number of fixed-size pages) and one rate-limit policy.
- **ADR 0030** is the authority for the permission rules used here: the view requirement and
  `kind`/`grant_scope` of `audit.export`, the named 403, the `@GatedRead` marker on every fan-out
  method and on the export method.
- **ADR 0031** defines what an audit row is about; the export row is about the export request
  (`AUDIT_EVENT`, no resource id), not about the events in it.
- **ADR 0035** is why the role hits omit archived roles: the search reuses the default list.
- The audit filter semantics (`docs/architecture/audit-logging.md`, "Search filters") are reused
  unchanged; only the export's `occurred_from` and window bounds are added.

## Not covered

- Platform search and platform audit export (`/api/v1/platform/...`).
- Search over branch assignments, role assignments, the permission catalogue, settings or any
  accounting record; export of anything but the tenant audit log.
- Ledger, sub-ledger and `accounting_report.export` (#52).
- Changing the per-resource `q` wildcard behaviour.
- A per-policy fail-closed rate-limit mode.
