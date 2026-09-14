package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender

/**
 * Wires the active [EmailGateway] implementation, always wrapped for observability.
 *
 * The gateway [Bean] is registered unconditionally and branches on [EmailProperties.enabled]
 * inside its method body, rather than gating which bean is registered with
 * `@ConditionalOnProperty`/`@ConditionalOnMissingBean`. This repository's `bootBuildImage`-based
 * container images run Spring AOT unconditionally, and AOT freezes a `@ConditionalOnProperty`
 * bean's registration decision at image-build time: once baked in, no environment variable set at
 * container start or restart can change which bean exists, only a rebuilt image can (verified
 * empirically via `processAot` for this exact configuration; see
 * `docs/superpowers/specs/2026-09-06-production-readiness-coolify-deployment-design.md`). A
 * property read *inside* an always-registered bean's method body is not frozen the same way — it
 * is re-evaluated from the running container's actual environment every time — which is what lets
 * `FINAXIS_EMAIL_ENABLED` be flipped on a live Coolify deployment with a container restart and no
 * image rebuild. [ObjectProvider] defers resolution of the mail sender and renderer so neither
 * bean needs to exist when email is disabled.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    /**
     * Produces the runtime [EmailGateway]: a Spring Mail-backed gateway when
     * `finaxis.email.enabled=true`, otherwise a no-op gateway that always fails permanently.
     * Always wrapped in [MeteredEmailGateway] for delivery-attempt observability, regardless of
     * which delegate is active.
     */
    @Bean
    fun emailGateway(
        properties: EmailProperties,
        mailSenderProvider: ObjectProvider<JavaMailSender>,
        rendererProvider: ObjectProvider<EmailTemplateRenderer>,
        meterRegistry: MeterRegistry,
    ): EmailGateway {
        val delegate: EmailGateway =
            if (properties.enabled) {
                SpringMailEmailGateway(
                    mailSenderProvider.getObject(),
                    rendererProvider.getObject(),
                    properties,
                )
            } else {
                DisabledEmailGateway()
            }
        return MeteredEmailGateway(delegate, meterRegistry)
    }
}
