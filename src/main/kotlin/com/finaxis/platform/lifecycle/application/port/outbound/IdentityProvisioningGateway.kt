package com.finaxis.platform.lifecycle.application.port.outbound

/**
 * Outbound identity-provider provisioning port owned by lifecycle application workflows.
 */
interface IdentityProvisioningGateway {
    /**
     * Finds an existing Keycloak user by email and username before creating a new user.
     *
     * Implementations must never create a duplicate identity for the same email or username.
     */
    fun findOrCreateUser(request: KeycloakUserProvisioningRequest): KeycloakUserRef

    /** Sends Keycloak required-action email for the provisioned subject. */
    fun sendRequiredActionsEmail(subject: String)
}

/** Request needed to provision a Keycloak user without handling any password material. */
data class KeycloakUserProvisioningRequest(
    val email: String,
    val username: String,
    val displayName: String,
)

/** Reference to an existing or newly-created Keycloak user. */
data class KeycloakUserRef(
    val subject: String,
    val created: Boolean,
)

/** Retryable identity provisioning failure raised by outbound identity adapters. */
class IdentityProvisioningException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
