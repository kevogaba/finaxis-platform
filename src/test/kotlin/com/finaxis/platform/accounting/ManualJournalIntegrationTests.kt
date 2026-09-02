package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.manual.AmendManualJournalCommand
import com.finaxis.platform.accounting.application.manual.CreateManualJournalCommand
import com.finaxis.platform.accounting.application.manual.ManualJournalDraftContent
import com.finaxis.platform.accounting.application.manual.ManualJournalService
import com.finaxis.platform.accounting.application.manual.ManualJournalTransitionCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual journals against PostgreSQL through the production wiring (#48): drafts never touch the
 * journal tables, approval posts through the engine as an ordinary `MANUAL` journal, and every
 * control the issue names is enforced by name.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ManualJournalIntegrationTests(
    private val manual: ManualJournalService,
    private val postingService: PostingService,
    private val ledger: JournalReadStore,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val fx = ManualJournalFixture(dsl, manual, tenants, schema)

    @Test
    fun `a draft is created and amended without touching the journal, and frozen on submit`() {
        val tenant = fx.provisionTenant("manual-lifecycle")

        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "250.00")
        assertEquals(ManualJournalStatus.DRAFT, draft.status)
        fx.inContext(tenant, ManualJournalFixture.MAKER) {
            manual.amend(
                AmendManualJournalCommand(
                    fx.context(tenant, ManualJournalFixture.MAKER),
                    draft.id,
                    fx.content(tenant, "300.00", title = "Amended"),
                ),
            )
        }
        assertEquals(0, fx.journalCount(tenant), "a draft writes nothing to the ledger")
        assertEquals(
            0,
            dsl.fetchCount(
                POSTING_REQUEST,
                POSTING_REQUEST.ORGANISATION_ID.eq(tenant.organisationId),
            ),
        )

        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)
        val frozen =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.amend(
                        AmendManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            draft.id,
                            fx.content(tenant, "1.00"),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_EDITABLE, frozen.code)
    }

    @Test
    fun `approval posts the draft through the engine as an ordinary MANUAL journal`() {
        val tenant = fx.provisionTenant("manual-approval")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "250.00")
        fx.inContext(tenant, ManualJournalFixture.MAKER) {
            manual.amend(
                AmendManualJournalCommand(
                    fx.context(tenant, ManualJournalFixture.MAKER),
                    draft.id,
                    fx.content(tenant, "300.00", title = "Amended"),
                ),
            )
        }
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)

        val approval =
            fx.inContext(tenant, tenant.checker) {
                manual.approve(
                    ManualJournalTransitionCommand(
                        fx.context(tenant, tenant.checker),
                        draft.id,
                        reason = "Reviewed",
                    ),
                )
            }

        assertEquals(ManualJournalStatus.POSTED, approval.journal.status)
        assertEquals(approval.receipt.journalEntryId, approval.journal.journalEntryId)
        val header =
            assertNotNull(
                ledger.findJournalEntry(tenant.organisationId, approval.receipt.journalEntryId),
            )
        assertEquals(JournalEntryType.MANUAL, header.entryType)
        assertEquals(
            "Amended",
            withRequestContext {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }.journal.title,
        )
        assertEquals(BigDecimal("300.000000"), header.totalDebitFunctional)
        val lines = ledger.findJournalLines(tenant.organisationId, header.id)
        assertEquals(listOf(PostingSide.DEBIT, PostingSide.CREDIT), lines.map { it.side })
        assertEquals(
            listOf("accounting", "MANUAL_JOURNAL", draft.id),
            dsl
                .select(
                    POSTING_REQUEST.SOURCE_MODULE,
                    POSTING_REQUEST.SOURCE_ENTITY_TYPE,
                    POSTING_REQUEST.SOURCE_ENTITY_ID,
                ).from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(approval.receipt.postingRequestId))
                .fetchOne { listOf(it.value1(), it.value2(), it.value3()) },
            "the draft is the durable source of the posting",
        )
        assertEquals(listOf("SUBMIT", "APPROVE"), fx.transitionNames(tenant, draft.id))
        assertEquals(listOf("journal.approve", "journal.create_manual"), fx.auditActions(tenant))
    }

    @Test
    fun `the maker cannot approve their own manual journal, and rejection needs a reason`() {
        val tenant = fx.provisionTenant("manual-sod")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "10.00")
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)

        // The maker holds journal.approve too (granted at provisioning, before the permission
        // snapshot is first read); what stops them is the separation of duties, not authority.
        val self =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.approve(
                        ManualJournalTransitionCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            draft.id,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_SELF_APPROVAL, self.code)
        assertEquals(0, fx.journalCount(tenant))

        assertFailsWith<InvalidOperationException> {
            fx.inContext(tenant, tenant.checker) {
                manual.reject(
                    ManualJournalTransitionCommand(fx.context(tenant, tenant.checker), draft.id),
                )
            }
        }
        val rejected =
            fx.inContext(tenant, tenant.checker) {
                manual.reject(
                    ManualJournalTransitionCommand(
                        fx.context(tenant, tenant.checker),
                        draft.id,
                        reason = "Wrong account",
                    ),
                )
            }
        assertEquals(ManualJournalStatus.DRAFT, rejected.status)
        assertEquals("Wrong account", rejected.statusReason)

        val cancelled =
            fx.inContext(tenant, ManualJournalFixture.MAKER) {
                manual.cancel(
                    ManualJournalTransitionCommand(
                        fx.context(tenant, ManualJournalFixture.MAKER),
                        draft.id,
                    ),
                )
            }
        assertEquals(ManualJournalStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `unbalanced, ineligible-account and control-account drafts cannot be created`() {
        val tenant = fx.provisionTenant("manual-refusals")

        val unbalanced =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.create(
                        CreateManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            fx.content(tenant, "10.00", credit = "9.00"),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, unbalanced.code)

        val noManual = schema.insertAccount(tenant.organisationId, "1900", "ASSET")
        val notAllowed =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.create(
                        CreateManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            fx.content(tenant, "10.00", debitAccount = noManual),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, notAllowed.code)

        val control =
            schema.insertControlAccount(
                tenant.organisationId,
                "2100",
                "LIABILITY",
                ControlSubledgerKind.SAVINGS_DEPOSITS,
            )
        val controlRefused =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.create(
                        CreateManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            fx.content(tenant, "10.00", creditAccount = control),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, controlRefused.code)
    }

    @Test
    fun `a closed period refuses approval and leaves the draft pending`() {
        val tenant = fx.provisionTenant("manual-closed-period")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "10.00")
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)
        fx.setPeriodStatus(tenant, "CLOSED")
        val closed =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, tenant.checker) {
                    manual.approve(
                        ManualJournalTransitionCommand(
                            fx.context(tenant, tenant.checker),
                            draft.id,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.PERIOD_CLOSED, closed.code)
        fx.setPeriodStatus(tenant, "OPEN")
        assertEquals(
            ManualJournalStatus.PENDING_APPROVAL,
            withRequestContext {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }.journal.status,
            "a refused approval leaves the draft where it was",
        )
        assertEquals(0, fx.journalCount(tenant))
    }

    @Test
    fun `a posted manual journal is corrected by reversal, never edited`() {
        val tenant = fx.provisionTenant("manual-reversal")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "40.00")
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)
        val approval =
            fx.inContext(tenant, tenant.checker) {
                manual.approve(
                    ManualJournalTransitionCommand(fx.context(tenant, tenant.checker), draft.id),
                )
            }

        val reversal =
            fx.inContext(tenant, ManualJournalFixture.MAKER) {
                postingService.reverse(
                    ReversePostingCommand(
                        fx.context(tenant, ManualJournalFixture.MAKER),
                        approval.receipt.journalEntryId,
                        reason = "Duplicate",
                    ),
                )
            }

        assertEquals(
            approval.receipt.journalEntryId,
            ledger
                .findJournalEntry(tenant.organisationId, reversal.journalEntryId)
                ?.reversesJournalEntryId,
        )
        assertNull(
            dsl
                .selectFrom(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(approval.receipt.journalEntryId))
                .and(JOURNAL_LINE.AMOUNT.ne(BigDecimal("40.000000")))
                .fetchAny(),
            "the original lines are untouched",
        )
    }

    @Test
    fun `every entry point is permission gated`() {
        val tenant = fx.provisionTenant("manual-permissions")
        assertFailsWith<ForbiddenOperationException> {
            fx.create(tenant, ManualJournalFixture.STRANGER, "1.00")
        }
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "1.00")
        assertFailsWith<ForbiddenOperationException> {
            fx.submit(tenant, ManualJournalFixture.STRANGER, draft.id)
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                manual.get(
                    tenant.organisationId,
                    draft.id,
                    ManualJournalFixture.STRANGER,
                )
            }
        }
    }

    @Test
    fun `only the maker may amend, submit or cancel their own draft`() {
        val tenant = fx.provisionTenant("manual-ownership")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "10.00")

        // The checker holds journal.create_manual through tenant admin, but the draft is not
        // theirs: a tenant-wide permission is not an ownership claim.
        val amend =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    manual.amend(
                        AmendManualJournalCommand(
                            fx.context(tenant, tenant.checker),
                            draft.id,
                            fx.content(tenant, "99.00"),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, amend.code)

        // Submitting someone else's draft would make its real preparer eligible to approve it.
        // The checker holds journal.submit too (granted at provisioning), so what refuses here is
        // ownership rather than authority.
        val submitByOther =
            assertFailsWith<ForbiddenOperationException> {
                fx.submit(
                    tenant,
                    tenant.checker,
                    draft.id,
                )
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, submitByOther.code)

        val cancelByOther =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    manual.cancel(
                        ManualJournalTransitionCommand(
                            fx.context(tenant, tenant.checker),
                            draft.id,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, cancelByOther.code)

        assertEquals(
            ManualJournalStatus.DRAFT,
            withRequestContext {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }.journal.status,
            "a refused act leaves the draft where the maker left it",
        )

        // The maker themselves still can.
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)
    }

    @Test
    fun `approval commits the draft, its log, the audit and the ledger together or not at all`() {
        val tenant = fx.provisionTenant("manual-atomicity")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "75.00")
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)

        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                listOf(
                    FoundationAtomicityProbes.manualJournalStatus(draft.id),
                    FoundationAtomicityProbes.manualJournalTransitionLogRows(tenant.organisationId),
                    FoundationAtomicityProbes.postingRequestRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalEntryRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalLineRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalSequenceValue(tenant.organisationId),
                    FoundationAtomicityProbes.auditEventRows(
                        tenant.organisationId,
                        "journal.approve",
                        "SUCCESS",
                    ),
                ),
            )
        val before = harness.snapshot()

        // A failure after approval must undo the draft's status, its transition log, the audit
        // event, the posting request, the journal, its lines and the gapless number together.
        harness.assertRollsBackAtomically(IllegalStateException::class) {
            fx.inContext(tenant, tenant.checker) {
                manual.approve(
                    ManualJournalTransitionCommand(fx.context(tenant, tenant.checker), draft.id),
                )
                error("simulated failure after the draft posted")
            }
        }

        assertEquals(before, harness.snapshot())
        assertEquals(
            ManualJournalStatus.PENDING_APPROVAL,
            withRequestContext {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }.journal.status,
            "the draft is still awaiting its checker",
        )
    }

    @Test
    fun `a rejection is audited, and a draft is bounded and settled before it is stored`() {
        val tenant = fx.provisionTenant("manual-controls")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "10.00")
        fx.submit(tenant, ManualJournalFixture.MAKER, draft.id)

        fx.inContext(tenant, tenant.checker) {
            manual.reject(
                ManualJournalTransitionCommand(
                    fx.context(tenant, tenant.checker),
                    draft.id,
                    reason = "Wrong account",
                ),
            )
        }
        assertTrue(
            "journal.reject" in fx.auditActions(tenant),
            "a refused adjustment is as much a control event as a posted one",
        )

        // More precision than the ledger stores is refused rather than silently rounded.
        val unsettled =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.create(
                        CreateManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            fx.content(tenant, "10.01000004", credit = "10.01000004"),
                        ),
                    )
                }
            }
        assertEquals(MoneyPolicy.AMOUNT_PRECISION_EXCEEDED, unsettled.code)
    }
}
