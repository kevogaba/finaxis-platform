package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.ledger.SerializablePostingTransaction
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.support.FakeAccountingPermissionGuard
import com.finaxis.platform.accounting.support.FakeManualJournalMakerResolver
import com.finaxis.platform.accounting.support.FakeManualJournalStore
import com.finaxis.platform.accounting.support.ManualJournalAccessKind
import com.finaxis.platform.accounting.support.MutableContextLookup
import com.finaxis.platform.accounting.support.NoTransitionEvents
import com.finaxis.platform.accounting.support.PostingEngineHarness
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authoring a manual journal: creating a draft, amending one, and reading one back.
 *
 * `ManualJournalPolicyTests` owns the arithmetic of a draft - the bounds, the blank, the scale, the
 * balance - and `ManualJournalIntegrationTests` owns the wiring and what the database enforces.
 * What is left, and what this suite is about, is the sequence the service imposes between its
 * collaborators while a journal is still the maker's: that the permission is checked before any row
 * is read, that every account a line names is looked up and looked up once, and that the row
 * version the maker prepared against is compared before a single line is replaced.
 *
 * Everything from the submission onwards - the checker, the posting, the terminal states - is
 * `ManualJournalApprovalServiceTests`, over the same [ManualJournalServiceTestFixture].
 */
class ManualJournalServiceTests : ManualJournalServiceTestFixture() {
    // ---- create -----------------------------------------------------------------------------

    @Test
    fun `a draft is created with its lines, its maker and a high-severity audit event`() {
        val created = service.create(CreateManualJournalCommand(actingAs(MAKER), content()))

        assertEquals(listOf(AccountingPermissions.JOURNAL_CREATE_MANUAL), permissions.checkedCodes)
        assertEquals(MAKER, permissions.checks.single().actorId)
        assertEquals(ORGANISATION, permissions.checks.single().organisationId)
        assertEquals(ManualJournalStatus.DRAFT, created.status)
        assertEquals(MAKER, created.createdBy)
        // The draft inherits the request's branch when the content names none.
        assertEquals(BRANCH, created.branchId)
        assertEquals(0L, created.rowVersion)
        assertNull(created.journalEntryId)
        assertEquals(listOf(1, 2), journals.lines.getValue(created.id).map { it.lineNumber })
        assertEquals(
            listOf(ManualJournalAccessKind.CREATE, ManualJournalAccessKind.REPLACE_LINES),
            journals.accesses.map { it.kind },
        )

        val audited = auditEvents.single()
        assertEquals(AccountingAuditActions.JOURNAL_CREATE_MANUAL, audited.action)
        assertEquals(AuditSeverity.HIGH, audited.severity)
        assertEquals(created.id.toString(), audited.resourceId)
        assertEquals(ORGANISATION.toString(), audited.tenantId)
        assertEquals("ADV-4471", audited.metadata["externalReference"])
    }

    @Test
    fun `a creation refused by the permission guard touches neither store nor audit trail`() {
        permissions.refuse(AccountingPermissions.JOURNAL_CREATE_MANUAL)

        assertFailsWith<ForbiddenOperationException> {
            service.create(CreateManualJournalCommand(actingAs(MAKER), content()))
        }

        // The check is recorded, the reads that would have followed it are not: the guard runs
        // before the content is validated and before a single account is looked up.
        assertEquals(listOf(AccountingPermissions.JOURNAL_CREATE_MANUAL), permissions.checkedCodes)
        assertTrue(journals.accesses.isEmpty())
        assertTrue(accounts.lookups.isEmpty())
        assertTrue(auditEvents.isEmpty())
    }

    @Test
    fun `a draft naming a branch of its own is created and audited against that branch`() {
        val elsewhere = uuidV7()

        val created =
            service.create(
                CreateManualJournalCommand(actingAs(MAKER), content(branchId = elsewhere)),
            )

        assertEquals(elsewhere, created.branchId)
        // The audit event follows the journal's branch, not the session's.
        assertEquals(elsewhere.toString(), auditEvents.single().branchId)
    }

