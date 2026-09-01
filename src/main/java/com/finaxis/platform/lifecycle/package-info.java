/**
 * Lifecycle application services built on public transition, audit, and persistence contracts.
 *
 * <p>BIAN: none — platform-specific boundary. Tenant, branch, user and membership state machines,
 * tenant provisioning and the controlled business date are SaaS platform concerns with no BIAN
 * Service Domain. See docs/architecture/bian-service-landscape.md.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Lifecycle",
    allowedDependencies = {
      "common::application",
      "common::audit",
      "common::context",
      "common::id",
      "common::jobs",
      "common::persistence",
      "common::transitions",
      "common::web-api",
      "common::web-idempotency",
      "notifications::email",
      "jooq"
    })
package com.finaxis.platform.lifecycle;
