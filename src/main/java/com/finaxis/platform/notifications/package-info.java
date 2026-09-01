/**
 * Notification delivery module consuming selected lifecycle transition integrations.
 *
 * <p>BIAN: Contact Handler (adapted) — outbound transactional email only, not multi-channel
 * customer contact. See docs/architecture/bian-service-landscape.md.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {
      "common::audit",
      "common::jobs",
      "common::persistence",
      "common::transitions",
      "jooq"
    })
package com.finaxis.platform.notifications;
