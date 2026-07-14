/** Lifecycle application services built on public transition, audit, and persistence contracts. */
@org.springframework.modulith.ApplicationModule(
    displayName = "Lifecycle",
    allowedDependencies = {
      "common::audit",
      "common::context",
      "common::id",
      "common::persistence",
      "common::transitions",
      "jooq"
    })
package com.finaxis.platform.lifecycle;
