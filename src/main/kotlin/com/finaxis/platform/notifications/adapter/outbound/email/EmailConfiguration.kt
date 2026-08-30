package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender

/** Wires the active [EmailGateway] implementation, always wrapped for observability. */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    /**
     * Produces a Spring Mail-backed email gateway wrapped with observability metrics when
     * `finaxis.email.enabled=true`.
     */
    @Bean
    @ConditionalOnProperty("finaxis.email.enabled", havingValue = "true")
    fun springMailEmailGateway(
        mailSender: JavaMailSender,
        renderer: EmailTemplateRenderer,
        properties: EmailProperties,
        meterRegistry: MeterRegistry,
    ): EmailGateway =
        MeteredEmailGateway(SpringMailEmailGateway(mailSender, renderer, properties), meterRegistry)

    /**
     * Produces a disabled email gateway wrapped with observability metrics as a fallback
     * when no other [EmailGateway] bean is available or when `finaxis.email.enabled=false`.
     */
    @Bean
    @ConditionalOnMissingBean(EmailGateway::class)
    fun disabledEmailGateway(meterRegistry: MeterRegistry): EmailGateway =
        MeteredEmailGateway(DisabledEmailGateway(), meterRegistry)
}
