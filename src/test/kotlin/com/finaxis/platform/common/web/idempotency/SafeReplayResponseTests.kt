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
    fun `tenant setting response is exempt for its setting key and survives revalidation`() {
        val body = TENANT_SETTING_BODY

        assertEquals(body, factory.fromLive(IdempotencyResponse(200, emptyMap(), body)).storedBody)
        assertEquals(
            body,
            factory.fromStored(status = 200, headers = emptyMap(), storedBody = body).storedBody,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"key":"base_currency"}""",
            """{"key":"base_currency","value":"KES"}""",
            """{"outer":$TENANT_SETTING_BODY}""",
            """[$TENANT_SETTING_BODY]""",
            """{"key":"k","value":"v","value_type":"STRING","sensitive":false,""" +
                """"platform_admin_only":false,"extra":1}""",
            """{"nested":{"key":"k"},"value":"v","value_type":"STRING","sensitive":false,""" +
                """"platform_admin_only":false}""",
        ],
    )
    fun `a bare key outside the exact tenant setting response shape stays rejected`(body: String) {
        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["api_key", "private_key", "access_token"])
    fun `the tenant setting exemption does not cover other sensitive names`(field: String) {
        val body =
            """{"key":"k","value":"v","value_type":"STRING","sensitive":false,""" +
                """"platform_admin_only":false,"$field":"must-not-persist"}"""

        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["pin_code", "api_key", "auth_line", "session", "token"])
    fun `a branch address object may carry free-form keys that look sensitive`(label: String) {
        val body = """{"branch_id":"b-1","status":"DRAFT","address":{"$label":"560001"}}"""

        assertEquals(body, factory.fromLive(IdempotencyResponse(200, emptyMap(), body)).storedBody)
        assertEquals(
            body,
            factory.fromStored(status = 200, headers = emptyMap(), storedBody = body).storedBody,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"api_key":"x","address":{"pin_code":"560001"}}""",
            """{"address":{"pin_code":"560001"},"api_key":"x"}""",
            """{"other":{"pin_code":"560001"}}""",
            """{"other":{"address":{"city":"Nairobi"},"session_id":"x"}}""",
            """{"address_book":{"pin_code":"560001"}}""",
            """{"address":"x","nested":{"client_secret":"y"}}""",
        ],
    )
    fun `the address exemption does not cover sensitive names outside an address object`(
        body: String,
    ) {
        assertFailsWith<IllegalArgumentException> {
            factory.fromLive(IdempotencyResponse(200, emptyMap(), body))
        }
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

    private companion object {
        const val TENANT_SETTING_BODY =
            """{"key":"base_currency","value":"KES","value_type":"CURRENCY",""" +
                """"sensitive":false,"platform_admin_only":false}"""
    }
}
