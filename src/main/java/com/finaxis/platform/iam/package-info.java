/**
 * Identity and Access Management: authentication context, membership selection, authorization, and
 * user profile services. Exports a named {@code authorization} interface for cross-module
 * permission checks performed by the lifecycle module adapter.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "IAM",
    allowedDependencies = {
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
