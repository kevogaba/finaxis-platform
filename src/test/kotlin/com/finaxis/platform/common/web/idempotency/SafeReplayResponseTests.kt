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

        assertNull(noBody.storedBody)
        assertNull(noBody.toLive().body)
        assertNull(emptyBody.storedBody)
        assertNull(emptyBody.toLive().body)
    }

    @Test
    fun `204 response rejects a nonempty JSON body`() {
        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(204, emptyMap(), "{}"))
        }
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
            "cookies",
            "COOKIES",
            "client_secret",
            "Client-Secret",
            "password_hash",
            "passwordHash",
            "access_token_value",
            "AccessTokenValue",
            "refresh_token_value",
            "REFRESH-TOKEN-VALUE",
            "id_token_value",
            "idTokenValue",
            "session_identifier",
            "Session-Identifier",
            "active_org_context",
            "activeOrgContext",
            "otp",
            "one_time_otp",
            "pin",
            "api_key",
            "private_key",
            "totp_secret",
            "recovery_code",
        ],
    )
    fun `nested prohibited secret and session fields are rejected recursively`(field: String) {
        val body = """{"outer":{"items":[{"$field":"must-not-persist"}]}}"""

        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "secretary",
            "keyholder_name",
            "sessionable",
            "organisation_id",
            "role_code",
            "assigned_branch_ids",
            "bootstrap_failure_code",
        ],
    )
    fun `benign response fields are allowed by token-aware matching`(field: String) {
        val body = """{"$field":"safe metadata"}"""

        assertEquals(
            body,
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body)).storedBody,
        )
    }

    @Test
    fun `stored response is revalidated with the same sensitive alias rule`() {
        assertFailsWith<IllegalArgumentException> {
            factory.fromStored(
                status = 200,
                headers = emptyMap(),
                storedBody = """{"outer":{"client_secret":"must-not-replay"}}""",
            )
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
