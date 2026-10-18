package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The resend job (#274): the linked subject, and only while the account is still invited. */
class KeycloakInvitationResendJobRequestHandlerTests {
    private val gateway = mock<IdentityProvisioningGateway>()
    private val store = mock<UserProvisioningStore>()
    private val handler = KeycloakInvitationResendJobRequestHandler(gateway, store)
    private val userId = uuidV7()
    private val request = KeycloakInvitationResendJobRequest(uuidV7(), userId, "key", 2)

    @Test
    fun `an invited account is emailed through its linked subject, with nothing created`() {
        whenever(store.userStatus(userId)).doReturn(UserLifecycleState.INVITED)
        whenever(store.linkedKeycloakSubject(userId)).doReturn("subject-1")

        handler.run(request)

        verify(gateway).sendRequiredActionsEmail("subject-1")
        verify(gateway, never()).findOrCreateUser(org.mockito.kotlin.any())
    }

    @Test
    fun `an account that signed in since the job was queued is not emailed`() {
        whenever(store.userStatus(userId)).doReturn(UserLifecycleState.ACTIVE)

        handler.run(request)

        verify(gateway, never()).sendRequiredActionsEmail(org.mockito.kotlin.any())
    }

    @Test
    fun `an account with no linked identity fails the job instead of creating one`() {
        whenever(store.userStatus(userId)).doReturn(UserLifecycleState.INVITED)
        whenever(store.linkedKeycloakSubject(userId)).doReturn(null)

        assertThrows<SanitisedJobFailureException> { handler.run(request) }

        verify(gateway, never()).findOrCreateUser(org.mockito.kotlin.any())
        verify(gateway, never()).sendRequiredActionsEmail(org.mockito.kotlin.any())
    }

    @Test
    fun `a keycloak failure leaves jobrunr to retry, without the message`() {
        whenever(store.userStatus(userId)).doReturn(UserLifecycleState.INVITED)
        whenever(store.linkedKeycloakSubject(userId)).doReturn("subject-1")
        whenever(gateway.sendRequiredActionsEmail("subject-1"))
            .doThrow(IllegalStateException("mail to ann@example.test failed"))

        val failure = assertThrows<SanitisedJobFailureException> { handler.run(request) }

        assert(failure.message?.contains("ann@example.test") != true)
    }
}
