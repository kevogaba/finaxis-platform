package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.common.application.InvalidOperationException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Posting eligibility, and the error code it finally raises.
 *
 * `accounting.account_not_postable` was published by issue #31 as part of the module's public
 * contract and nothing had ever thrown it. These are the first assertions that it is reachable —
 * which matters, because a published error code no code path can produce is a contract a client
 * writes a branch for and never enters.
 */
class GlAccountPostingPolicyTests {
    @Test
    fun `an active postable account is accepted`() {
        // The positive control: every other assertion here is a rejection.
        GlAccountPostingPolicy.requirePostable(account())
        GlAccountPostingPolicy.requireManualPostingAllowed(
            account().copy(manualPostingAllowed = true),
        )
    }

    @Test
    fun `a header account is refused with the published code`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                GlAccountPostingPolicy.requirePostable(account().copy(usage = AccountUsage.HEADER))
            }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
    }

    @Test
    fun `every non-active status is refused`() {
        listOf(
            GlAccountStatus.DRAFT,
            GlAccountStatus.PENDING_APPROVAL,
            GlAccountStatus.INACTIVE,
        ).forEach { status ->
            val failure =
                assertFailsWith<InvalidOperationException>("$status must not be postable") {
                    GlAccountPostingPolicy.requirePostable(account().copy(status = status))
                }
            assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
        }
    }

    @Test
    fun `a manual entry needs the account to have opted in`() {
        // manual_posting_allowed defaults to false in the schema, so this is the ordinary case for
        // an account fed by a posting rule or a subsidiary ledger rather than an unusual one.
        val failure =
            assertFailsWith<InvalidOperationException> {
                GlAccountPostingPolicy.requireManualPostingAllowed(account())
            }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
    }

    @Test
    fun `a manual entry into an inactive account fails on postability first`() {
        // Guards requireManualPostingAllowed against being written as a bare flag check, which
        // would let a manual journal into a deactivated account that happened to have opted in.
        val failure =
            assertFailsWith<InvalidOperationException> {
                GlAccountPostingPolicy.requireManualPostingAllowed(
                    account().copy(status = GlAccountStatus.INACTIVE, manualPostingAllowed = true),
                )
            }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
    }

    private fun account() =
        GlAccount(
            id = UUID.randomUUID(),
            organisationId = UUID.randomUUID(),
            code = AccountCode("1010"),
            name = "Cash",
            accountClass = AccountClass.ASSET,
            usage = AccountUsage.POSTABLE,
            status = GlAccountStatus.ACTIVE,
        )
}
