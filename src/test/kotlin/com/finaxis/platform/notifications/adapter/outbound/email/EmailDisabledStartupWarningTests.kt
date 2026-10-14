package com.finaxis.platform.notifications.adapter.outbound.email

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the startup warning for email delivery switched off: the real [EmailProperties] binding
 * and the real component are refreshed in a context, and the log output is captured on the
 * component's own logger, so nothing else in the test run can satisfy the assertion.
 */
class EmailDisabledStartupWarningTests {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(
            EmailPropertiesBinding::class.java,
            EmailDisabledStartupWarning::class.java,
        )

    @Test
    fun `logs exactly one warning at startup when email delivery is disabled`() {
        val warnings =
            captureWarnings {
                contextRunner.withPropertyValues("finaxis.email.enabled=false").run { context ->
                    assertTrue(context.isRunning)
                }
            }

        assertEquals(1, warnings.size, "expected exactly one startup WARN, got $warnings")
        val message = warnings.single().formattedMessage
        assertTrue(message.contains("finaxis.email.enabled=false"), message)
        assertTrue(message.contains("FINAXIS_EMAIL_ENABLED"), message)
        assertTrue(message.contains("welcome"), message)
        assertTrue(message.contains("application-invite"), message)
        assertFalse(message.contains("@"), "the warning must carry no address: $message")
    }

    @Test
    fun `logs no warning at startup when email delivery is enabled`() {
        val warnings =
            captureWarnings {
                contextRunner.withPropertyValues("finaxis.email.enabled=true").run { context ->
                    assertTrue(context.isRunning)
                }
            }

        assertTrue(warnings.isEmpty(), "expected no startup WARN, got $warnings")
    }

    private fun captureWarnings(startContext: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EmailDisabledStartupWarning::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            startContext()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }
    }
}

@EnableConfigurationProperties(EmailProperties::class)
private class EmailPropertiesBinding
