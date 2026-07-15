/** Notification delivery module consuming selected lifecycle transition integrations. */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notifications",
    allowedDependencies = {"common::audit", "common::persistence", "common::transitions"})
package com.finaxis.platform.notifications;
