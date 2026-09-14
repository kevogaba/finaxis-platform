package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.adapter.outbound.persistence.FiscalCalendarFixture
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.application.GlAccountLifecycleService
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.GlAccountTransitionCommand
import com.finaxis.platform.accounting.application.Patch
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Replacing a wrongly classified control account (#91).
 *
 * `uq_gl_account_control_kind` holds a sub-ledger class for the life of the row, deliberately: a
 * [SubledgerProofQuery] names the class rather than the account, so a second classified account -
 * deactivated or not - could be proven against an aggregate that is not its own. Without a way to
 * release the class that permanence is a trap, because an approved account takes only a name or
 * description change and a withdrawn one used to take none at all: classify the wrong account once
 * and the tenant could never configure another for that class.
 *
 * The way out is narrow on purpose - withdrawn first, nothing else changed, and only while the
 * account never carried a posting - and this suite pins every step of it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ControlAccountReplacementIntegrationTests(
    private val lifecycle: GlAccountLifecycleService,
    private val chart: ChartOfAccountsService,
    private val accounts: GlAccountStore,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val journals = JournalSchemaFixture(dsl)
    private val calendar = FiscalCalendarFixture(dsl)

    @Test
    fun `a withdrawn control account releases its class so a replacement can be configured`() {
        val organisationId = organisation("replacement")
        val wrong = controlDraft(organisationId, "2100")
        withRequestContext { lifecycle.submit(command(organisationId, wrong.id)) }
        withRequestContext { lifecycle.approve(command(organisationId, wrong.id, checker())) }

        // While it is live the class is held.
        val whileActive =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.create(controlCommand(organisationId, "2101")) }
            }
        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_DUPLICATE, whileActive.code)

        withRequestContext {
            lifecycle.deactivate(command(organisationId, wrong.id, reason = "Mis-classified"))
        }

        // Deactivation alone does not free the class - a proof of a date it was live is still a
        // legitimate question, and it must not be answered against whatever replaced it.
        val whileInactive =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.create(controlCommand(organisationId, "2101")) }
            }
        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_DUPLICATE, whileInactive.code)

        // Releasing the classification is the one amendment a withdrawn account accepts, and it is
        // all it accepts: nothing else may ride along.
        val smuggled =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    chart.update(
                        release(organisationId, wrong.id).copy(name = "Renamed on the way out"),
                    )
                }
            }
        assertEquals(NOT_AMENDABLE, smuggled.code)

        withRequestContext { chart.update(release(organisationId, wrong.id)) }
        val released = accounts.findById(organisationId, wrong.id)
        assertEquals(false, released?.isControlAccount)
        assertNull(released?.controlSubledgerKind)

        // And now the replacement is created and approved as any other account.
        val replacement =
            withRequestContext { chart.create(controlCommand(organisationId, "2101")) }
        withRequestContext { lifecycle.submit(command(organisationId, replacement.id)) }
        val approved =
            withRequestContext {
                lifecycle.approve(
                    command(organisationId, replacement.id, checker()),
                )
            }

        assertEquals(GlAccountStatus.ACTIVE, approved.status)
        assertEquals(ControlSubledgerKind.SAVINGS_DEPOSITS, approved.controlSubledgerKind)
    }

    @Test
    fun `an active control account cannot be de-classified without being withdrawn first`() {
        // Deactivation is a maker-checker transition with its own HIGH audit event. Letting an
        // ACTIVE account drop its classification directly would route around that control and pull
        // the class out from under proofs that are still being run against it.
        val organisationId = organisation("active-declassify")
        val control = controlDraft(organisationId, "2100")
        withRequestContext { lifecycle.submit(command(organisationId, control.id)) }
        withRequestContext { lifecycle.approve(command(organisationId, control.id, checker())) }

        val refused =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.update(release(organisationId, control.id)) }
            }

        assertEquals(NOT_AMENDABLE, refused.code)
        assertEquals(true, accounts.findById(organisationId, control.id)?.isControlAccount)
    }

    @Test
    fun `a control account that has been posted to keeps its class even once withdrawn`() {
        // The escape hatch is for a mis-classification caught before it carried anything. A class
        // that has carried postings has to stay on the account that carried them: INV-14 compares
        // the whole sub-ledger aggregate against the one control account of the class, so a
        // released account's balance would sit outside the class while the positions behind it
        // stayed inside the aggregate - a BREAK of exactly that balance, on every later run,
        // against a replacement that was never out.
        val organisationId = organisation("posted")
        val control = controlDraft(organisationId, "2100")
        withRequestContext { lifecycle.submit(command(organisationId, control.id)) }
        withRequestContext { lifecycle.approve(command(organisationId, control.id, checker())) }
        postTo(organisationId, control.id)
        withRequestContext {
            lifecycle.deactivate(command(organisationId, control.id, reason = "Mis-classified"))
        }

        val refused =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.update(release(organisationId, control.id)) }
            }

        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_HAS_HISTORY, refused.code)
        assertEquals(true, accounts.findById(organisationId, control.id)?.isControlAccount)
        // And the class stays claimed, so no replacement slips in behind the refusal.
        val replacement =
            assertFailsWith<ConflictException> {
                withRequestContext { chart.create(controlCommand(organisationId, "2101")) }
            }
        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_DUPLICATE, replacement.code)
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** One balanced journal with a line against [accountId], which is what freezes its class. */
    private fun postTo(
        organisationId: UUID,
        accountId: UUID,
    ) {
        val period = calendar.createPeriod(organisationId, FiscalPeriodStatus.OPEN)
        val tenant =
            JournalSchemaFixture.Tenant(
                organisationId = organisationId,
                branchId = journals.insertBranch(organisationId, "JL"),
                fiscalPeriodId = period.fiscalPeriodId,
                debitAccountId = journals.insertAccount(organisationId, "1900", "ASSET"),
                creditAccountId = accountId,
            )
        val entryId = journals.insertJournalEntry(tenant)
        journals.insertJournalLine(tenant, entryId, lineNumber = 1, direction = "DEBIT")
        journals.insertJournalLine(
            tenant,
            entryId,
            lineNumber = 2,
            glAccountId = accountId,
            direction = "CREDIT",
        )
    }

    /** The amendment that gives up the class, and changes nothing else. */
    private fun release(
        organisationId: UUID,
        accountId: UUID,
    ) = UpdateGlAccountCommand(
        organisationId = organisationId,
        actorId = MAKER,
        accountId = accountId,
        isControlAccount = false,
        controlSubledgerKind = Patch.Set(null),
    )

    private fun controlCommand(
        organisationId: UUID,
        code: String,
    ) = CreateGlAccountCommand(
        organisationId = organisationId,
        actorId = MAKER,
        code = AccountCode(code),
        name = "Member deposits control $code",
        accountClass = AccountClass.LIABILITY,
        usage = AccountUsage.POSTABLE,
        isControlAccount = true,
        controlSubledgerKind = ControlSubledgerKind.SAVINGS_DEPOSITS,
    )

    private fun controlDraft(
        organisationId: UUID,
        code: String,
    ): GlAccount = withRequestContext { chart.create(controlCommand(organisationId, code)) }

    private fun command(
        organisationId: UUID,
        accountId: UUID,
        actorId: UUID = MAKER,
        reason: String? = "Because",
    ) = GlAccountTransitionCommand(
        organisationId = organisationId,
        accountId = accountId,
        actorId = actorId,
        reason = reason,
    )

    private fun organisation(label: String): UUID {
        val organisationId = tenants.createActiveOrganisation("coa-replace-$label", MAKER)
        tenants.grantTenantAdmin(organisationId, checker())
        return organisationId
    }

    /** A checker owned by this suite, so no seeded actor accumulates roles another test counts. */
    private fun checker(): UUID {
        val existing =
            dsl
                .select(USER_ACCOUNT.ID)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.USERNAME.eq(CHECKER_USERNAME))
                .fetchOne(USER_ACCOUNT.ID)
        if (existing != null) {
            return existing
        }
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, CHECKER_USERNAME)
            .set(USER_ACCOUNT.EMAIL, "$CHECKER_USERNAME@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Control Replacement Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        const val CHECKER_USERNAME = "coa.replace.checker"
        const val NOT_AMENDABLE = "accounting.gl_account_not_amendable"
    }
}
