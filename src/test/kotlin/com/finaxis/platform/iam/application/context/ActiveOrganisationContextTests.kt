package com.finaxis.platform.iam.application.context

import com.finaxis.platform.common.id.uuidV7
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveOrganisationContextTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC)
    private val service =
        ActiveOrganisationContextService(
            properties =
                ActiveOrganisationContextProperties(
                    secret = "test-secret-with-enough-length-32bytes",
                ),
            clock = clock,
        )

    @Test
    fun `signed active organisation context round trips`() {
        val context =
            ActiveOrganisationContext(
                userId = uuidV7(),
                organisationId = uuidV7(),
                membershipId = uuidV7(),
                branchId = uuidV7(),
            )

        val token = service.issue(context)

        assertEquals(context, service.verify(token))
    }

    @Test
    fun `tampered active organisation context is rejected`() {
        val context =
            ActiveOrganisationContext(
                userId = uuidV7(),
                organisationId = uuidV7(),
                membershipId = uuidV7(),
            )

        val token = service.issue(context).let { "${it.dropLast(1)}x" }

        assertNull(service.verify(token))
    }

    @Test
    fun `malformed and expired active organisation contexts are rejected`() {
        val ttl = Duration.ofSeconds(1)
        val expiredService =
            ActiveOrganisationContextService(
                properties =
                    ActiveOrganisationContextProperties(
                        "test-secret-with-enough-length-32bytes",
                        ttl,
                    ),
                clock = clock,
            )
        val context =
            ActiveOrganisationContext(
                uuidV7(),
                uuidV7(),
                uuidV7(),
            )
        val token = expiredService.issue(context)
        val verifier =
            ActiveOrganisationContextService(
                properties =
                    ActiveOrganisationContextProperties(
                        "test-secret-with-enough-length-32bytes",
                        ttl,
                    ),
                clock = Clock.fixed(Instant.parse("2026-07-04T08:00:02Z"), ZoneOffset.UTC),
            )

        assertNull(service.verify("not-a-valid-token"))
        assertNull(service.verify("invalid-payload.${service.issue(context).substringAfter('.')}"))
        assertNull(verifier.verify(token))
    }

    @Test
    fun `signed malformed payloads are rejected`() {
        val shortPayload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("too.short".toByteArray())
        val invalidExpiryPayload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    "${uuidV7()}.${uuidV7()}.${uuidV7()}.-.not-a-number"
                        .toByteArray(),
                )
        val invalidUuidPayload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("bad.bad.bad.-.1783152000".toByteArray())

        assertNull(service.verify("\$\$\$.${sign("\$\$\$")}"))
        assertNull(service.verify("$shortPayload.${sign(shortPayload)}"))
        assertNull(service.verify("$invalidExpiryPayload.${sign(invalidExpiryPayload)}"))
        assertNull(service.verify("$invalidUuidPayload.${sign(invalidUuidPayload)}"))
    }

    private fun sign(payload: String): String {
        val method = service.javaClass.getDeclaredMethod("sign", String::class.java)
        method.isAccessible = true
        return method.invoke(service, payload) as String
    }
}
