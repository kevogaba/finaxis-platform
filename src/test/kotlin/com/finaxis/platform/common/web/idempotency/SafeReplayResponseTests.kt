package com.finaxis.platform.common.web.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeReplayResponseTests {
    private val properties = IdempotencyProperties(maxResponseBodyBytes = 128)
    private val factory = SafeReplayResponseFactory(ObjectMapper(), properties)

    @Test
    fun `safe JSON is validated and preserved exactly`() {
        val exactJson = """{"state": "CREATED", "items":[1,2]}"""

        val replay =
            factory.fromLive(
                IdempotencyResponse(
                    status = 201,
                    headers = mapOf("Location" to "/api/v1/widgets/42"),
                    body = exactJson,
                ),
            )

        assertEquals(exactJson, replay.storedBody)
        assertEquals(exactJson, replay.toLive().body)
    }

    @Test
    fun `empty and no-body success produce an empty safe stored representation`() {
        val noBody = factory.fromLive(IdempotencyResponse(204, emptyMap(), null))
        val emptyBody = factory.fromLive(IdempotencyResponse(204, emptyMap(), ""))

        assertEquals("", noBody.storedBody)
        assertNull(noBody.toLive().body)
        assertEquals("", emptyBody.storedBody)
        assertNull(emptyBody.toLive().body)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "authorization",
            "AUTHORIZATION",
            "cookie",
            "set_cookie",
            "SeT-CoOkIe",
            "password",
            "secret",
            "access_token",
            "AccessToken",
            "refresh_token",
            "id_token",
            "context_token",
            "contextToken",
            "session_id",
            "sessionId",
            "active_organisation_context",
            "activeOrganisationContext",
        ],
    )
    fun `nested prohibited secret and session fields are rejected recursively`(field: String) {
        val body = """{"outer":{"items":[{"$field":"must-not-persist"}]}}"""

        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body))
        }
    }

    @Test
    fun `oversized JSON is rejected by UTF-8 byte length`() {
        val smallFactory =
            SafeReplayResponseFactory(
                ObjectMapper(),
                IdempotencyProperties(maxResponseBodyBytes = 20),
            )

        assertFailsWith<IllegalArgumentException> {
            smallFactory.fromLive(
                IdempotencyResponse(200, emptyMap(), """{"value":"😀😀😀😀"}"""),
            )
        }
    }

    @Test
    fun `non-JSON non-empty live body cannot become a safe replay`() {
        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), "created"))
        }
    }

    @Test
    fun `persistable replay values do not expose a public constructor`() {
        assertTrue(
            SafeReplayResponse::class.java.declaredConstructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers)
            },
        )
    }
}
