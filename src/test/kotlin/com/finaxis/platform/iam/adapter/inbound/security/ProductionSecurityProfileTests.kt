package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals

class ProductionSecurityProfileTests {
    @Test
    fun `production profile starts when deployment secrets are supplied`() {
        productionContext(
            "$ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV=$TEST_SECRET",
            "$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example",
        ).use { context ->
            assertEquals(
                TEST_SECRET,
                context.getBean(ActiveOrganisationContextProperties::class.java).secret,
            )
        }
    }

    @Test
    fun `production profile fails fast when active organisation secret is absent`() {
        assertThrows<Exception> {
            productionContext("$CORS_ALLOWED_ORIGINS_ENV=https://app.finaxis.example").use { }
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ActiveOrganisationContextProperties::class)
    private class ProductionSecurityProfileTestApplication

    private companion object {
        const val ACTIVE_ORGANISATION_CONTEXT_SECRET_ENV =
            "FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET"
        const val CORS_ALLOWED_ORIGINS_ENV = "FINAXIS_CORS_ALLOWED_ORIGINS"
        const val TEST_SECRET = "production-test-active-organisation-context-secret"
        const val PRODUCTION_CONFIG_LOCATIONS =
            "optional:file:src/main/resources/application.yaml,optional:file:src/main/resources/application-production.yaml"
    }
}
