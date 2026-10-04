package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CloseBranchCommand
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.SuspendBranchCommand
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `opened_on` and `closed_on` are stamped from the tenant's business date, in the same statement
 * as the state change (issue #165). The business date is set to a day that is not today, so a
 * wall-clock implementation cannot pass.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqBranchBusinessDateTests(
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `activation and closure stamp the tenant business date and reactivation keeps it`() {
        val maker = insertUser()
        val checker = insertUser()
        val organisationId = fixture.createActiveOrganisation("branch-dates", maker)
        fixture.grantTenantAdmin(organisationId, checker)
        val opening = LocalDate.of(2031, 3, 9)
        assertNotEquals(LocalDate.now(), opening)
        setBusinessDate(organisationId, opening)
        val branchId = createSubmittedBranch(organisationId, maker)
        assertEquals(null to null, branchDates(branchId), "a pending branch has neither date")

        withRequestContext {
            branchProvisioningService.activate(
                ActivateBranchCommand(
                    organisationId = organisationId,
                    branchId = branchId,
                    actorId = checker,
                    requestId = uuidV7(),
                ),
            )
        }
        assertEquals(opening to null, branchDates(branchId))

        // A later business date must not move the first opening date on reactivation.
        setBusinessDate(organisationId, LocalDate.of(2031, 3, 10))
        withRequestContext {
            branchProvisioningService.suspend(
                SuspendBranchCommand(organisationId, branchId, "Audit hold", checker),
            )
            branchProvisioningService.reactivate(
                ReactivateBranchCommand(organisationId, branchId, "Audit done", checker),
            )
        }
        assertEquals(opening to null, branchDates(branchId))

        val closing = LocalDate.of(2031, 4, 21)
        setBusinessDate(organisationId, closing)
        withRequestContext {
            branchProvisioningService.close(
                CloseBranchCommand(organisationId, branchId, "Branch relocated", checker),
            )
        }
        assertEquals(opening to closing, branchDates(branchId))
    }

    @Test
    fun `the head office activated during tenant approval is opened on the business date`() {
        val organisationId = fixture.createActiveOrganisation("head-office-dates", insertUser())

        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            )
        val headOffice =
            requireNotNull(
                dsl
                    .select(BRANCH.OPENED_ON, BRANCH.CLOSED_ON)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.STATUS.eq("ACTIVE"))
                    .fetchOne(),
            )
        assertEquals(businessDate, headOffice.get(BRANCH.OPENED_ON))
        assertEquals(null, headOffice.get(BRANCH.CLOSED_ON))
    }

    private fun createSubmittedBranch(
        organisationId: UUID,
        maker: UUID,
    ): UUID =
        withRequestContext {
            val created =
                branchProvisioningService
                    .createDraft(
                        CreateBranchCommand(
                            organisationId = organisationId,
                            branchCode = "DATES",
                            branchName = "Dated Branch",
                            branchType = "OPERATIONS",
                            timezone = "Africa/Nairobi",
                            requestedBy = maker,
                        ),
                    ).branchId
            branchProvisioningService.submitForApproval(
                SubmitBranchForApprovalCommand(
                    organisationId = organisationId,
                    branchId = created,
                    actorId = maker,
                    requestId = uuidV7(),
                ),
            )
            created
        }

    private fun setBusinessDate(
        organisationId: UUID,
        date: LocalDate,
    ) {
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, date)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .execute()
    }

    private fun branchDates(branchId: UUID): Pair<LocalDate?, LocalDate?> =
        requireNotNull(
            dsl
                .select(BRANCH.OPENED_ON, BRANCH.CLOSED_ON)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(),
        ).let { it.get(BRANCH.OPENED_ON) to it.get(BRANCH.CLOSED_ON) }

    private fun insertUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }
}
