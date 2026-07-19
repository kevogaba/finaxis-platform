package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.application.RequestTooLargeException
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
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
    fun `query encoding cannot create delimiter collisions`() {
        authenticate("actor-a")
        val encodedDelimiter = hasher.fingerprint(request("a%26b=c", "{}"))
        val separatePair = hasher.fingerprint(request("a=&b=c", "{}"))
        val encodedEquals = hasher.fingerprint(request("a%3Db=c", "{}"))
        val literalEquals = hasher.fingerprint(request("a=b%3Dc", "{}"))

        assertNotEquals(encodedDelimiter, separatePair)
        assertNotEquals(encodedEquals, literalEquals)
    }

    @Test
    fun `duplicate and empty query values remain structured and order independent`() {
        authenticate("actor-a")
        val first = hasher.fingerprint(request("a=1&a=&b", "{}"))
        val reordered = hasher.fingerprint(request("b=&a=&a=1", "{}"))

        assertEquals(first, reordered)
    }

    @Test
    fun `malformed percent encoding is a safe client error`() {
        authenticate("actor-a")

        val failure =
            assertFailsWith<InvalidRequestException> {
                hasher.fingerprint(request("broken=%GG", "{}"))
            }

        assertEquals("INVALID_QUERY_ENCODING", failure.code)
    }

    @Test
    fun `nested platform targets participate in the fingerprint`() {
        authenticate("actor-a")
        val firstTenant = UUID.randomUUID()
        val secondTenant = UUID.randomUUID()

        val first = hasher.fingerprint(request(null, "{}", path = "/api/v1/$firstTenant/branches"))
        val second =
            hasher.fingerprint(
                request(null, "{}", path = "/api/v1/$secondTenant/branches"),
            )

        assertNotEquals(first, second)
    }

    @Test
    fun `keycloak subject remains the actor after tenant principal enrichment`() {
        authenticate("actor-a")
        val jwtFingerprint = hasher.fingerprint(request(null, "{}"))
        SecurityContextHolder.getContext().authentication =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = java.util.UUID.randomUUID(),
                    keycloakSubject = "actor-a",
                    organisationId = java.util.UUID.randomUUID(),
                    membershipId = java.util.UUID.randomUUID(),
                    email = null,
                    fullName = null,
                    permissions = emptySet(),
                ),
            )

        assertEquals(jwtFingerprint, hasher.fingerprint(request(null, "{}")))
    }

    @Test
    fun `cache overflow fails safely before producing a partial hash`() {
        authenticate("actor-a")
        val smallHasher =
            CanonicalRequestHasher(
                ApiJsonCodec(),
                IdempotencyProperties(maxRequestBodyBytes = 8),
            )
        assertFailsWith<RequestTooLargeException> {
            smallHasher.fingerprint(
                request(null, """{"value":123456}""", cacheLimit = 9),
            )
        }
    }

    @Test
    fun `bounded wrapper consumes at most maximum plus one upstream bytes`() {
        val source = CountingRequest(ByteArray(100))

        assertFailsWith<RequestTooLargeException> {
            BoundedContentCachingRequestWrapper(source, 8)
        }

        assertEquals(9, source.bytesRead)
    }

    @Test
    fun `request body configuration has a sane hard upper bound`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyProperties(maxRequestBodyBytes = 10_485_761)
        }
    }

    private fun request(
        query: String?,
        body: String,
        cacheLimit: Int = 1_024,
        path: String = "/api/v1//widgets/",
    ): BoundedContentCachingRequestWrapper {
        val request =
            MockHttpServletRequest("POST", path).apply {
                queryString = query
                setContent(body.toByteArray())
            }
        return BoundedContentCachingRequestWrapper(request, cacheLimit)
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

private class CountingRequest(
    private val bytes: ByteArray,
) : MockHttpServletRequest("POST", "/api/v1/test") {
    var bytesRead: Int = 0
        private set

    override fun getInputStream(): ServletInputStream =
        object : ServletInputStream() {
            private var position = 0

            override fun read(): Int {
                if (position >= bytes.size) return -1
                bytesRead++
                return bytes[position++].toInt()
            }

            override fun read(
                target: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (position >= bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(target, offset, position, position + count)
                position += count
                bytesRead += count
                return count
            }

            override fun isFinished(): Boolean = position >= bytes.size

            override fun isReady(): Boolean = true

            override fun setReadListener(readListener: ReadListener) = Unit
        }
}
