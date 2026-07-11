# API Versioning

All public APIs must use path-based versions from the beginning.

## Canonical Namespace

The first public API namespace is:

```text
/api/v1
```

Current auth context endpoints are:

```text
POST /api/v1/auth/select-organisation
POST /api/v1/auth/select-branch
GET  /api/v1/auth/me
```

Do not add public endpoints outside `/api/v1`, `/api/v2`, and future explicit versions. Actuator,
OpenAPI docs, Scalar docs, and static docs are operational/documentation endpoints, not public
business APIs.

## Version Introduction

Introduce `/api/v2` only when response shape, validation, workflow, or compatibility requirements
cannot be safely evolved in `/api/v1`. Document the compatibility window, migration path, and smoke
test changes.

## Spring Framework 7

Spring Framework 7 has first-class API versioning support. The project currently keeps explicit
path constants because the route surface is small and the canonical path must be obvious. If native
version negotiation is introduced later, keep the public path shape unchanged.