    @Test
    fun `a line naming an account the tenant does not hold is refused before anything is stored`() {
        val unknown = uuidV7()

        val failure =
            assertFailsWith<InvalidOperationException> {
                service.create(
                    CreateManualJournalCommand(
                        actingAs(MAKER),
                        content(lines = balancedLines(debitAccount = unknown)),
                    ),
                )
            }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
        assertTrue(journals.journals.isEmpty())
        assertTrue(journals.accesses.isEmpty())
        assertTrue(auditEvents.isEmpty())
    }

    @Test
    fun `an account that has not opted into manual posting, or is a control account, is refused`() {
        listOf(RULE_FED_ACCOUNT, CONTROL_ACCOUNT).forEach { accountId ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    service.create(
                        CreateManualJournalCommand(
                            actingAs(MAKER),
                            content(lines = balancedLines(debitAccount = accountId)),
                        ),
                    )
                }

            assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code, "account $accountId")
        }
        assertTrue(journals.journals.isEmpty())
    }

    @Test
    fun `an account named by several lines is looked up once`() {
        val fourLines =
            listOf(
                line(1, DEBIT_ACCOUNT, PostingSide.DEBIT, "6.00"),
                line(2, DEBIT_ACCOUNT, PostingSide.DEBIT, "4.00"),
                line(3, CREDIT_ACCOUNT, PostingSide.CREDIT, "7.00"),
                line(4, CREDIT_ACCOUNT, PostingSide.CREDIT, "3.00"),
            )

        service.create(CreateManualJournalCommand(actingAs(MAKER), content(lines = fourLines)))

        assertEquals(listOf(DEBIT_ACCOUNT, CREDIT_ACCOUNT), accounts.lookups)
    }

    // ---- amend ------------------------------------------------------------------------------

    @Test
    fun `an amendment prepared against a row version that has moved is a conflict`() {
        // The whole point of issue #109: the second maker editing from an earlier view is refused
        // rather than silently overwriting the first maker's header and every line of it.
        val journal = seed(rowVersion = 4)

        val stale =
            assertFailsWith<ConflictException> {
                service.amend(
                    AmendManualJournalCommand(
                        context = actingAs(MAKER),
                        journalId = journal.id,
                        expectedRowVersion = 3,
                        content =
                            content(
                                title = "Rewritten",
                                lines = balancedLines(debit = "99.00"),
                            ),
                    ),
                )
            }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_STALE, stale.code)
        assertEquals(journal, journals.journals.getValue(journal.id))
        assertEquals(balancedLines(), journals.lines.getValue(journal.id))
        // Refused before validateContent and before any line is touched.
        assertTrue(
            journals.accesses.none {
                it.kind == ManualJournalAccessKind.REPLACE_LINES ||
                    it.kind == ManualJournalAccessKind.UPDATE_DRAFT
            },
        )
        assertTrue(auditEvents.isEmpty())
    }

    @Test
    fun `an amendment at the current version replaces header and lines and is audited`() {
        val journal = seed(rowVersion = 2)
        val replacement = balancedLines(debit = "25.00")

        val amended =
            service.amend(
                AmendManualJournalCommand(
                    context = actingAs(MAKER),
                    journalId = journal.id,
                    expectedRowVersion = 2,
                    content =
                        content(
                            title = "Restated",
                            externalReference = "MEMO-9",
                            lines = replacement,
                        ),
                ),
            )

        assertEquals("Restated", amended.title)
        assertEquals("MEMO-9", amended.externalReference)
        assertEquals(3L, amended.rowVersion)
        assertEquals(replacement, journals.lines.getValue(journal.id))

        val audited = auditEvents.single()
        assertEquals(AccountingAuditActions.JOURNAL_AMEND_MANUAL, audited.action)
        assertEquals(AuditSeverity.HIGH, audited.severity)
        assertEquals(2L, audited.metadata["amendedFromRowVersion"])
        assertEquals(2, audited.metadata["lineCount"])
    }

    @Test
    fun `only the maker may amend a draft`() {
        val journal = seed()

        val refused =
            assertFailsWith<ForbiddenOperationException> {
                service.amend(amendment(journal, CHECKER))
            }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, refused.code)
        assertEquals(balancedLines(), journals.lines.getValue(journal.id))
        assertTrue(journals.draftUpdates.isEmpty())
    }

    @Test
    fun `a journal that has left DRAFT is no longer editable`() {
        listOf(
            ManualJournalStatus.PENDING_APPROVAL,
            ManualJournalStatus.POSTED,
            ManualJournalStatus.CANCELLED,
        ).forEach { status ->
            val journal = seed(status = status)

            val refused =
                assertFailsWith<ConflictException> { service.amend(amendment(journal, MAKER)) }

            assertEquals(
                PostingErrorCodes.MANUAL_JOURNAL_NOT_EDITABLE,
                refused.code,
                "status $status",
            )
        }
        assertTrue(journals.draftUpdates.isEmpty())
    }

    @Test
    fun `amending a journal that does not exist, or is another tenant's, is not found`() {
        val absent =
            assertFailsWith<ResourceNotFoundException> {
                service.amend(
                    AmendManualJournalCommand(actingAs(MAKER), uuidV7(), 0, content()),
                )
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_FOUND, absent.code)

        val theirs = seed(organisationId = OTHER_ORGANISATION)
        val hidden =
            assertFailsWith<ResourceNotFoundException> {
                service.amend(amendment(theirs, MAKER))
            }

        // Tenant-scoped, and the permission was checked before the row was looked for either way.
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_FOUND, hidden.code)
        assertEquals(
            listOf(
                AccountingPermissions.JOURNAL_CREATE_MANUAL,
                AccountingPermissions.JOURNAL_CREATE_MANUAL,
            ),
            permissions.checkedCodes,
        )
        assertTrue(journals.draftUpdates.isEmpty())
    }

    @Test
    fun `a store that refuses the header's compare-and-set answers with the same stale conflict`() {
        // Unreachable while the caller holds the row lock, which is why it is worth stating: a
        // future caller that does not hold it must get the answer a retrying client already knows.
        val journal = seed()
        val refusing = serviceOver(StoreRefusingDraftUpdate(journals))

        val stale =
            assertFailsWith<ConflictException> { refusing.amend(amendment(journal, MAKER)) }

        assertEquals(ManualJournalPolicy.staleEdit().code, stale.code)
        assertEquals(ManualJournalPolicy.staleEdit().message, stale.message)
        assertTrue(auditEvents.isEmpty())
    }

    // ---- get --------------------------------------------------------------------------------

    @Test
    fun `reading a journal gates on the permission, then the snapshot, then the rows`() {
        val journal = seed()
        permissions.refuse(AccountingPermissions.JOURNAL_VIEW)

        assertFailsWith<ForbiddenOperationException> {
            service.get(ORGANISATION, journal.id, CHECKER)
        }
        assertTrue(snapshotReads.isEmpty())
        assertTrue(journals.accesses.isEmpty())

        permissions.allow(AccountingPermissions.JOURNAL_VIEW)
        stableSnapshot = false
        val torn =
            assertFailsWith<ConflictException> { service.get(ORGANISATION, journal.id, CHECKER) }
        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, torn.code)
        assertEquals(1, snapshotReads.size)
        assertTrue(journals.accesses.isEmpty())

        stableSnapshot = true
        val detail = service.get(ORGANISATION, journal.id, CHECKER)

        assertEquals(journal, detail.journal)
        assertEquals(balancedLines(), detail.lines)
        assertEquals(3, permissions.checkedCodes.count { it == AccountingPermissions.JOURNAL_VIEW })
    }

    @Test
    fun `reading a journal that does not exist, or is another tenant's, is not found`() {
        val theirs = seed(organisationId = OTHER_ORGANISATION)

        listOf(uuidV7(), theirs.id).forEach { journalId ->
            val missing =
                assertFailsWith<ResourceNotFoundException> {
                    service.get(ORGANISATION, journalId, CHECKER)
                }
            assertEquals(
                PostingErrorCodes.MANUAL_JOURNAL_NOT_FOUND,
                missing.code,
                "journal $journalId",
            )
        }
    }
}

