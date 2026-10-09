package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.net.URI
import kotlin.test.assertNotNull

/**
 * `GET /.well-known/oauth-protected-resource` through the real filter chain (#253): public,
 * unversioned, served before bearer authentication, the active-organisation filter and the rate
 * limiter, and carrying exactly the configured document whatever `Host` the request names. The
 * `401` challenge's `resource_metadata` URL is fetched to prove it advertises this document.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.protected-resource.resource=https://API.finaxis.example/",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=$ISSUER",
    ],
)
@AutoConfigureMockMvc
class ProtectedResourceMetadataIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `an anonymous caller gets the configured document as JSON`() {
        mockMvc.get(METADATA_PATH).andExpectDocument()
    }

    @Test
    fun `a forged Host header does not change the configured resource`() {
        // Forwarded headers need Tomcat's RemoteIpValve, which MockMvc has none of: they are
        // covered over a real Tomcat by ForwardedHeadersIntegrationTests.
        mockMvc
            .get(METADATA_PATH) {
                header("Host", "forged.example.test:8443")
            }.andExpectDocument()
    }

    @Test
    fun `a rejected bearer token and an unselected organisation context do not block it`() {
        mockMvc
            .get(METADATA_PATH) {
                header("Authorization", "Bearer not-a-jwt")
                header("X-Active-Organisation-Context", "not-a-context")
            }.andExpectDocument()
    }

    @Test
    fun `a path-suffixed request gets the configured origin plus that path as its resource`() {
        // RFC 9728 section 3.3: `resource` is identical to the identifier the URL was derived
        // from; only the path comes from the request, never the forged host.
        mockMvc
            .get("$METADATA_PATH/api/v1/auth/me") {
                header("Host", "forged.example.test:8443")
            }.andExpectDocument("https://api.finaxis.example/api/v1/auth/me")
    }

    @Test
    fun `the missing-token challenge advertises a URL that serves this document`() {
        val challenge =
            mockMvc
                .get("/api/v1/auth/me")
                .andExpect { status { isUnauthorized() } }
                .andReturn()
                .response
                .getHeader("WWW-Authenticate")
                .orEmpty()
        val advertised = assertNotNull(METADATA.find(challenge)?.groupValues?.get(1), challenge)

        mockMvc.get(URI.create(advertised)).andExpectDocument()
    }

    private fun ResultActionsDsl.andExpectDocument(resource: String = RESOURCE): ResultActionsDsl =
        andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/json") }
            content { json(expectedJson(resource), JsonCompareMode.STRICT) }
            header { doesNotExist("WWW-Authenticate") }
            header { string("X-Content-Type-Options", "nosniff") }
        }

    private companion object {
        const val METADATA_PATH = "/.well-known/oauth-protected-resource"
        val METADATA = Regex("""resource_metadata="([^"]*)"""")
        const val RESOURCE = "https://api.finaxis.example"

        fun expectedJson(resource: String): String =
            """
            {
              "resource": "$resource",
              "authorization_servers": ["$ISSUER"],
              "bearer_methods_supported": ["header"]
            }
            """.trimIndent()
    }
}

private const val ISSUER = "https://id.finaxis.example/realms/finaxis"
