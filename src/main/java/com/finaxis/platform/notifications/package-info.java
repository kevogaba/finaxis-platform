/** Notification delivery module consuming selected lifecycle transition integrations. */
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
