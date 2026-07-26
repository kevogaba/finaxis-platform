package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.PostgresTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.rate-limit.policies.auth-selection.capacity=1",
        "finaxis.rate-limit.policies.auth-selection.refill-tokens=1",
        "finaxis.rate-limit.policies.auth-selection.refill-period=1h",
    ],
)
@AutoConfigureMockMvc
class IdempotencyRateLimitOrderingIntegrationTests {
    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    fun `distributed rate limit rejection consumes no request body bytes`() {
        val subject = UUID.randomUUID().toString()
        mockMvc
            .post(SELECT_ORGANISATION_PATH) {
                with(jwt().jwt { token -> token.subject(subject) })
                contentType = MediaType.APPLICATION_JSON
                content = ORGANISATION_BODY
            }.andExpect { status { isForbidden() } }
        val requestBuilder = CountingUnknownLengthRequestBuilder()

        mockMvc
            .perform(
                requestBuilder
                    .uri(SELECT_ORGANISATION_PATH)
                    .with(jwt().jwt { token -> token.subject(subject) })
                    .header("Transfer-Encoding", "chunked")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ORGANISATION_BODY),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .status()
                    .isTooManyRequests,
            )

        assertThat(requestBuilder.bytesRead).isZero()
    }

    private companion object {
        const val SELECT_ORGANISATION_PATH = "/api/v1/auth/select-organisation"
        const val ORGANISATION_BODY =
            """{"organisation_id":"22222222-2222-2222-2222-222222222222"}"""
    }
}
