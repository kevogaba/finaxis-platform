package com.finaxis.platform.lifecycle.adapter.outbound.keycloak

import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.CreatedResponseUtil
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.stereotype.Component

/** Keycloak Admin REST implementation of identity provisioning with circuit-breaker protection. */
@Component
@ConditionalOnProperty("finaxis.keycloak.admin.enabled", havingValue = "true")
class KeycloakAdminGateway(
    private val keycloak: Keycloak,
    private val properties: KeycloakAdminProperties,
    circuitBreakerFactory: CircuitBreakerFactory<*, *>,
) : IdentityProvisioningGateway {
    private val circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME)

    /** Finds by email then username before creating a Keycloak user. */
    override fun findOrCreateUser(request: KeycloakUserProvisioningRequest): KeycloakUserRef {
        val users = usersResource()
        findByEmail(users, request.email)?.let { return KeycloakUserRef(it, created = false) }
        findByUsername(users, request.username)?.let { return KeycloakUserRef(it, created = false) }
        return KeycloakUserRef(createUser(users, request), created = true)
    }

    /** Sends a Keycloak verify-email required action to the subject. */
    override fun sendRequiredActionsEmail(subject: String) {
        protectedCall("send required actions email") {
            keycloak
                .realm(properties.realm)
                .users()
                .get(subject)
                .executeActionsEmail(listOf(VERIFY_EMAIL))
        }
    }

    private fun usersResource(): UsersResource = keycloak.realm(properties.realm).users()

    private fun findByEmail(
        users: UsersResource,
        email: String,
    ): String? =
        protectedCall("search user by email") { users.searchByEmail(email, true) }
            .firstOrNull()
            ?.id

    private fun findByUsername(
        users: UsersResource,
        username: String,
    ): String? =
        protectedCall("search user by username") { users.searchByUsername(username, true) }
            .firstOrNull()
            ?.id

    private fun createUser(
        users: UsersResource,
        request: KeycloakUserProvisioningRequest,
    ): String =
        protectedCall("create user") {
            val response =
                users.create(
                    UserRepresentation().apply {
                        email = request.email
                        username = request.username
                        firstName = request.displayName
                        isEnabled = true
                        isEmailVerified = false
                    },
                )
            response.useCreatedId()
        }

    private fun <T> protectedCall(
        action: String,
        supplier: () -> T,
    ): T =
        circuitBreaker.run(
            { supplier() },
            { cause ->
                throw IdentityProvisioningException("Keycloak admin failed to $action.", cause)
            },
        )

    private fun Response.useCreatedId(): String =
        use { response -> CreatedResponseUtil.getCreatedId(response) }

    private companion object {
        const val CIRCUIT_BREAKER_NAME = "keycloakAdmin"
        const val VERIFY_EMAIL = "VERIFY_EMAIL"
    }
}
