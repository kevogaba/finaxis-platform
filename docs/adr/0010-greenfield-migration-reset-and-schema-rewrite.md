# ADR 0010: Greenfield Migration Reset And Schema Rewrite

## Status

Accepted

Date: 2026-08-02

## Context

The foundation was built incrementally across fourteen Flyway migrations, `V1__create_iam_schema`
through `V14__iam_listing_order_indexes`. That history was honest but no longer useful as a schema
reference: the shape of `user_organisation_membership` was spread across `V1` and `V6`, the
`reason` columns arrived in `V4` as `ALTER TABLE` statements against five tables created in `V1`,
the permission catalogue was assembled from `V2`, `V4`, `V7`, `V8`, and `V10`, and `V12`/`V13`
inserted then deleted the same two rows. Reading the schema required replaying fourteen files in
order.

Two latent defects were embedded in that history. `V7` re-inserted `iam.profile.read` under a
second id, `40000000-0000-0000-0000-000000000026`, using `ON CONFLICT (permission_code) DO
UPDATE`, so the `V2` id `66666666-6666-6666-6666-666666666601` always won and the `V7` id was
never used in any environment — a permission with two documented ids, one of them fictional. And
`V8` granted three business-date permissions to every role coded `TENANT_ADMIN`, which no
migration ever creates (that role is created by application code at tenant-provisioning time), so
the grant was a no-op on a fresh database.

Critically, the platform has never been deployed. There is no production database, no customer
data, and no environment whose Flyway history must be preserved. This is the last moment at which
a destructive reset is free.

## Decision

Delete all fourteen migrations and replace them with three:

- `V1__foundation_schema.sql` — every table, constraint, index, and comment. The single source of
  schema truth.
- `V2__platform_reference_data.sql` — the 54-code permission catalogue, the reserved `PLATFORM`
  organisation, and the `PLATFORM_SUPER_ADMIN` / `PLATFORM_SUPPORT` roles with their grants.
- `V3__bootstrap_tenant_and_administrator.sql` — the first-deployment bootstrap tenant, its head
  office and operations branches, the first administrator, and that administrator's Keycloak
  identity link, membership, branch assignments, role, business date, and settings.

Every deterministic seed identifier is preserved byte for byte. 118 references across 22 test,
script, and documentation files depend on them, and changing them would have turned a schema
reset into a test rewrite. `iam.profile.read` keeps the `V2` id that actually won.

Two permission codes are dropped. `logistics.shipment.approve` was a leftover from an unrelated
logistics domain with no place in a SACCO/core-banking catalogue. `iam.user.invite` duplicated
`user.invite` and was enforced by nothing — `user.invite` is the code checked in
`TenantUserController`, audited by `UserProvisioningService`, and granted to every provisioned
tenant role, while `iam.user.invite` existed only in the catalogue and one bootstrap grant. Two
codes for one capability is an authorization hazard: a reviewer cannot tell which one actually
gates the operation.

`PLATFORM_SUPER_ADMIN` is now granted permissions by a set-based `INSERT … SELECT … FROM
permission` rather than an enumerated list, so the superset role can never again drift behind the
catalogue as it did when `V10` added 24 codes.

The new seeds deliberately omit `ON CONFLICT` clauses. On a fresh database a conflict means the
seed is wrong, and it should fail loudly rather than silently skip.

**This is the last reset.** Every future schema change is a forward-only `V4+` migration.

## Consequences

The schema is readable in one file. A new developer or agent can answer "what does this table look
like" without replaying history.

Any existing local or CI database must be dropped and recreated; Flyway will otherwise fail
validation with a checksum mismatch against the old `V1`. `docs/development/local-development.md`
documents the reset command. Because jOOQ codegen bootstraps an embedded PostgreSQL from these
migrations at build time (`build.gradle.kts`), the generated sources regenerate automatically and
no generated code was hand-edited.

The three-file split is a boundary that should be maintained: schema in `V1`-equivalents,
immutable platform reference data separate from tenant-specific bootstrap data. A future
deployment that must not carry the bootstrap tenant can skip `V3` by configuring a narrower Flyway
location, without touching the schema or the catalogue.

## Alternatives Considered

Keep the fourteen migrations and add a documentation file describing the consolidated schema:

- Rejected. A hand-maintained schema document drifts from the migrations that actually run. The
  migrations are executable; a document is not.

Squash into a single migration file:

- Rejected. Schema, immutable reference data, and environment-seeded bootstrap data have different
  review audiences and different reasons to change. Collapsing them would make a permission
  catalogue change indistinguishable from a DDL change in review.

Preserve `logistics.shipment.approve` for compatibility:

- Rejected. Nothing depends on it in the database. The only references were unit-test fixtures
  using it as an arbitrary in-memory string, which are unaffected.

## Verification

- `FoundationSchemaMigrationTests` — all 23 tables created on an empty PostgreSQL via
  Testcontainers
- `FoundationSeedDataTests` — the exact 54-code catalogue, superset role coverage, support-role
  scope, and bootstrap-administrator login pre-checks
- `FoundationSchemaGuidTests` — see ADR 0015
- A live reset: `docker compose down -v`, fresh volume, application boot applies exactly three
  migrations, and `scripts/local-smoke.sh` passes end to end against Keycloak
- ADR 0011, ADR 0015
