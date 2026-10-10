package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFC 9728 protected-resource metadata (#253): `resource` comes from configuration when set
 * (otherwise Spring's request-derived value, with one startup `WARN`), `authorization_servers` is
 * the JWT decoder's issuer, `bearer_methods_supported` is the `Authorization` header only, and
 * Spring's default mTLS claim is gone.
 */
class ProtectedResourceMetadataTests {
    @Test
    fun `the customizer replaces Spring's defaults with exactly the configured claims`() {
        val metadata =
            springDefaults("http://forged.example.test")
                .also(ProtectedResourceMetadataCustomizer(RESOURCE, listOf(ISSUER))::accept)
                .build()

        assertEquals(
            mapOf(
                "resource" to RESOURCE,
                "authorization_servers" to listOf(ISSUER),
                "bearer_methods_supported" to listOf("header"),
            ),
            metadata.claims,
        )
    }

    @Test
    fun `with no resource configured Spring's request-derived resource is kept`() {
        val metadata =
            springDefaults("https://api.example.test")
                .also(ProtectedResourceMetadataCustomizer(null, listOf(ISSUER))::accept)
                .build()

        assertEquals(
            mapOf(
                "resource" to "https://api.example.test",
                "authorization_servers" to listOf(ISSUER),
                "bearer_methods_supported" to listOf("header"),
            ),
            metadata.claims,
        )
    }

    @Test
    fun `a configured resource keeps the request's path but never its scheme, host or port`() {
        val metadata =
            springDefaults("http://forged.example.test:8443/api/v1/auth/me")
                .also(ProtectedResourceMetadataCustomizer(RESOURCE, listOf(ISSUER))::accept)
                .build()

        assertEquals("$RESOURCE/api/v1/auth/me", metadata.claims["resource"])
    }

    @Test
    fun `with no resource configured a path-suffixed value is kept unchanged`() {
        val metadata =
            springDefaults("https://api.example.test/api/v1")
                .also(ProtectedResourceMetadataCustomizer(null, listOf(ISSUER))::accept)
                .build()

        assertEquals("https://api.example.test/api/v1", metadata.claims["resource"])
    }

    @ParameterizedTest
    @CsvSource(
        "http://h.example, ''",
        "http://h.example/, ''",
        "http://h.example:8443/api/v1/auth/me, /api/v1/auth/me",
        "http://h.example/api/v1/, /api/v1/",
        "http://h.example/a%20b, /a%20b",
        "http://h.example/a/../b, ''",
        "http://h.example/./a, ''",
        "http://h.example/a?x=1, ''",
        "http://h.example/a#f, ''",
        "/relative/path, ''",
        "http://h.example/a b, ''",
        "'', ''",
    )
    fun `only an absolute, normalised path without query or fragment is kept`(
        requestDerived: String,
        expected: String,
    ) {
        assertEquals(expected, ProtectedResourceMetadataCustomizer.pathOf(requestDerived))
    }

    @Test
    fun `a missing or non-string request-derived value keeps no path`() {
        assertEquals("", ProtectedResourceMetadataCustomizer.pathOf(null))
        assertEquals("", ProtectedResourceMetadataCustomizer.pathOf(listOf("http://h/a")))
    }

    @Test
    fun `Spring's filter answers a path-suffixed request with the configured origin plus path`() {
        val configured =
            serve(
                ProtectedResourceMetadataCustomizer(RESOURCE, listOf(ISSUER)),
                "$METADATA_PATH/api/v1/auth/me",
            )
        val unset =
            serve(
                ProtectedResourceMetadataCustomizer(null, listOf(ISSUER)),
                "$METADATA_PATH/api/v1/auth/me",
            )

        JSONAssert.assertEquals(
            EXPECTED_JSON.replace(RESOURCE, "$RESOURCE/api/v1/auth/me"),
            configured.contentAsString,
            JSONCompareMode.STRICT,
        )
        JSONAssert.assertEquals(
            EXPECTED_JSON.replace(RESOURCE, "https://forged.example.test:8443/api/v1/auth/me"),
            unset.contentAsString,
            JSONCompareMode.STRICT,
        )
    }

    @Test
    fun `with no issuer configured the document names no authorization server`() {
        val metadata =
            springDefaults("http://forged.example.test")
                .also(ProtectedResourceMetadataCustomizer(RESOURCE, emptyList())::accept)
                .build()

        assertEquals(
            mapOf("resource" to RESOURCE, "bearer_methods_supported" to listOf("header")),
            metadata.claims,
        )
    }

    @Test
    fun `an issuer that is not a URL fails when the customizer is built, not per request`() {
        assertThrows<IllegalArgumentException> {
            ProtectedResourceMetadataCustomizer(RESOURCE, listOf("not a url"))
        }
    }

    @Test
    fun `the customizer reads the resource and the JWT issuer from configuration`() {
        val resourceServer = OAuth2ResourceServerProperties().apply { jwt.issuerUri = ISSUER }

        val (customizer, warnings) =
            captureWarnings {
                ProtectedResourceMetadataCustomizer.from(
                    ProtectedResourceProperties(" $RESOURCE/ "),
                    resourceServer,
                )
            }

        assertEquals(RESOURCE, customizer.resource)
        assertEquals(listOf(ISSUER), customizer.authorizationServers)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a blank or unset resource falls back to the request and is warned about once`() {
        listOf(null, "", "  ").forEach { value ->
            val (customizer, warnings) =
                captureWarnings {
                    ProtectedResourceMetadataCustomizer.from(
                        ProtectedResourceProperties(value),
                        OAuth2ResourceServerProperties(),
                    )
                }

            assertNull(customizer.resource, "resource <$value>")
            assertEquals(
                listOf(ProtectedResourceMetadataCustomizer.RESOURCE_NOT_CONFIGURED),
                warnings,
            )
        }
        assertTrue(
            ProtectedResourceMetadataCustomizer.RESOURCE_NOT_CONFIGURED.contains(
                "FINAXIS_PROTECTED_RESOURCE_URL",
            ),
        )
    }

    @Test
    fun `a blank or absent issuer gives no authorization server`() {
        listOf(null, "", "  ").forEach { issuer ->
            val resourceServer = OAuth2ResourceServerProperties().apply { jwt.issuerUri = issuer }

            val customizer =
                ProtectedResourceMetadataCustomizer.from(
                    ProtectedResourceProperties(RESOURCE),
                    resourceServer,
                )

            assertEquals(emptyList(), customizer.authorizationServers, "issuer <$issuer>")
        }
    }

    @Test
    fun `Spring's filter serves the customised document whatever host the request names`() {
        val response = serve(ProtectedResourceMetadataCustomizer(RESOURCE, listOf(ISSUER)))

        assertEquals(200, response.status)
        assertEquals("application/json", response.contentType?.substringBefore(';'))
        JSONAssert.assertEquals(EXPECTED_JSON, response.contentAsString, JSONCompareMode.STRICT)
    }

    @Test
    fun `Spring's filter serves the request origin as the resource when none is configured`() {
        val response = serve(ProtectedResourceMetadataCustomizer(null, listOf(ISSUER)))

        JSONAssert.assertEquals(
            EXPECTED_JSON.replace(RESOURCE, "https://forged.example.test:8443"),
            response.contentAsString,
            JSONCompareMode.STRICT,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "HTTPS://API.Finaxis.Example:8443/, https://api.finaxis.example:8443",
        "https://api.finaxis.example:443, https://api.finaxis.example",
        "http://api.finaxis.example:80/, http://api.finaxis.example",
        "http://api.finaxis.example:443, http://api.finaxis.example:443",
        "http://[::1]:8081, http://[::1]:8081",
    )
    fun `an origin is lower-cased without a trailing slash or its scheme's default port`(
        value: String,
        expected: String,
    ) {
        assertEquals(expected, ProtectedResourceMetadataCustomizer.canonicalOrigin(value))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "api.finaxis.example",
            "/relative",
            "ftp://api.finaxis.example",
            "https://",
            "https://api.finaxis.example/api/v1",
            "https://api.finaxis.example/?tenant=1",
            "https://api.finaxis.example/#top",
            "https://user:secret@api.finaxis.example",
            "https://api finaxis.example",
        ],
    )
    fun `a resource that is not a bare http or https origin is refused without repeating it`(
        value: String,
    ) {
        val failure =
            assertThrows<IllegalArgumentException> {
                ProtectedResourceMetadataCustomizer.from(
                    ProtectedResourceProperties(value),
                    OAuth2ResourceServerProperties(),
                )
            }

        // The fixed text, and no chained URISyntaxException (whose message repeats the input).
        assertEquals(ProtectedResourceMetadataCustomizer.INVALID_RESOURCE, failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `the property binds as written, unset included, and never fails binding`() {
        val runner =
            ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration::class.java)

        runner.run { context ->
            assertNull(context.startupFailure)
            assertNull(context.getBean(ProtectedResourceProperties::class.java).resource)
        }
        runner.withPropertyValues("$PROPERTY=https://user:secret@x").run { context ->
            assertNull(context.startupFailure)
        }
    }

    private fun serve(
        customizer: ProtectedResourceMetadataCustomizer,
        path: String = METADATA_PATH,
    ): MockHttpServletResponse {
        val filter =
            OAuth2ProtectedResourceMetadataFilter().apply {
                setProtectedResourceMetadataCustomizer(customizer)
            }
        val request =
            MockHttpServletRequest("GET", path).apply {
                scheme = "https"
                serverName = "forged.example.test"
                serverPort = 8443
            }
        return MockHttpServletResponse().also {
            filter.doFilter(request, it, MockFilterChain())
        }
    }

    /** A builder as Spring's filter prepares it before applying the customizer. */
    private fun springDefaults(requestOrigin: String): OAuth2ProtectedResourceMetadata.Builder =
        OAuth2ProtectedResourceMetadata
            .builder()
            .resource(requestOrigin)
            .bearerMethod("header")
            .tlsClientCertificateBoundAccessTokens(true)

    private fun <T> captureWarnings(action: () -> T): Pair<T, List<String>> {
        val logger =
            LoggerFactory.getLogger(ProtectedResourceMetadataCustomizer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val result = action()
            return result to
                appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProtectedResourceProperties::class)
    class PropertiesConfiguration

    private companion object {
        const val PROPERTY = "finaxis.security.protected-resource.resource"
        const val RESOURCE = "https://api.finaxis.example"
        const val ISSUER = "https://id.finaxis.example/realms/finaxis"
        const val METADATA_PATH = "/.well-known/oauth-protected-resource"
        val EXPECTED_JSON =
            """
            {
              "resource": "$RESOURCE",
              "authorization_servers": ["$ISSUER"],
              "bearer_methods_supported": ["header"]
            }
            """.trimIndent()
    }
}
