package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionSecurityProfileTests {
    @Test
    fun `production profile starts when deployment secrets are supplied`() {
        productionContext(
            "$ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV=$TEST_SECRET",
            "$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example",
            "$PROTECTED_RESOURCE_URL_ENV=https://api.finaxis.example:443/",
        ).use { context ->
            assertEquals(
                TEST_SECRET,
                context.getBean(ActiveOrganisationContextProperties::class.java).secret,
            )
            val built = context.getBean(BuiltCustomizer::class.java)
            assertEquals("https://api.finaxis.example", built.customizer.resource)
            assertEquals(emptyList(), built.warnings)
        }
    }

    @Test
    fun `production profile starts without the protected resource URL and warns once`() {
        productionContext(
            "$ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV=$TEST_SECRET",
            "$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example",
        ).use { context ->
            val built = context.getBean(BuiltCustomizer::class.java)
            assertNull(built.customizer.resource)
            assertEquals(
                listOf(ProtectedResourceMetadataCustomizer.RESOURCE_NOT_CONFIGURED),
                built.warnings,
            )
            // The fallback document: the origin Spring derived from the request.
            val metadata =
                OAuth2ProtectedResourceMetadata
                    .builder()
                    .resource("https://api.example.test")
                    .tlsClientCertificateBoundAccessTokens(true)
                    .also(built.customizer::accept)
                    .build()
            assertEquals("https://api.example.test", metadata.claims["resource"])
            assertFalse(metadata.claims.containsKey("tls_client_certificate_bound_access_tokens"))
        }
    }

    @Test
    fun `production profile refuses an invalid protected resource URL without repeating it`() {
        val failure =
            assertThrows<Exception> {
                productionContext(
                    "$ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV=$TEST_SECRET",
                    "$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example",
                    "$PROTECTED_RESOURCE_URL_ENV=https://user:$REFUSED_SECRET@api.finaxis.example",
                ).use { }
            }

        val chain = generateSequence(failure as Throwable) { it.cause }.toList()
        val root = chain.last()
        assertIs<IllegalArgumentException>(root)
        assertEquals(ProtectedResourceMetadataCustomizer.INVALID_RESOURCE, root.message)
        assertTrue(chain.none { it.message.orEmpty().contains(REFUSED_SECRET) }, chain.toString())
    }

    @Test
    fun `production profile fails fast when active organisation secret is absent`() {
        assertThrows<Exception> {
            productionContext(
                "$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example",
                "$PROTECTED_RESOURCE_URL_ENV=https://api.finaxis.example",
            ).use { }
        }
    }

    private fun productionContext(vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(ProductionSecurityProfileTestApplication::class.java)
            .profiles("production")
            .web(WebApplicationType.NONE)
            .run(
                "--spring.config.location=$PRODUCTION_CONFIG_LOCATIONS",
                *properties.map { "--$it" }.toTypedArray(),
            )

    /** The customizer as `SecurityConfiguration` builds it, and the warnings building it logged. */
    class BuiltCustomizer(
        val customizer: ProtectedResourceMetadataCustomizer,
        val warnings: List<String>,
    )

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        ActiveOrganisationContextProperties::class,
        ProtectedResourceProperties::class,
        OAuth2ResourceServerProperties::class,
    )
    private class ProductionSecurityProfileTestApplication {
        @Bean
        fun builtCustomizer(
            properties: ProtectedResourceProperties,
            resourceServer: OAuth2ResourceServerProperties,
        ): BuiltCustomizer {
            // Attached here, after Spring Boot has initialised logging, so it is not reset.
            val logger =
                LoggerFactory.getLogger(ProtectedResourceMetadataCustomizer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val customizer =
                    ProtectedResourceMetadataCustomizer.from(properties, resourceServer)
                return BuiltCustomizer(
                    customizer,
                    appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage },
                )
            } finally {
                logger.detachAppender(appender)
            }
        }
    }

    private companion object {
        const val ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV =
            "FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET"
        const val CORS_ALLOWED_ORIGINS_ENV = "FINAXIS_CORS_ALLOWED_ORIGINS"
        const val PROTECTED_RESOURCE_URL_ENV = "FINAXIS_PROTECTED_RESOURCE_URL"
        const val TEST_SECRET = "production-test-active-organisation-context-secret"
        const val REFUSED_SECRET = "refused-userinfo-secret"
        const val PRODUCTION_CONFIG_LOCATIONS =
            "optional:file:src/main/resources/application.yaml,optional:file:src/main/resources/application-production.yaml"
    }
}
