package com.finaxis.platform.accounting.adapter.outbound.context

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit coverage for the only accounting class permitted to read the ambient thread-local context.
 */
class RequestContextAccountingLookupTests {
    private val lookup = RequestContextAccountingLookup()

    @AfterEach
    fun clearContext() {
        RequestContexts.clear()
    }

    @Test
    fun `current returns null when no context is installed`() {
        RequestContexts.clear()

        assertNull(lookup.current())
    }

    @Test
    fun `current returns null when the context carries no tenant`() {
        RequestContexts.with(RequestContext(actor = actor(ACTOR_ID))) {
            assertNull(lookup.current())
        }
    }

    @Test
    fun `current returns null when the context carries no actor`() {
        RequestContexts.with(RequestContext(tenant = TenantContext(ORGANISATION_ID))) {
            assertNull(lookup.current())
        }
    }

    @Test
    fun `current narrows a full context to organisation branch actor and correlation`() {
        val branchId = uuidV7()
        val context =
            RequestContext(
                tenant = TenantContext(ORGANISATION_ID),
                branch = BranchContext(branchId),
                actor = actor(ACTOR_ID),
                correlation = CorrelationContext(requestId = "req-1", correlationId = "corr-1"),
            )

        RequestContexts.with(context) {
            val accounting = checkNotNull(lookup.current())
            assertEquals(ORGANISATION_ID, accounting.organisationId)
            assertEquals(branchId, accounting.branchId)
            assertEquals(ACTOR_ID, accounting.actorId)
            assertEquals("corr-1", accounting.correlationId)
        }
    }

    @Test
    fun `current succeeds with a null branch when no branch is selected`() {
        val context =
            RequestContext(tenant = TenantContext(ORGANISATION_ID), actor = actor(ACTOR_ID))

        RequestContexts.with(context) {
            val accounting = checkNotNull(lookup.current())
            assertNull(accounting.branchId)
            assertEquals(ORGANISATION_ID, accounting.organisationId)
        }
    }

    @Test
    fun `require raises a typed failure when no context is installed`() {
        RequestContexts.clear()

        val failure = assertFailsWith<InvalidOperationException> { lookup.require() }

        assertEquals(PostingErrorCodes.NO_ACTIVE_CONTEXT, failure.code)
    }

    @Test
    fun `the previous context is restored after a scoped block`() {
        RequestContexts.clear()

        RequestContexts.with(
            RequestContext(tenant = TenantContext(ORGANISATION_ID), actor = actor(ACTOR_ID)),
        ) {
            checkNotNull(lookup.current())
        }

        assertNull(lookup.current(), "the scoped context must not leak past its block")
    }

    private fun actor(userId: UUID) =
        ActorContext(
            userId = userId,
            externalSubject = null,
            username = null,
            email = null,
        )

    private companion object {
        val ORGANISATION_ID: UUID = uuidV7()
        val ACTOR_ID: UUID = uuidV7()
    }
}
