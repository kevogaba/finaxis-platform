package com.finaxis.platform.notifications.adapter.outbound.email

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Finaxis-specific email configuration. SMTP transport settings (host/port/credentials/timeouts)
 * are owned by Spring Boot's own `spring.mail.*`/`MailProperties`, not duplicated here.
 */
@ConfigurationProperties(prefix = "finaxis.email")
data class EmailProperties(
    val enabled: Boolean = false,
    val fromAddress: String = "no-reply@finaxis.local",
    val fromDisplayName: String = "Finaxis",
    val appBaseUrl: String = "http://localhost:5173",
) {
    init {
        require(fromAddress.isNotBlank()) { "From address must not be blank" }
        require(fromDisplayName.isNotBlank()) { "From display name must not be blank" }
        require(
            APP_URL_REGEX.matches(appBaseUrl),
        ) { "App base URL must be an absolute http(s) URL" }
    }

    private companion object {
        val APP_URL_REGEX = Regex("^https?://.+")
    }
}
