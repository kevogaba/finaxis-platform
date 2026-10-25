package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jobs stored by the release before membership generations (ADR 0037) carry no `generation`;
 * JobRunr deserialises them with the JVM-overload defaults, which must read as generation 0 so a
 * job already queued for a live, never-revoked membership is processed as it always was.
 */
class JobRequestGenerationCompatibilityTests {
    private val mapper = JacksonJsonMapper()

    @Test
    fun `an application invite stored without a generation reads as generation zero`() {
        val request =
            ApplicationInviteJobRequest(
                organisationId = uuidV7(),
                membershipId = uuidV7(),
                userId = uuidV7(),
                email = "member@example.test",
                dispatchKey = "user:org:APPLICATION_INVITE",
                generation = 4,
            )
        val json = mapper.serialize(request)
        assertTrue(json.contains("\"generation\":4"), json)

        val stored = json.replace(Regex(",?\"generation\":4"), "")
        val read = mapper.deserialize(stored, ApplicationInviteJobRequest::class.java)

        assertEquals(request.copy(generation = 0), read)
        assertEquals(
            4,
            mapper.deserialize(json, ApplicationInviteJobRequest::class.java).generation,
        )
    }

    @Test
    fun `a Keycloak provisioning stored without a generation reads as generation zero`() {
        val request =
            KeycloakUserProvisioningJobRequest(
                organisationId = uuidV7(),
                membershipId = uuidV7(),
                userId = uuidV7(),
                email = "member@example.test",
                username = "member",
                displayName = "Member",
                sendKeycloakInvite = true,
                dispatchKey = "org:user:KEYCLOAK_PROVISIONING",
                actorId = uuidV7(),
                bootstrapRequestId = "BOOTSTRAP-1",
                bootstrapAttempt = 2,
                generation = 3,
            )
        val json = mapper.serialize(request)
        assertTrue(json.contains("\"generation\":3"), json)

        val stored = json.replace(Regex(",?\"generation\":3"), "")
        val read = mapper.deserialize(stored, KeycloakUserProvisioningJobRequest::class.java)

        assertEquals(request.copy(generation = 0), read)
        assertEquals(
            3,
            mapper.deserialize(json, KeycloakUserProvisioningJobRequest::class.java).generation,
        )
    }

    @Test
    fun `generation zero keeps every dispatch key exactly as earlier releases wrote it`() {
        val org = uuidV7()
        val user = uuidV7()

        assertEquals("$org:$user:KEYCLOAK_PROVISIONING", keycloakDispatchKey(org, user))
        assertEquals("$org:$user:KEYCLOAK_PROVISIONING", keycloakDispatchKey(org, user, 0))
        assertEquals("$user:$org:APPLICATION_INVITE", applicationInviteDispatchKey(org, user))
        assertEquals("$user:$org:APPLICATION_INVITE", applicationInviteDispatchKey(org, user, 0))
        assertEquals("$org:$user:KEYCLOAK_PROVISIONING:G2", keycloakDispatchKey(org, user, 2))
        assertEquals("$user:$org:APPLICATION_INVITE:G1", applicationInviteDispatchKey(org, user, 1))
    }
}
