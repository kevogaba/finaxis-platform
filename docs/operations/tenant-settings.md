# Tenant settings

Tenant settings are setup-level organisation configuration. `Organisation` remains the tenant
boundary; this feature adds a catalog-backed command/query layer over the existing
effective-dated `organisation_setting` table.

This document describes the application service surface implemented by `TenantSettingsService`.
`TenantSettingsController` exposes it over REST at `/api/v1/tenant/settings` — see the
[foundation API contract](../api/foundation-api.md#tenant-settings). Authorization is enforced
per setting key inside `TenantSettingsService.authorize()` rather than by a controller-level
permission gate, which is one of the two documented exceptions to the blanket endpoint-permission
rule.

Read this with [organisation provisioning](tenant-provisioning.md),
[audit logging](../architecture/audit-logging.md), and
[ADR 0007](../adr/0007-append-only-audit-log-and-redaction-policy.md).

## Catalog

`TenantSettingCatalog` is the authoritative list of allowed keys. Mutation commands reject
unknown keys instead of accepting arbitrary key/value pairs.

| Key | Type | Sensitive | Platform-only | Default |
|---|---|---:|---:|---|
| `default_timezone` | `TIMEZONE` | no | no | none |
| `base_currency` | `CURRENCY` | no | no | read-only mirror (see below) |
| `require_maker_checker_for_user_invites` | `BOOLEAN` | no | no | `true` |
| `require_maker_checker_for_branch_creation` | `BOOLEAN` | no | no | `true` |
| `business_date_auto_advance_enabled` | `BOOLEAN` | no | no | `false` |
| `audit_retention_days` | `INT` | no | yes | none |

`require_maker_checker_for_user_invites` and `require_maker_checker_for_branch_creation` are the
tenant's **maker-checker switches** ([ADR 0032](../adr/0032-per-tenant-maker-checker-switches.md)).
Maker-checker is required unless the tenant stores the literal `false`: unset, `true` and any other
value keep it on, so the default is `true` and no existing tenant changes behaviour.

- **Off lifts segregation, not the approval.** With `require_maker_checker_for_user_invites` off,
  the inviter and the invited user may approve a membership; with
  `require_maker_checker_for_branch_creation` off, the creator and anyone who amended the branch
  may approve it. `user.approve` / `branch.approve` are still required and the FSM is unchanged.
- **Tenant approvers only.** The platform checker is always held to the rule (ADR 0028), whatever
  the tenant stored. Tenant provisioning approval and accounting maker-checker are not governed.
- **Audited.** An approval that relied on a lifted check is a self-approval: the `user.approve` row
  is `HIGH` with `selfApproved = true`, a branch approval adds one `branch.self_approve` row
  (`HIGH`); changing either setting is itself a `HIGH` `settings.update` row. Find them with
  `severity=HIGH` or by those action names.

Validation and canonicalization are catalog-owned:

- `TIMEZONE`: must be an IANA zone id from `ZoneId.getAvailableZoneIds()`.
- `CURRENCY`: must be a currency the ledger can post in, resolved by
  `MoneyPolicy.requireSettlementCurrency`. That is an ISO 4217 code the JDK knows **and**
  one that has a minor unit, so `XXX` and the metals (`XAU`, `XAG`, `XPD`, `XPT`) are
  refused: no amount can be settled in them. A failure reports `accounting.currency_invalid`.
  A posting refuses an unknown code under that same code, but refuses `XXX` and the metals as
  `accounting.amount_precision_exceeded` instead - they are known codes with no minor unit, so it
  is the amount rather than the currency that fails. Refusing them here is what stops a tenant
  reaching that state.
- `BOOLEAN`: accepts only `true` or `false`, case-insensitively, and stores lowercase.
- `INT`: must parse as a non-negative integer and stores the canonical decimal string.

Blank values are rejected before type validation. Currency values are stored uppercase.

Two keys are internal and deliberately outside the catalogue. `settings.operational` is the marker
the platform writes when a tenant is approved, and it is not a tenant setting: `ListTenantSettings`
leaves it out, and `GetTenantSetting` refuses it as an unknown key, exactly as a `PUT` does. After
approval a list therefore totals the catalogue's six keys and no more.

## Platform route and retired rows

`/api/v1/platform/tenants/{tenant_id}/settings` is how a platform administrator, who is no member
of the tenant, reads and changes the platform-only keys (#273); see the foundation API. It reuses
the tenant route's validation, audit (`settings.update`, actor the platform administrator) and
outbox event, and the permission check comes first.

`V31` closes (`effective_to`, never a delete) every current `base_currency` row, a mirror nothing
reads, and the `business-date.timezone` row `V3` seeded for the bootstrap tenant, which nothing
reads either: the organisation's `base_currency_code` and `timezone` columns are the one source.
It also closes any current `false` of the two maker-checker switches. Until that release both
switches defaulted to `false` and were read by nothing, so a `false` may be only a saved default;
the release makes `false` mean "self-approval allowed", and a row nobody meant as an opt-out must
not become one. A tenant that wants the opt-out sets it again after the upgrade, which is audited
HIGH. `true` rows and unset switches are left alone. `V31` is idempotent and writes only
`effective_to` and `updated_at`. A hand-written row under any of those keys is retired the same
way if it is current when `V31` runs.

Changing a maker-checker switch locks the organisation row, the lock a membership or branch
approval takes before it reads the switch, so an approval never acts on a value a committed change
has replaced.

## The base currency is a read-only mirror

`base_currency` shows the organisation's `base_currency_code`, the column the ledger reads for every
journal line. The setting is not a second copy that can be changed:

- `GetTenantSetting` and `ListTenantSettings` report the column's value. A row stored under the key
  is ignored.
- `PUT /{key}` and `DELETE /{key}` for `base_currency` answer `422 invalid_operation` with the
  detail `base_currency is derived from the organisation's base currency and cannot be changed
  here.` The refusal comes after the caller's permission check and before any value check, so
  nothing is stored, audited or published.
- The column is written only at draft creation and draft amendment, and an amendment is refused
  unless the organisation is a `DRAFT`. A journal can be posted only once the organisation has left
  `DRAFT`, so after a posting no route reaches the column and the base currency is frozen. See
  [accounting foundation](../architecture/accounting-foundation.md), "The functional currency is
  frozen once the tenant has posted".
- On draft creation, `initial_settings.base_currency` is accepted only when it equals
  `base_currency_code` after canonicalisation. A different currency is `422 invalid_operation`.
  An equal value is accepted and not stored: the organisation's column carries it.

## Setup values at draft creation and amendment

The setup values a tenant draft takes: `initial_settings` is set on create only, and
`business_date` can be set on create or amend:

- `initial_settings` is a create input. A draft amend with a non-empty `initial_settings` is
  `422 invalid_operation`; an empty `{}` is accepted and changes nothing. Later changes to a
  setting go through `PUT /{key}`.
- `business_date` sets the date the tenant opens on, on create or on an amend (#271). An absent key
  or an explicit `null` changes nothing. A draft that never names a date has none until approval
  derives it from the timezone the tenant then has, so a timezone amended on the draft needs no
  business date; a date named is kept as stated. Drafts created before #271 were seeded a derived
  date at create: amend them with `business_date` if their timezone changed.
- A refused amend writes nothing: the draft, its administrator, its settings and the audit log are
  as they were.

The amend's `admin` block is optional. An amend without it leaves the stored administrator draft
alone. See the [foundation API contract](../api/foundation-api.md#amend-tenant-draft).

## Effective dating

Settings are never overwritten destructively. `OrganisationSettingsStore` serializes writes for
one `(organisation_id, key)`, closes the currently-effective row by setting `effective_to`, and
inserts a new row with the catalog value type and sensitivity metadata.

`DeactivateTenantSetting` also closes the currently-effective row, but inserts no replacement.
The key then has no current stored value. A later `CreateOrUpdateTenantSetting` can make the
setting effective again.

## Permissions

Only active organisations can be changed.

Normal tenant settings require `settings.update` scoped to the target organisation. `base_currency`
is not writable through them (see above):

- `default_timezone`
- `require_maker_checker_for_user_invites`
- `require_maker_checker_for_branch_creation`
- `business_date_auto_advance_enabled`

Platform-only settings require `tenant_setting.manage_platform` scoped to the reserved platform
organisation, not to the tenant organisation:

- `audit_retention_days` (a future purge job must keep the amend rows the maker-checker rules
  read: see [audit logging](../architecture/audit-logging.md), "Retention (#255)")

The permission check is dependency-inverted. `lifecycle` defines the `PermissionGuard` port, and
`iam` implements it with `LifecyclePermissionGuardAdapter`, avoiding a direct
`lifecycle -> iam` module dependency.

`GetTenantSetting` and `ListTenantSettings` require `settings.view` scoped to the target
organisation. A read of a platform-only key also requires `tenant_setting.manage_platform` scoped
to the platform organisation, so a single `GET /audit_retention_days` without it is `403`.

`ListTenantSettings` does not refuse a platform-only key to a caller without that permission. It
masks a set value as `***REDACTED***` and reports an unset value as `null`. A mask is applied only
to a value that is present, so an unset key never looks set.

## Sensitivity and redaction

No current catalog setting is sensitive, but the mechanism is implemented and tested. When a
catalog entry is sensitive, `TenantSettingsService` wraps before/after audit values in
`Redacted(...)`, and `AuditService` stores the masked value. Read responses also mask sensitive
current values as `***REDACTED***`.

`GetTenantSetting` and `ListTenantSettings` prefer catalog metadata, but can still read a
stored-only key by falling back to the row's persisted `value_type` and `is_sensitive` metadata.
That fallback keeps historical rows readable after a catalog change.

## Command and query surface

`TenantSettingsService` exposes application-service operations:

- `CreateOrUpdateTenantSetting`: validate the key and value, require the correct permission,
  close the current row, insert a new row, audit, and publish an externalized event.
- `DeactivateTenantSetting`: require the same permission, close the current row, audit, and
  publish an externalized event with no replacement row.
- `GetTenantSetting`: return one current setting view, masking sensitive values.
- `ListTenantSettings`: return all catalog settings plus stored-only current settings, leaving out
  the internal `settings.operational` marker.

These are exposed by `TenantSettingsController` under `/api/v1/tenant/settings`:

| Method | Path | Authorization |
| --- | --- | --- |
| GET | `/` | per-key, inside the service |
| GET | `/{key}` | per-key, inside the service |
| PUT | `/{key}` | per-key, inside the service |
| DELETE | `/{key}` | per-key, inside the service |

Listing is paginated and tenant-filtered. The controller performs no authorization of its own —
`TenantSettingsService.authorize()` resolves the required permission from the setting key, so a
platform-only key is refused to a tenant administrator.

## Audit and outbox

Every create/update/deactivate records an audit event with action `settings.update` and resource
type `ORGANISATION_SETTING`. Sensitive before/after values are masked before persistence.

Every mutation publishes an `ExternalizedTransitionEvent` to target
`finaxis.lifecycle.organisation.settings-updated`.

The event metadata includes:

- `eventType=TenantSettingsUpdated` for create/update.
- `eventType=TenantSettingDeactivated` for deactivate.
- `organisationId` and `key`.

The event is emitted through the existing Spring Modulith and Namastack outbox path. This slice
does not add a RabbitMQ consumer.

## Deliberately not tenant settings

Keycloak owns authentication and SSO entirely. There is no `keycloak_invite_enabled` setting and
no external-SSO setting in this application. This app is an OAuth2 resource server; it never
manages credentials or IdP configuration.

`max_failed_login_policy_reference` is not a setting. Lockout and failed-login policy belong in
Keycloak.

`application_invite_enabled` is not a setting. Invite transport is Keycloak-owned, not a tenant
toggle in this application.

Branch assignment is a structural invariant, not a toggle. Every operational tenant requires at
least one active branch, so there is no `enforce_branch_assignment_for_login` setting and no
`minimum_active_branches_required` setting.

Login and role gating are permission-code based. Missing permission returns 403; there is no
separate `enforce_role_assignment_for_login` flag.

Outbox retry is platform-level Namastack configuration, not tenant configuration. There is no
`outbox_max_retries` tenant setting.
