You are working on our Greenfield Spring Boot modular monolith SACCO/core banking platform.

Important architectural constraints:
- The application uses Spring Boot and Spring Modulith.
- Persistence is JDBC-first, using Spring Data JDBC where appropriate.
- Database migrations are exactly three Flyway files after the greenfield reset:
  `V1__foundation_schema.sql`, `V2__platform_reference_data.sql`, and
  `V3__bootstrap_tenant_and_administrator.sql`. The greenfield window is now **closed** — every
  further schema change is a forward-only `V4+` migration. See
  [ADR 0010](../adr/0010-greenfield-migration-reset-and-schema-rewrite.md).
- We are implementing only foundational platform concerns in this sequence:
  - identity
  - multi-tenancy
  - tenant provisioning/deprovisioning
  - branch provisioning/deprovisioning
  - user provisioning/deprovisioning
  - user organisation membership
  - branch assignment
  - roles and permissions
  - lifecycle FSM usage
  - audit logging
  - AMQP/domain event foundation
  - setup-level organisation settings
  - business date
- Do not implement savings, shares, loans, accounting, teller, member onboarding, or product
  configuration yet except where required for permissions, tenant setup, or future extension
  points.
- We already have foundational FSM concepts: transitions and logs. Do not duplicate the FSM
  engine blindly. Inspect the existing implementation first, then apply it to tenant, branch,
  user, and membership lifecycle use cases.
- We will coordinate users with Keycloak. The application must have its own user and tenant
  membership records, while Keycloak remains the external identity provider for login.
- A user may belong to multiple organisations/tenants.
- A user must not be allowed to operate unless:
  - the tenant is ACTIVE,
  - the user account is ACTIVE,
  - the user’s tenant membership is ACTIVE,
  - the user is assigned to at least one active branch, where required,
  - the user has at least one active role with permissions.
- Use transactional outbox (namastack) for AMQP publication. Do not publish critical events
  directly inside business services without durable persistence.
- Configure Spring auditing for JDBC so created_at, updated_at, created_by, and updated_by are
  automatically populated.
- Every implementation must include tests, documentation, and ADRs where decisions affect the
  whole project.
- Maintain Spring Modulith boundaries and add tests that verify module boundaries.
- Prefer explicit, boring, production-grade design over clever abstractions.

Foundation REST API rules:
- [Foundation REST API](../api/foundation-api.md) is the canonical OpenAPI/REST contract
  reference.
- Inbound adapters are thin and module-owned; they validate transport details and delegate to
  application services.
- Queries must be bounded and tenant-filtered. Every collection endpoint returns the standard
  paginated envelope.
- Every endpoint has an explicit application-layer permission check. The intentional exceptions
  are organisation/branch selection, enforced inside `AuthSelectionService`, and tenant settings,
  enforced per setting key inside `TenantSettingsService.authorize()`.
- Every mutation is idempotent with optional/generated UUID `Idempotency-Key` handling.
- Tenant and branch context are enforced before tenant data is returned or mutated.
- Public JSON uses `snake_case`; business dates use `dd-MM-yyyy`, times use `HH:mm:ss`, and
  datetimes use ISO-8601 offset format.
