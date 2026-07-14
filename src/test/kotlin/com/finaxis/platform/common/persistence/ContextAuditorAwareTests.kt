package com.finaxis.platform.common.persistence

import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID
import kotlin.test.assertEquals

class ContextAuditorAwareTests {
    @Test
    fun `uses the authenticated actor when a request context is present`() {
        val actorId = UUID.fromString("11111111-1111-1111-1111-111111111111")

        RequestContexts.withActor(
            ActorContext(actorId, "subject-1", "local.admin", "admin@finaxis.local"),
        ) {
            assertEquals(actorId, ContextAuditorAware().currentAuditor.orElseThrow())
        }
    }

    @Test
    fun `uses the stable system actor outside a request context`() {
        assertEquals(SystemActor.ID, ContextAuditorAware().currentAuditor.orElseThrow())
    }

    @Test
    fun `installs and clears tenant branch actor and correlation MDC fields`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        val actorId = uuidV7()

        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(organisationId, "finaxis"),
                branch = BranchContext(branchId, "OPERATE"),
                actor = ActorContext(actorId, "subject", "operator", "operator@example.test"),
                correlation = CorrelationContext("request-1", "correlation-1"),
            ),
        ) {
            assertEquals(organisationId.toString(), MDC.get("organisationId"))
            assertEquals(branchId.toString(), MDC.get("branchId"))
            assertEquals(actorId.toString(), MDC.get("actorId"))
            assertEquals("correlation-1", MDC.get("correlationId"))
        }

        assertEquals(null, MDC.get("organisationId"))
        assertEquals(null, MDC.get("branchId"))
    }
}
