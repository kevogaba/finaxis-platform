package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

private const val CONFIGURED_POLICY = "default-src 'none'"

/**
 * Regression for a Codex review finding on the design spec: a strict configured CSP
 * (`default-src 'none'`, production's default) blocks Scalar outright, because its HTML carries
 * an inline initializer script with no nonce hook and fetches the OpenAPI document itself.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.headers.content-security-policy=$CONFIGURED_POLICY",
        "finaxis.security.api-docs.public-access-enabled=true",
    ],
)
@AutoConfigureMockMvc
class ContentSecurityPolicyWithPublicDocsIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `a docs-compatible policy replaces the configured one while docs are public`() {
        mockMvc.get("/actuator/health").andExpect {
            header {
                string(
                    "Content-Security-Policy",
                    "default-src 'none'; script-src 'self' 'unsafe-inline'; " +
                        "style-src 'self' 'unsafe-inline'; connect-src 'self'; " +
                        "img-src 'self' data:; font-src 'self' data:",
                )
            }
        }
    }
}

/**
 * Discriminating control for [ContentSecurityPolicyWithPublicDocsIntegrationTests]: with docs
 * access disabled, the operator's configured policy must apply unchanged.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.headers.content-security-policy=$CONFIGURED_POLICY",
        "finaxis.security.api-docs.public-access-enabled=false",
    ],
)
@AutoConfigureMockMvc
class ContentSecurityPolicyWithPrivateDocsIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `the configured policy applies unchanged while docs require authentication`() {
        mockMvc.get("/actuator/health").andExpect {
            header { string("Content-Security-Policy", CONFIGURED_POLICY) }
        }
    }
}
