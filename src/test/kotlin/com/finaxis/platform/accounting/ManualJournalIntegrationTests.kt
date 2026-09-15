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
import org.springframework.transaction.support.TransactionTemplate
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
    private val transactions = TransactionTemplate(transactionManager)

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
                    draft.rowVersion,
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
                            // The version as it stands after submit, not the pre-submit one:
                            // otherwise this passes on staleness rather than on the status check
                            // it is named for.
                            fx.currentRowVersion(tenant, draft.id),
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
                    draft.rowVersion,
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
        assertEquals(AUDITED_THROUGH_APPROVAL, fx.auditActions(tenant))
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

    /**
     * The lost update issue #92 reports, reproduced as two editors working from one view.
     *
     * Before the expected version came from the caller, this pair merely serialised: the second
     * amendment read the row it had just locked, compared its version against itself, found them
     * equal and overwrote the first maker's whole header and line set - title, narrative, dates and
     * every amount - with `MANUAL_JOURNAL_STALE` advertising a protection that could not fire.
     */
    @Test
    fun `an amendment prepared against an earlier view is refused, not silently applied`() {
        val tenant = fx.provisionTenant("manual-stale-amend")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "250.00")

        // Both editors read the same version. The first amendment wins.
        val sharedView = draft.rowVersion
        fx.inContext(tenant, ManualJournalFixture.MAKER) {
            manual.amend(
                AmendManualJournalCommand(
                    fx.context(tenant, ManualJournalFixture.MAKER),
                    draft.id,
                    sharedView,
                    fx.content(tenant, "300.00", title = "First edit"),
                ),
            )
        }

        val stale =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.amend(
                        AmendManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            draft.id,
                            sharedView,
                            fx.content(tenant, "999.00", title = "Second edit"),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_STALE, stale.code)

        // The amendment that landed is audited, naming the version it was prepared from; the
        // refused one writes nothing, or the trail would claim an edit that never happened.
        val amendments = fx.auditMetadata(tenant, "journal.amend_manual")
        assertEquals(1, amendments.size, "only the amendment that landed is audited: $amendments")
        assertTrue(Regex(""""amendedFromRowVersion":\s*$sharedView""") in amendments.single())

        // The first edit stands, untouched, and the refused attempt wrote nothing at all - not the
        // header, not the lines, not even the row version.
        val afterStale =
            fx.inContext(tenant, ManualJournalFixture.MAKER) {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }
        assertEquals("First edit", afterStale.journal.title)
        assertEquals(sharedView + 1, afterStale.journal.rowVersion)
        assertEquals(
            BigDecimal("300.000000"),
            afterStale.lines.first { it.side == PostingSide.DEBIT }.amount,
        )

        // Re-reading and retrying against the version that actually stands succeeds, which is the
        // whole point: this is a conflict to resolve, not a wall.
        fx.inContext(tenant, ManualJournalFixture.MAKER) {
            manual.amend(
                AmendManualJournalCommand(
                    fx.context(tenant, ManualJournalFixture.MAKER),
                    draft.id,
                    afterStale.journal.rowVersion,
                    fx.content(tenant, "410.00", title = "Second edit, retried"),
                ),
            )
        }
        val settled =
            fx.inContext(tenant, ManualJournalFixture.MAKER) {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }
        assertEquals("Second edit, retried", settled.journal.title)
    }

    @Test
    fun `a draft carries a structured external reference from creation to the approval audit`() {
        val tenant = fx.provisionTenant("manual-external-reference")
        val draft =
            fx.inContext(tenant, ManualJournalFixture.MAKER) {
                manual.create(
                    CreateManualJournalCommand(
                        fx.context(tenant, ManualJournalFixture.MAKER),
                        fx.content(tenant, "120.00", externalReference = "BANK-ADVICE-4471"),
                    ),
                )
            }
        assertEquals("BANK-ADVICE-4471", draft.externalReference)

        // It survives an amendment, and can be cleared by one.
        fx.inContext(tenant, ManualJournalFixture.MAKER) {
            manual.amend(
                AmendManualJournalCommand(
                    fx.context(tenant, ManualJournalFixture.MAKER),
                    draft.id,
                    draft.rowVersion,
                    fx.content(tenant, "120.00", externalReference = null),
                ),
            )
        }
        assertNull(
            fx
                .inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
                }.journal.externalReference,
        )

        // A blank reference is refused rather than stored: it is indistinguishable from having
        // supplied nothing while still occupying a column reports are grouped by.
        val blank =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, ManualJournalFixture.MAKER) {
                    manual.create(
                        CreateManualJournalCommand(
                            fx.context(tenant, ManualJournalFixture.MAKER),
                            fx.content(tenant, "10.00", externalReference = "   "),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID, blank.code)
    }

    /**
     * `get` is what a checker reads before approving, so its header and lines must be one draft.
     *
     * The guard fires when the isolation a caller declared was not the isolation it got - which is
     * what Spring does, silently, when the read joins a transaction that is already open. Without
     * it the annotation would be a claim nothing checks.
     */
    @Test
    fun `reading a draft refuses a transaction whose snapshot can move under it`() {
        val tenant = fx.provisionTenant("manual-torn-read")
        val draft = fx.create(tenant, ManualJournalFixture.MAKER, "75.00")

        val torn =
            assertFailsWith<ConflictException> {
                transactions.execute {
                    fx.inContext(tenant, ManualJournalFixture.MAKER) {
                        manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
                    }
                }
            }

        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, torn.code)
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
                            draft.rowVersion,
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

        // The failure this scenario used to inject - `error(...)` after `approve` returned, inside
        // the harness's own `TransactionTemplate` - can no longer reach the approval's transaction,
        // and keeping it would have been worse than losing it. `approve` now opens its own
        // `SERIALIZABLE` transaction through `PostingTransactionBoundary`, which refuses to run
        // inside a caller's, so the injected `IllegalStateException` would in fact have been the
        // boundary's `check` firing before the draft was touched at all, and every probe would have
        // read "unchanged" for the wrong reason. There is no seam left to inject through either:
        // the body is a private `approveInTransaction`, deliberately, so that nothing but the
        // boundary can open the transaction it runs in.
        //
        // What is still provable from outside is the either-or itself, so both halves are asserted.
        // The refusal half is a closed period, the latest refusal the approval can raise:
        // `PostingEngine` writes its `posting_request` claim before it resolves the period, so a
        // durable row is in flight that the rollback has to take with it - this is not a scenario
        // that passes because nothing was ever attempted.
        fx.setPeriodStatus(tenant, "CLOSED")
        val refused =
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
        assertEquals(
            PostingErrorCodes.PERIOD_CLOSED,
            refused.code,
            "the refusal must still be the period's, not the isolation guard's or the boundary's",
        )
        assertEquals(before, harness.snapshot(), "a refused approval leaves nothing behind")
        assertEquals(
            ManualJournalStatus.PENDING_APPROVAL,
            withRequestContext {
                manual.get(tenant.organisationId, draft.id, ManualJournalFixture.MAKER)
            }.journal.status,
            "the draft is still awaiting its checker",
        )

        // And the commit half: the draft's status, its transition log, the audit event, the posting
        // request, the journal, its lines and the gapless number all move on the one approval.
        fx.setPeriodStatus(tenant, "OPEN")
        fx.inContext(tenant, tenant.checker) {
            manual.approve(
                ManualJournalTransitionCommand(fx.context(tenant, tenant.checker), draft.id),
            )
        }
        val after = harness.snapshot()
        assertTrue(
            before.all { (probe, value) -> after.getValue(probe) > value },
            "every probe advanced on the one approval: $before -> $after",
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

    private companion object {
        /**
         * Every audit event a draft leaves on the way to `POSTED`, in the order `auditActions`
         * sorts them.
         *
         * `journal.amend_manual` is among them because an amendment is the one manual-journal
         * operation that leaves no `manual_journal_transition_log` row, and it can rewrite every
         * amount and account the approval then posts.
         */
        val AUDITED_THROUGH_APPROVAL =
            listOf("journal.amend_manual", "journal.approve", "journal.create_manual")
    }
}
