package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.AtomicityProbe
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.ORGANISATION_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.ReturnOrganisationForChangesCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Commit-or-roll-back-together proof for returning a pending tenant to draft (issue #181). It is
 * not a financial write path (ADR 0029), but the status change, its transition-log row, its audit
 * row and the bootstrap record's reset to a draft are one service transaction: they become visible
 * together, or not at all, and nothing reaches the outbox.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TenantReturnAtomicityIntegrationTests(
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val makerId = uuidV7().also { seedUser(it) }
    private val submitterId = uuidV7().also { seedUser(it) }
    private val checkerId = uuidV7().also { seedUser(it) }

    init {
        fixture.grantPlatformSuperAdmin(checkerId)
    }

    private fun pendingTenant(): UUID =
        withRequestContext {
            val tenantId =
                organisationProvisioningService
                    .createDraft(
                        CreateOrganisationDraftCommand(
                            tenantCode = "ret-atomic-${uuidV7().toString().takeLast(8)}",
                            displayName = "Return Atomic Tenant",
                            legalName = null,
                            registrationNumber = null,
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            requestedBy = makerId,
                        ),
                    ).organisationId
            organisationProvisioningService.submitForApproval(
                SubmitOrganisationForApprovalCommand(tenantId, actorId = submitterId),
            )
            tenantId
        }

    private fun harness(tenantId: UUID) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            listOf(
                AtomicityProbe("organisation.row_version[$tenantId]") { dsl ->
                    dsl
                        .select(ORGANISATION.ROW_VERSION)
                        .from(ORGANISATION)
                        .where(ORGANISATION.ID.eq(tenantId))
                        .fetchOne(ORGANISATION.ROW_VERSION) ?: 0L
                },
                AtomicityProbe(
                    "organisation_transition_log[RETURN_FOR_CHANGES, $tenantId]",
                ) { dsl ->
                    dsl
                        .fetchCount(
                            ORGANISATION_TRANSITION_LOG,
                            ORGANISATION_TRANSITION_LOG.ENTITY_ID
                                .eq(tenantId)
                                .and(ORGANISATION_TRANSITION_LOG.TRANSITION_NAME.eq(RETURN)),
                        ).toLong()
                },
                FoundationAtomicityProbes.auditEventRows(
                    tenantId,
                    "organisation.return_for_changes",
                    "SUCCESS",
                ),
                // 0 while the record still describes a pending submission, 1 once it is a draft
                // again with its submitter cleared.
                AtomicityProbe("bootstrap_record[reset to draft, $tenantId]") { dsl ->
                    val record = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
                    dsl
                        .fetchCount(
                            record,
                            record.ORGANISATION_ID
                                .eq(tenantId)
                                .and(record.STATUS.eq("DRAFT"))
                                .and(record.SUBMITTED_BY.isNull),
                        ).toLong()
                },
                // Internal event only: the return must never reach the outbox.
                AtomicityProbe("outbox_record[RETURN_FOR_CHANGES, $tenantId]") { dsl ->
                    dsl
                        .fetchValue(
                            "SELECT COUNT(*) FROM outbox_record " +
                                "WHERE payload LIKE ? AND payload LIKE ?",
                            "%$tenantId%",
                            "%$RETURN%",
                        )?.toString()
                        ?.toLong() ?: 0L
                },
            ),
        )

    private fun returnTenant(tenantId: UUID) =
        withRequestContext {
            organisationProvisioningService.returnForChanges(
                ReturnOrganisationForChangesCommand(
                    tenantId,
                    Reason.required("Typo in the legal name."),
                    checkerId,
                ),
            )
        }

    private fun status(tenantId: UUID): String? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenantId))
            .fetchOne(ORGANISATION.STATUS)

    @Test
    fun `a failure after a return rolls back the status its rows and the record`() {
        val tenantId = pendingTenant()

        harness(tenantId).assertRollsBackAtomically(IllegalStateException::class) {
            returnTenant(tenantId)
            assertEquals("DRAFT", status(tenantId), "the return must have taken effect")
            error("simulated failure after a successful return")
        }

        assertEquals("PENDING_APPROVAL", status(tenantId))
    }

    @Test
    fun `a return becomes visible together with its log row audit row and record reset`() {
        val tenantId = pendingTenant()

        harness(tenantId).assertVisibleOnlyAfterCommit(
            mapOf(
                "organisation.row_version[$tenantId]" to 1L,
                "organisation_transition_log[RETURN_FOR_CHANGES, $tenantId]" to 1L,
                "audit_event[organisation.return_for_changes, SUCCESS]" to 1L,
                "bootstrap_record[reset to draft, $tenantId]" to 1L,
            ),
        ) {
            returnTenant(tenantId)
        }

        assertEquals("DRAFT", status(tenantId))
    }

    private fun seedUser(id: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "tenant-ret-atomic-$id")
            .set(USER_ACCOUNT.EMAIL, "tenant-ret-atomic-$id@tenant-return.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "atomic")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }

    private companion object {
        const val RETURN = "RETURN_FOR_CHANGES"
    }
}
