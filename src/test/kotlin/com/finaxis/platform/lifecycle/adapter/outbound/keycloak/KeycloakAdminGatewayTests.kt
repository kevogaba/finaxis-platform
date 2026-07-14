package com.finaxis.platform.lifecycle.adapter.outbound.keycloak

import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.UserRepresentation
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder
import java.net.URI
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeycloakAdminGatewayTests {
    private val keycloak = mock(Keycloak::class.java)
    private val realm = mock(RealmResource::class.java)
    private val users = mock(UsersResource::class.java)
    private val user = mock(UserResource::class.java)
    private val circuitBreakers = RecordingCircuitBreakerFactory()
    private val gateway =
        KeycloakAdminGateway(
            keycloak,
            KeycloakAdminProperties(
                serverUrl = "http://localhost:8080",
                realm = "finaxis",
                clientId = "finaxis-platform-admin",
                clientSecret = "secret",
                enabled = true,
            ),
            circuitBreakers,
        )

    @Test
    fun `finds existing user by email before creating`() {
        val existing = UserRepresentation()
        existing.id = "subject-1"
        `when`(keycloak.realm("finaxis")).thenReturn(realm)
        `when`(realm.users()).thenReturn(users)
        `when`(users.searchByEmail("member@example.test", true)).thenReturn(listOf(existing))

        val result =
            gateway.findOrCreateUser(
                KeycloakUserProvisioningRequest(
                    email = "member@example.test",
                    username = "member",
                    displayName = "Member One",
                ),
            )

        assertEquals("subject-1", result.subject)
        assertFalse(result.created)
        assertEquals(listOf("keycloakAdmin"), circuitBreakers.createdIds)
        verify(users).searchByEmail("member@example.test", true)
        verifyNoInteractions(user)
    }

    @Test
    fun `searches username before creating missing user`() {
        val response = Response.created(URI.create("http://keycloak/users/subject-2")).build()
        `when`(keycloak.realm("finaxis")).thenReturn(realm)
        `when`(realm.users()).thenReturn(users)
        `when`(users.searchByEmail("member@example.test", true)).thenReturn(emptyList())
        `when`(users.searchByUsername("member", true)).thenReturn(emptyList())
        `when`(users.create(any(UserRepresentation::class.java))).thenReturn(response)

        val result =
            gateway.findOrCreateUser(
                KeycloakUserProvisioningRequest(
                    email = "member@example.test",
                    username = "member",
                    displayName = "Member One",
                ),
            )

        assertEquals("subject-2", result.subject)
        assertTrue(result.created)
        assertEquals(listOf("keycloakAdmin"), circuitBreakers.createdIds)
        verify(users).searchByEmail("member@example.test", true)
        verify(users).searchByUsername("member", true)
        verify(users).create(any(UserRepresentation::class.java))
    }

    @Test
    fun `translates a raw Keycloak failure into IdentityProvisioningException via the fallback`() {
        val failingCircuitBreakers = FailingCircuitBreakerFactory()
        val gatewayWithFailingBreaker =
            KeycloakAdminGateway(
                keycloak,
                KeycloakAdminProperties(
                    serverUrl = "http://localhost:8080",
                    realm = "finaxis",
                    clientId = "finaxis-platform-admin",
                    clientSecret = "secret",
                    enabled = true,
                ),
                failingCircuitBreakers,
            )
        val keycloakFailure = RuntimeException("Keycloak admin REST call failed")
        `when`(keycloak.realm("finaxis")).thenReturn(realm)
        `when`(realm.users()).thenReturn(users)
        `when`(users.searchByEmail("member@example.test", true)).thenThrow(keycloakFailure)

        val thrown =
            assertFailsWith<IdentityProvisioningException> {
                gatewayWithFailingBreaker.findOrCreateUser(
                    KeycloakUserProvisioningRequest(
                        email = "member@example.test",
                        username = "member",
                        displayName = "Member One",
                    ),
                )
            }

        assertEquals("Keycloak admin failed to search user by email.", thrown.message)
        assertSame(keycloakFailure, thrown.cause)
        assertEquals(listOf("keycloakAdmin"), failingCircuitBreakers.createdIds)
    }
}

private class RecordingCircuitBreakerFactory :
    CircuitBreakerFactory<Any, ConfigBuilder<Any>>() {
    val createdIds = mutableListOf<String>()

    override fun create(id: String): CircuitBreaker {
        createdIds += id
        return RecordingCircuitBreaker()
    }

    override fun configBuilder(id: String): ConfigBuilder<Any> = ConfigBuilder { Any() }

    override fun configureDefault(defaultConfiguration: Function<String, Any>) = Unit

    override fun configure(
        configure: Consumer<ConfigBuilder<Any>>,
        vararg ids: String,
    ) = Unit
}

private class RecordingCircuitBreaker : CircuitBreaker {
    override fun <T : Any> run(
        toRun: Supplier<T>,
        fallback: Function<Throwable?, T>,
    ): T = toRun.get()
}

/**
 * A [CircuitBreakerFactory] whose breakers behave like a real one on failure: when [toRun]
 * throws, the exception is routed to [fallback] instead of propagating directly. This is what
 * lets tests exercise [KeycloakAdminGateway]'s fallback-based exception translation, which
 * [RecordingCircuitBreaker] (always calling [toRun] and never [fallback]) cannot cover.
 */
private class FailingCircuitBreakerFactory : CircuitBreakerFactory<Any, ConfigBuilder<Any>>() {
    val createdIds = mutableListOf<String>()

    override fun create(id: String): CircuitBreaker {
        createdIds += id
        return FailingCircuitBreaker()
    }

    override fun configBuilder(id: String): ConfigBuilder<Any> = ConfigBuilder { Any() }

    override fun configureDefault(defaultConfiguration: Function<String, Any>) = Unit

    override fun configure(
        configure: Consumer<ConfigBuilder<Any>>,
        vararg ids: String,
    ) = Unit
}

private class FailingCircuitBreaker : CircuitBreaker {
    override fun <T : Any> run(
        toRun: Supplier<T>,
        fallback: Function<Throwable?, T>,
    ): T =
        try {
            toRun.get()
        } catch (ex: Throwable) {
            fallback.apply(ex)
        }
}
