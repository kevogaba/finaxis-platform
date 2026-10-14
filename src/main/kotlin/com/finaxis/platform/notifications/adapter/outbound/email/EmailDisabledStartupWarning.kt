package com.finaxis.platform.notifications.adapter.outbound.email

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * Logs one `WARN` at startup while `finaxis.email.enabled=false`, the default in every profile.
 *
 * With email off, [DisabledEmailGateway] fails every welcome and application-invite email
 * permanently, so an operator who forgot the flag would otherwise find out only from failed
 * `jobrunr_jobs` rows. The line names the property and its environment variable and carries no
 * address, recipient or secret. The property is read from the running environment here, not at
 * image-build time, so the warning follows a `FINAXIS_EMAIL_ENABLED` change on restart.
 */
@Component
class EmailDisabledStartupWarning(
    private val properties: EmailProperties,
) : SmartInitializingSingleton {
    /** Logs the warning once per start, after every singleton exists, when email is disabled. */
    override fun afterSingletonsInstantiated() {
        if (properties.enabled) {
            return
        }
        logger.warn(
            "Email delivery is disabled (finaxis.email.enabled=false): welcome and " +
                "application-invite emails will fail permanently until it is enabled " +
                "(set FINAXIS_EMAIL_ENABLED=true).",
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(EmailDisabledStartupWarning::class.java)
    }
}
