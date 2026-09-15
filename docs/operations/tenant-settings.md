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
| `base_currency` | `CURRENCY` | no | no | none |
| `require_maker_checker_for_user_invites` | `BOOLEAN` | no | no | `false` |
| `require_maker_checker_for_branch_creation` | `BOOLEAN` | no | no | `false` |
| `business_date_auto_advance_enabled` | `BOOLEAN` | no | no | `false` |
| `audit_retention_days` | `INT` | no | yes | none |

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

## Effective dating

Settings are never overwritten destructively. `OrganisationSettingsStore` serializes writes for
one `(organisation_id, key)`, closes the currently-effective row by setting `effective_to`, and
inserts a new row with the catalog value type and sensitivity metadata.

`DeactivateTenantSetting` also closes the currently-effective row, but inserts no replacement.
The key then has no current stored value. A later `CreateOrUpdateTenantSetting` can make the
setting effective again.

## Permissions

Only active organisations can be changed.

Normal tenant settings require `settings.update` scoped to the target organisation:

- `default_timezone`
- `base_currency`
- `require_maker_checker_for_user_invites`
- `require_maker_checker_for_branch_creation`
- `business_date_auto_advance_enabled`

Platform-only settings require `tenant_setting.manage_platform` scoped to the reserved platform
organisation, not to the tenant organisation:

- `audit_retention_days`

The permission check is dependency-inverted. `lifecycle` defines the `PermissionGuard` port, and
`iam` implements it with `LifecyclePermissionGuardAdapter`, avoiding a direct
`lifecycle -> iam` module dependency.

`GetTenantSetting` and `ListTenantSettings` require `settings.update` scoped to the target
organisation. List results mask platform-only values unless the caller also has
`tenant_setting.manage_platform` scoped to the platform organisation.

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
- `ListTenantSettings`: return all catalog settings plus stored-only current settings.

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
