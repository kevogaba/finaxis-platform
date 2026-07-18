package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.RequestTooLargeException
import com.finaxis.platform.common.web.api.ApiJsonCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.util.ContentCachingRequestWrapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CanonicalRequestHasherTests {
    private val hasher = CanonicalRequestHasher(ApiJsonCodec(), IdempotencyProperties())

    @AfterEach
    fun clearSecurity() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `json fields and query pairs are canonicalized before hashing`() {
        authenticate("actor-a")
        val first = request("b=2&a=1", """{"z":2,"a":{"b":1,"a":0}}""")
        val reordered = request("a=1&b=2", """{"a":{"a":0,"b":1},"z":2}""")

        assertEquals(hasher.fingerprint(first), hasher.fingerprint(reordered))
    }

    @Test
    fun `actor and payload changes produce distinct fingerprints`() {
        authenticate("actor-a")
        val baseline = hasher.fingerprint(request(null, """{"value":1}"""))
        val changedBody = hasher.fingerprint(request(null, """{"value":2}"""))
        authenticate("actor-b")
        val changedActor = hasher.fingerprint(request(null, """{"value":1}"""))

        assertNotEquals(baseline, changedBody)
        assertNotEquals(baseline, changedActor)
    }

    @Test
    fun `cache overflow fails safely before producing a partial hash`() {
        authenticate("actor-a")
        val smallHasher =
            CanonicalRequestHasher(
                ApiJsonCodec(),
                IdempotencyProperties(maxRequestBodyBytes = 8),
            )
        val request = request(null, """{"value":123456}""", cacheLimit = 9)

        assertFailsWith<RequestTooLargeException> { smallHasher.fingerprint(request) }
    }

    private fun request(
        query: String?,
        body: String,
        cacheLimit: Int = 1_024,
    ): ContentCachingRequestWrapper {
        val request =
            MockHttpServletRequest("POST", "/api/v1//widgets/").apply {
                queryString = query
                setContent(body.toByteArray())
            }
        return ContentCachingRequestWrapper(request, cacheLimit).also {
            it.inputStream.readAllBytes()
        }
    }

    private fun authenticate(subject: String) {
        val jwt =
            Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }
}
