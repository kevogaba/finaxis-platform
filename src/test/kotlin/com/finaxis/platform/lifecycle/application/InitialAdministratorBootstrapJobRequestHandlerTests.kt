package com.finaxis.platform.lifecycle.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

class InitialAdministratorBootstrapJobRequestHandlerTests {
    private val bootstrapService = mock(InitialAdministratorBootstrapService::class.java)
    private val handler = InitialAdministratorBootstrapJobRequestHandler(bootstrapService)

    @Test
    fun `handler delegates running to bootstrap service`() {
        val organisationId = UUID.randomUUID()
        val request = InitialAdministratorBootstrapJobRequest(organisationId)

        handler.run(request)

        verify(bootstrapService).bootstrap(organisationId)
    }
}
