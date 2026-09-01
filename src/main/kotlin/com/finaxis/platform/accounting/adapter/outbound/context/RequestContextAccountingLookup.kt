package com.finaxis.platform.accounting.adapter.outbound.context

import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.RequestContexts

/**
 * Reads the ambient request context and narrows it to the tenant, branch and actor accounting
 * needs. The only class in the accounting module permitted to touch the thread-local context.
 */
class RequestContextAccountingLookup : AccountingContextLookup {
    override fun current(): AccountingContext? {
        val context = RequestContexts.current() ?: return null
        val organisationId = context.tenant?.organisationId
        val actorId = context.actor?.userId
        return if (organisationId == null || actorId == null) {
            null
        } else {
            AccountingContext(
                organisationId = organisationId,
                branchId = context.branch?.branchId,
                actorId = actorId,
                correlationId = context.correlation?.correlationId,
            )
        }
    }

    override fun require(): AccountingContext =
        current()
            ?: throw InvalidOperationException(
                code = PostingErrorCodes.NO_ACTIVE_CONTEXT,
                safeDetail = "No active organisation and actor context is available.",
            )
}
