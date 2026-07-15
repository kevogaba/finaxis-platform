package com.finaxis.platform.iam.adapter.outbound.keycloak

import org.keycloak.admin.client.KeycloakBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Guards the Keycloak admin-client classpath and constructor API for future provisioning work.
 */
class KeycloakAdminClientAvailabilityTest {
    @Test
    fun `admin client builds without contacting Keycloak`() {
        val keycloak =
            KeycloakBuilder
                .builder()
                .serverUrl("http://localhost:8080")
                .realm("finaxis")
                .clientId("finaxis-admin")
                .grantType("client_credentials")
                .clientSecret("x")
                .build()

        try {
            assertNotNull(keycloak)
        } finally {
            keycloak.close()
        }
    }
}