/** Refuses [updateDraft] while the locked row's version matched: the port's own last word. */
private class StoreRefusingDraftUpdate(
    delegate: FakeManualJournalStore,
) : ManualJournalStore by delegate {
    override fun updateDraft(
        organisationId: UUID,
        journalId: UUID,
        content: ManualJournalDraftContent,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean = false
}

/**
 * The collaborators every manual-journal service test needs, and the builders that feed them.
 *
 * Shared by [ManualJournalServiceTests] and `ManualJournalApprovalServiceTests`, which split the
 * one service between them along the maker/checker seam. The posting engine is the real one,
 * assembled by [PostingEngineHarness] over its own in-memory ports rather than stubbed, because a
 * stub cannot fail the way a posting fails - and the approval path's whole interest is in what the
 * service does when it does.
 *
 * The engine requires an active transaction, so one is declared around every test; nothing rolls
 * back, and the assertions about a refused operation are therefore about what was *never
 * attempted*, which is the stronger statement anyway.
 */
abstract class ManualJournalServiceTestFixture {
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val contextLookup =
        MutableContextLookup(AccountingContext(ORGANISATION, BRANCH, MAKER, CORRELATION))
    private val harness =
        PostingEngineHarness(ORGANISATION, PERIOD, TODAY, CURRENCY, clock, contextLookup)

    /** The chart of accounts the service validates lines against, recording its lookups. */
    internal val accounts = harness.accounts

    /** The ledger write port, holding whatever an approval posted. */
    internal val postings = harness.postings

    /** The gapless journal counter; set `next` to null to make the engine refuse. */
    internal val numbers = harness.numbers

    /** The permission guard, recording every check in order. */
    internal val permissions = FakeAccountingPermissionGuard()

    /** The separation-of-duties lookup over the transition log. */
    internal val makers = FakeManualJournalMakerResolver()

    /** Every transition the executor logged, in order. */
    internal val transitionLogs = mutableListOf<TransitionLog>()

    private val transitions =
        TransitionExecutor(
            clock,
            TransitionLogRepository { transitionLogs += it },
            NoTransitionEvents(),
        )

    /** Every audit event the service raised, in order. */
    internal val auditEvents = mutableListOf<AuditEvent>()

    private val auditService = AuditService(AuditEventRepository { auditEvents += it }, clock)

    /** The name of every operation that asked for a stable snapshot, in order. */
    internal val snapshotReads = mutableListOf<String>()

    /** Set false to make the next snapshot check report a torn read. */
    internal var stableSnapshot = true

    private val snapshots =
        SnapshotIsolationGuard { _, operation ->
            snapshotReads += operation
            if (!stableSnapshot) {
                throw ConflictException(
                    code = PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE,
                    safeDetail = "The transaction does not hold one snapshot.",
                )
            }
        }

    /** The manual-journal store the service under test writes through. */
    internal val journals = FakeManualJournalStore()

    /** The service under test, over [journals]. */
    internal val service = serviceOver(journals)

    @BeforeEach
    fun inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        accounts.put(DEBIT_ACCOUNT)
        accounts.put(CREDIT_ACCOUNT)
        accounts.put(RULE_FED_ACCOUNT, manualPostingAllowed = false)
        accounts.put(CONTROL_ACCOUNT, controlAccount = true)
    }

    @AfterEach
    fun clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    /** Builds the service over [store], for the suites that decorate one of its refusals. */
    internal fun serviceOver(store: ManualJournalStore) =
        ManualJournalService(
            journals = store,
            accounts = accounts,
            makers = makers,
            engine = harness.engine,
            permissions = permissions,
            transitions = transitions,
            auditService = auditService,
            snapshots = snapshots,
            boundary = PostingTransactionBoundary(SerializablePostingTransaction()),
        )

    /**
     * Acts as [actorId], installing them as the ambient context too.
     *
     * The engine reconciles the caller's context against the ambient one, so a command whose actor
     * is not the request's actor is a `CONTEXT_MISMATCH` rather than the refusal under test.
     */
    internal fun actingAs(
        actorId: UUID,
        organisationId: UUID = ORGANISATION,
    ): AccountingContext {
        val context = AccountingContext(organisationId, BRANCH, actorId, CORRELATION)
        contextLookup.context = context
        return context
    }

    /** A transition command against [journal], acting as [actorId]. */
    internal fun move(
        journal: ManualJournal,
        actorId: UUID,
        reason: String? = null,
    ) = ManualJournalTransitionCommand(
        context = actingAs(actorId),
        journalId = journal.id,
        reason = reason,
    )

    /** An amendment of [journal] at its current row version, acting as [actorId]. */
    internal fun amendment(
        journal: ManualJournal,
        actorId: UUID,
    ) = AmendManualJournalCommand(
        context = actingAs(actorId),
        journalId = journal.id,
        expectedRowVersion = journal.rowVersion,
        content = content(title = "Restated"),
    )

    /** Draft content that passes validation unless a caller deliberately breaks it. */
    internal fun content(
        title: String = "Adjustment",
        externalReference: String? = "ADV-4471",
        branchId: UUID? = null,
        lines: List<ManualJournalLine> = balancedLines(),
    ) = ManualJournalDraftContent(
        title = title,
        externalReference = externalReference,
        narrative = NARRATIVE,
        branchId = branchId,
        transactionDate = null,
        valueDate = null,
        postingDate = null,
        lines = lines,
    )

    /** Two lines, one each side, balanced unless [credit] is given a different amount. */
    internal fun balancedLines(
        debit: String = "10.00",
        credit: String = debit,
        debitAccount: UUID = DEBIT_ACCOUNT,
    ) = listOf(
        line(1, debitAccount, PostingSide.DEBIT, debit),
        line(2, CREDIT_ACCOUNT, PostingSide.CREDIT, credit),
    )

    /** One draft line. */
    internal fun line(
        lineNumber: Int,
        accountId: UUID,
        side: PostingSide,
        amount: String,
    ) = ManualJournalLine(
        lineNumber = lineNumber,
        accountId = accountId,
        side = side,
        amount = BigDecimal(amount),
        currencyCode = CURRENCY,
        narrative = null,
    )

    /** Stores a journal and its lines directly, as a maker would have left them. */
    internal fun seed(
        status: ManualJournalStatus = ManualJournalStatus.DRAFT,
        createdBy: UUID = MAKER,
        organisationId: UUID = ORGANISATION,
        rowVersion: Long = 0,
        lines: List<ManualJournalLine> = balancedLines(),
    ): ManualJournal {
        val journal =
            ManualJournal(
                id = uuidV7(),
                organisationId = organisationId,
                branchId = BRANCH,
                title = "Adjustment",
                externalReference = "ADV-4471",
                narrative = NARRATIVE,
                status = status,
                statusReason = null,
                transactionDate = null,
                valueDate = null,
                postingDate = null,
                journalEntryId = null,
                createdBy = createdBy,
                rowVersion = rowVersion,
            )
        journals.put(journal)
        journals.putLines(journal.id, lines)
        return journal
    }

    internal companion object {
        val ORGANISATION: UUID = uuidV7()
        val OTHER_ORGANISATION: UUID = uuidV7()
        val BRANCH: UUID = uuidV7()
        val MAKER: UUID = uuidV7()
        val CHECKER: UUID = uuidV7()
        val PERIOD: UUID = uuidV7()
        val DEBIT_ACCOUNT: UUID = uuidV7()
        val CREDIT_ACCOUNT: UUID = uuidV7()
        val RULE_FED_ACCOUNT: UUID = uuidV7()
        val CONTROL_ACCOUNT: UUID = uuidV7()
        val TODAY: LocalDate = LocalDate.of(2026, 8, 15)
        val NOW: Instant = Instant.parse("2026-08-15T10:00:00Z")
        const val CURRENCY = "KES"
        const val CORRELATION = "corr-1"
        const val NARRATIVE = "Correct a mis-posting"
    }
}
