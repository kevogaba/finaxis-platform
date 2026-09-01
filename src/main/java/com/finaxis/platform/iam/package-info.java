/**
 * Identity and Access Management: authentication context, membership selection, authorization, and
 * user profile services. Exports a named {@code authorization} interface for cross-module
 * permission checks performed by the lifecycle module adapter.
 *
 * <p>BIAN: Party Authentication, Party Lifecycle Management, Party Reference Data Directory
 * (adapted) — Keycloak authenticates; Finaxis owns permission-code authorization and multi-tenant
 * membership, which BIAN does not model. See docs/architecture/bian-service-landscape.md.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "IAM",
    allowedDependencies = {
      "accounting",
      "common::application",
      "common::audit",
      "common::context",
      "common::id",
      "common::persistence",
      "common::transitions",
      "common::web-api",
      "common::web-idempotency",
      "common::web-ratelimit",
      "common::web-versioning",
      "lifecycle",
      "lifecycle::application",
      "lifecycle::domain",
      "lifecycle::query",
      "lifecycle::web",
      "jooq"
    })
package com.finaxis.platform.iam;
