/** Lifecycle application services built on public transition, audit, and persistence contracts. */
@org.springframework.modulith.ApplicationModule(
    displayName = "Lifecycle",
    allowedDependencies = {
      "common::application",
      "common::audit",
      "common::context",
      "common::id",
      "common::persistence",
      "common::transitions",
      "common::web-api",
      "common::web-idempotency",
      "jooq"
    })
package com.finaxis.platform.lifecycle;
