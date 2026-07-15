package com.finaxis.platform.lifecycle.adapter.outbound.keycloak

import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import jakarta.ws.rs.client.ClientBuilder
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/** Type-safe Keycloak admin-client configuration. */
@ConfigurationProperties("finaxis.keycloak.admin")
data class KeycloakAdminProperties(
    val serverUrl: String,
    val realm: String,
    val clientId: String,
    val clientSecret: String,
    val enabled: Boolean = false,
    val connectTimeoutMillis: Long = 5_000,
    val readTimeoutMillis: Long = 10_000,
)

/** Registers the Keycloak admin client and disabled fallback gateway. */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties::class)
class KeycloakAdminConfiguration {
    /** Builds the lazily-connecting Keycloak admin client when admin provisioning is enabled. */
    @Bean
    @ConditionalOnProperty("finaxis.keycloak.admin.enabled", havingValue = "true")
    fun keycloakAdminClient(properties: KeycloakAdminProperties): Keycloak {
        val client =
            ClientBuilder
                .newBuilder()
                .connectTimeout(properties.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
        return KeycloakBuilder
            .builder()
            .serverUrl(properties.serverUrl)
            .realm(properties.realm)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(properties.clientId)
            .clientSecret(properties.clientSecret)
            .resteasyClient(client)
            .build()
    }

    /** Fallback gateway used when Keycloak admin provisioning is disabled. */
    @Bean
    @ConditionalOnMissingBean(IdentityProvisioningGateway::class)
    fun disabledIdentityProvisioningGateway(): IdentityProvisioningGateway =
        DisabledIdentityProvisioningGateway()
}

private class DisabledIdentityProvisioningGateway : IdentityProvisioningGateway {
    override fun findOrCreateUser(request: KeycloakUserProvisioningRequest): KeycloakUserRef =
        throw IdentityProvisioningException(DISABLED_MESSAGE)

    override fun sendRequiredActionsEmail(subject: String): Unit =
        throw IdentityProvisioningException(DISABLED_MESSAGE)

    private companion object {
        const val DISABLED_MESSAGE = "Keycloak admin provisioning is disabled."
    }
}
