package com.finaxis.platform.notifications.application.port.outbound

import com.finaxis.platform.notifications.application.WelcomeEmailCommand

/**
 * Schedules deferred delivery of a welcome email without coupling the application service to a
 * background-processing implementation.
 */
fun interface WelcomeEmailScheduler {
    /**
     * Schedules a welcome email using the supplied membership activation details.
     *
     * @param command the activation details for the email delivery job
     */
    fun scheduleWelcomeEmail(command: WelcomeEmailCommand)
}
