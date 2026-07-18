# Tenant Settings & Business Date / COB Foundation — Design

- **Date:** 2026-07-16
- **Status:** Approved (brainstorming)
- **Module:** `lifecycle` (extends existing services; no new top-level module)
- **Scope:** Setup-level tenant settings + business date / COB status foundation.
  **No accounting / financial day-close / EOD implementation** — status foundation only.

## 1. Context and prior art

`Organisation` **is** the tenant boundary in this codebase (`organisation.tenant_code`
is the natural key; `OrganisationStatus` doc reads "Lifecycle state for an organisation
tenant"). There is no separate `Tenant` entity and this slice does not introduce one —
"TenantSetting" is a command/query naming layer over the existing `organisation_setting`
table.

Substantial prior art already exists and is **extended, not replaced wholesale**:

- `organisation_setting` table — already effective-dated (close-row / insert-row
  versioning, unique on `(organisation_id, setting_key, effective_from)`), with
  `is_sensitive` and `value_type` columns. Adapter:
  `JooqOrganisationSettingsStore` (pg advisory-lock serialises concurrent writers).
- `business_date` table — already a singleton-per-tenant, optimistic-locked row with
  `current_business_date`, `current_cob_date`, `status`
  (`OPEN|CLOSING|CLOSED|ADVANCING`), `row_version`. Adapter: `JooqBusinessDateStore`.
- `OrganisationSettingsService` / `BusinessDateService` — existing simple, non-FSM
  mutation services audited via `@AuditedAction`. **Neither currently enforces any
  permission.** This slice adds permission enforcement.
- Audit: `AuditService.recordSettingsChange(...)` exists; `SensitiveDataRedactor`
  centralises redaction inside `AuditService.record(...)` and honours an explicit
  `Redacted(value)` wrapper. See ADR 0007.
- Events: `ExternalizedTransitionEvent` → Spring Modulith outbox → Namastack
  `RabbitOutboxRouting` (exchange chosen by event `target`) → thin `@RabbitListener`.
  See ADR 0004 / 0008. New externalized events need a matching route only when a
  consumer is added; emitting to the outbox does not.
- Permission catalogue seeded in Flyway (`V4`), global and immutable by code,
  `<module>.<verb>` convention. Already seeds `settings.update`, `business_date.view`,
  `business_date.advance` under module `settings`.

Confirmed absent: any REST controller / inbound adapter for settings or business date
(`lifecycle` has no inbound adapter yet — a documented, accepted follow-up).

## 2. Decisions (from brainstorming)

1. **Application layer only** — commands / queries / services / persistence / events /
   audit. **No REST controllers** in this slice (keeps the documented "lifecycle has no
   inbound adapter yet" posture). Services are the public port for now.
2. **Permissions: reuse + extend.** Keep `settings.update` for tenant-admin-mutable
   settings; add `tenant_setting.manage_platform` for platform-admin-only settings; add
   `business_date.reopen`, `cob.start`, `cob.complete`. Reuse `business_date.advance`,
   `business_date.view`. New codes seeded via a new Flyway migration.
3. **Deactivate = close current row, no replacement.** `DeactivateTenantSetting` sets
   `effective_to = now` on the currently-effective row and inserts nothing. Get/List
   then report the key as unset (falling back to a coded default if the catalog defines
   one). Fully reversible via `CreateOrUpdateTenantSetting`.
4. **COB = plain status column, no FSM.** `StartCob` / `CompleteCob` /
   `ReopenBusinessDate` validate and update `business_date.status` via `require()` +
   optimistic lock, in the same style as the existing `advance()`. The 3–4 state toggle
   is not promoted into the `common.transitions` FSM engine — foundation only.

## 3. Tenant setting catalog

A code-level registry (`lifecycle/domain/TenantSettingCatalog.kt`), analogous to the
permission catalogue: the authoritative list of allowed setting keys. Commands validate
against it; arbitrary keys are rejected. Each entry: `key`, `valueType`,
`sensitive: Boolean`, `platformAdminOnly: Boolean`, optional `defaultValue`.

| key | valueType | sensitive | platformAdminOnly |
|---|---|---|---|
| `default_timezone` | `TIMEZONE` | no | no |
| `base_currency` | `CURRENCY` | no | no |
| `require_maker_checker_for_user_invites` | `BOOLEAN` | no | no |
| `require_maker_checker_for_branch_creation` | `BOOLEAN` | no | no |
| `business_date_auto_advance_enabled` | `BOOLEAN` | no | no |
| `audit_retention_days` | `INT` | no | **yes** |

### Value types and validation

- `TIMEZONE` — must be a member of `java.time.ZoneId.getAvailableZoneIds()` (IANA zone
  ids, e.g. `Africa/Nairobi`). JVM three-letter abbreviations are **not** accepted.
- `CURRENCY` — must be a member of `java.util.Currency.getAvailableCurrencies()` mapped
  to ISO 4217 codes (e.g. `KES`, `USD`).
- `BOOLEAN` — `true` / `false`.
- `INT` — parseable non-negative integer.

Validation lives in the catalog (`validate(key, rawValue)`), returning the canonical
stored string form or throwing `IllegalArgumentException`.

### Sensitivity / redaction

No setting in the current catalog is naturally sensitive. The `sensitive` flag and its
redaction path remain a **real, tested mechanism** for future settings:

- When a catalog entry is `sensitive`, the service wraps its value in `Redacted(...)`
  before handing before/after maps to `AuditService`, so the audit trail records "this
  key changed" without leaking the value.
- The read side (`GetTenantSetting` / `ListTenantSettings`) also masks sensitive values
  (returns `***REDACTED***`), not just the audit path.
- Tested via a synthetic sensitive catalog entry in unit tests (not a real production
  key), so the guarantee holds when a real sensitive setting is later added.

## 4. Tenant settings service

`TenantSettingsService` (evolves the current `OrganisationSettingsService`;
single-key oriented to match the requested commands).

Commands (`lifecycle/application/TenantSettingCommands.kt`):

- `CreateOrUpdateTenantSettingCommand(organisationId, key, value, actorId, reason?)`
- `DeactivateTenantSettingCommand(organisationId, key, actorId, reason?)`

Queries:

- `GetTenantSettingQuery(organisationId, key)` → value + catalog metadata
- `ListTenantSettingsQuery(organisationId)` → all currently-effective settings + metadata

### `CreateOrUpdateTenantSetting` flow

1. Resolve catalog entry for `key`; reject unknown key.
2. Validate + canonicalise `value` via the catalog.
3. Guard: organisation lifecycle state must be `ACTIVE`
   (`require(lifecycleStore.lifecycleState(id) == ACTIVE)`).
4. Permission gate via `AuthorizationService.requirePermission(actorId, scopeOrgId, code)`:
   - normal setting → `code = "settings.update"`, `scopeOrgId = command.organisationId`
   - `platformAdminOnly` setting → `code = "tenant_setting.manage_platform"`,
     `scopeOrgId = PlatformOrganisation.ID`
5. Persist via existing close-row / insert-row versioning (store gains a single-key
   entry point; the `value_type` and `is_sensitive` columns are written from the catalog
   entry rather than hard-coded `STRING`/`false`).
6. Audit via `AuditService.recordSettingsChange(...)` with before/after; sensitive values
   wrapped in `Redacted(...)`.
7. Publish `ExternalizedTransitionEvent` (`target =
   finaxis.lifecycle.organisation.settings-updated`, `eventType = TenantSettingsUpdated`).

`DeactivateTenantSetting`: steps 1, 3, 4 as above; store closes the current row with no
replacement; audit records before = current value, after = `{key: null}`; same event
with `eventType = TenantSettingDeactivated`.

### Store port changes (`OrganisationSettingsStore`)

- `upsertSetting(organisationId, key, value, valueType, sensitive, actorId)` —
  single-key close-row/insert-row (generalises the existing `updateSettings` loop body;
  writes real `value_type` / `is_sensitive`).
- `deactivateSetting(organisationId, key, actorId)` — close current row, no insert.
- `currentSetting(organisationId, key)` / `currentSettings(organisationId)` — reads for
  the query side (returns value + `value_type` + `is_sensitive`).

## 5. Business date / COB service

`BusinessDateService` (extended). Business date is a singleton row per organisation.
Status model (existing column, `OPEN|CLOSING|CLOSED|ADVANCING`): this slice uses
`OPEN → CLOSING (StartCob) → CLOSED (CompleteCob) → OPEN (ReopenBusinessDate)`.
`ADVANCING` is left reserved for future auto-advance work and not driven here.

Commands (`lifecycle/application/BusinessDateCommands.kt`):

| command | permission | precondition | effect |
|---|---|---|---|
| `InitializeBusinessDate(orgId, initialBusinessDate, actorId)` | `business_date.advance` | org ACTIVE, no row exists | insert singleton, `status = OPEN` |
| `AdvanceBusinessDate(orgId, newBusinessDate, actorId, reason?)` | `business_date.advance` | org ACTIVE, `status = OPEN`, newDate > current | optimistic-locked advance |
| `StartCob(orgId, actorId, reason?)` | `cob.start` | org ACTIVE, `status = OPEN` | `status = CLOSING`, `current_cob_date = current_business_date` |
| `CompleteCob(orgId, actorId, reason?)` | `cob.complete` | org ACTIVE, `status = CLOSING` | `status = CLOSED` |
| `ReopenBusinessDate(orgId, actorId, reason?)` | `business_date.reopen` (no fallback) | org ACTIVE, `status = CLOSED` | `status = OPEN` |

Every command: ACTIVE guard, permission gate, optimistic-locked update
(`WHERE row_version = expected`, `check(updated)` on stale), append a history row (§6),
emit an `ExternalizedTransitionEvent`, and audit explicitly (explicit `AuditService`
calls rather than `@AuditedAction`, since these are permission-gated and their
before/after state is clearer to assemble in code than in SpEL).

### Events (targets / eventType)

| event | target | eventType |
|---|---|---|
| `BusinessDateInitialized` | `finaxis.lifecycle.organisation.business-date-initialized` | `BusinessDateInitialized` |
| `BusinessDateAdvanced` | `finaxis.lifecycle.organisation.business-date-advanced` | `BusinessDateAdvanced` |
| `CobStarted` | `finaxis.lifecycle.organisation.cob-started` | `CobStarted` |
| `CobCompleted` | `finaxis.lifecycle.organisation.cob-completed` | `CobCompleted` |

`ReopenBusinessDate` also emits (`...business-date-reopened` / `BusinessDateReopened`)
for completeness, though not in the required list. No RabbitMQ consumers are added in
this slice — emitting to the outbox is sufficient and does not require a route until a
listener exists.

### Queries

- `GetBusinessDateQuery(organisationId)` → current snapshot (permission `business_date.view`).
- `ListBusinessDateHistoryQuery(organisationId, page, size)` → paginated history
  (permission `business_date.view`). Uses the standard `common.web.pagination` types and
  is bounded (never unbounded), per API governance.

## 6. `business_date_history` table (new)

The current schema keeps only current-state columns on `business_date`; there is no
change log. Advancing/COB overwrites the singleton, so a dedicated append-only history
table backs `ListBusinessDateHistory`:

```
business_date_history (
  id UUID PK,
  organisation_id UUID NOT NULL REFERENCES organisation(id),
  event_type TEXT NOT NULL,      -- INITIALIZED|ADVANCED|COB_STARTED|COB_COMPLETED|REOPENED
  from_status TEXT,
  to_status TEXT NOT NULL,
  from_business_date DATE,
  to_business_date DATE NOT NULL,
  actor_id UUID,
  reason TEXT,
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  created_by UUID
)
```

Index on `(organisation_id, occurred_at DESC)`. Append-only (insert path only), mirroring
the audit-log posture — it is a domain read model, distinct from the cross-cutting
`audit_event` log.

## 7. Migration

One new Flyway migration (next `V<n>`), idempotent-friendly, adding:

1. Permission catalogue rows under module `settings`:
   `tenant_setting.manage_platform` (risk HIGH), `business_date.reopen` (risk CRITICAL),
   `cob.start` (risk HIGH), `cob.complete` (risk HIGH). Following the existing seed
   convention (stable UUID ids, module `settings`).
2. `business_date_history` table + index.

Also grants the new permissions to the seeded platform-admin role (matching how baseline
permissions are granted in the existing seed migrations).

## 8. Architecture decisions to document (in `tenant-settings.md`)

Explicitly record **what is deliberately NOT a tenant setting**, and why:

- **Keycloak owns authentication and SSO entirely.** No `keycloak_invite_enabled` /
  external-SSO settings in the application. This app is an OAuth2 resource server; it
  never manages credentials, invite-provider toggles, or external IdP configuration —
  that lives in Keycloak. External SSO is configured at the Keycloak realm level, not
  inside this application. (Consistent with CLAUDE.md's Keycloak boundary.)
- **`max_failed_login_policy_reference` removed** — lockout/failed-login policy is a
  Keycloak concern, not an application setting.
- **`application_invite_enabled` removed** — invite transport is governed by the
  Keycloak-owned flow, not a tenant toggle.
- **Branch assignment is a structural invariant, not a toggle.** Every operational tenant
  requires a minimum of 1 active branch; all non-selection endpoints require an active
  branch. So no `enforce_branch_assignment_for_login` and no
  `minimum_active_branches_required` setting.
- **Login/role gating is permission-based.** A user lacking permission for a resource
  receives 403; there is no separate `enforce_role_assignment_for_login` flag.
- **Outbox retry is a platform-level Namastack configuration**, not a tenant setting — so
  no `outbox_max_retries`.

## 9. Testing

Unit (per service, mocked ports):

- Settings: catalog validation (unknown key rejected; bad timezone/currency/int
  rejected; valid IANA/ISO 4217 accepted); ACTIVE guard; permission gate for normal vs
  platform-admin-only settings; **sensitive setting redacted in audit metadata** (synthetic
  sensitive catalog entry); deactivate closes row / no insert; read-side masking of
  sensitive values.
- Business date: each command's precondition + status transition; **advance requires
  `business_date.advance` permission**; reopen requires `business_date.reopen` (no
  fallback); optimistic-lock stale → retry error; **inactive tenant cannot advance /
  init / cob** (ACTIVE guard).

Integration (`@ApplicationModuleTest` + Testcontainers Postgres/Redis/RabbitMQ),
covering the acceptance list verbatim:

- Settings update writes an audit event.
- Sensitive settings are redacted in the persisted audit event.
- Business date advance requires permission (denied → `AccessDeniedException`).
- Business date advance emits an outbox event (assert outbox / externalized event).
- Inactive tenant cannot advance business date.
- History query is paginated and ordered.

ArchUnit + Spring Modulith `verify()` must stay green.

## 10. Documentation deliverables

- `docs/operations/tenant-settings.md` — catalog, value types (IANA/ISO 4217),
  sensitivity/redaction, permissions, effective-dating/deactivate semantics, and the
  §8 "not a setting" architecture decisions.
- `docs/operations/business-date.md` — status model, command matrix, permissions,
  history/pagination, COB-status-foundation scope (no EOD/day-close).

## 11. Out of scope (explicit)

- Financial EOD / day-close / accounting posting.
- REST/HTTP inbound adapters (deferred, consistent with current lifecycle posture).
- RabbitMQ consumers / JobRunr jobs for the new events (outbox emission only).
- Promoting COB status into the `common.transitions` FSM engine.
- Encryption-at-rest of sensitive setting values (schema comment notes it; not
  implemented here — redaction covers the audit-trail requirement).
