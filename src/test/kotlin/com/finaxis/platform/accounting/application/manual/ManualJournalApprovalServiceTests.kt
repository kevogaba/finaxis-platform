package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.ManualJournalTransition
import com.finaxis.platform.accounting.support.FakeManualJournalStore
import com.finaxis.platform.accounting.support.ManualJournalAccessKind
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditSeverity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A manual journal from the submission onwards: the checker, the posting, and the terminal states.
 *
 * The seam with [ManualJournalServiceTests] is the maker's hand leaving the draft. Everything here
 * is about what the service does once someone else is involved - that the header's lock is taken
 * before its status moves, that the lines validated are the ones the store holds rather than the
 * ones the maker last sent, that a checker is not the submitter, and that approval reaches the real
 * posting engine, which is assembled over its own fakes rather than stubbed because a stub cannot
 * fail the way a posting fails.
 *
 * The collaborators, the builders and the transaction the engine requires are
 * [ManualJournalServiceTestFixture]'s.
 */
class ManualJournalApprovalServiceTests : ManualJournalServiceTestFixture() {
    // ---- submit -----------------------------------------------------------------------------

    @Test
    fun `submitting a draft moves it to pending approval under the header's lock`() {
        val journal = seed()

        val submitted = service.submit(move(journal, MAKER))

        assertEquals(listOf(AccountingPermissions.JOURNAL_SUBMIT), permissions.checkedCodes)
        assertEquals(ManualJournalStatus.PENDING_APPROVAL, submitted.status)
        val moved = journals.statusMoves.single()
        assertEquals(ManualJournalStatus.DRAFT, moved.from)
        assertEquals(ManualJournalStatus.PENDING_APPROVAL, moved.to)
        assertEquals(MAKER, moved.actorId)
        assertNull(moved.journalEntryId)
        assertTrue(
            journals.accesses.indexOfFirst { it.kind == ManualJournalAccessKind.LOCK } <
                journals.accesses.indexOfFirst {
                    it.kind == ManualJournalAccessKind.UPDATE_STATUS
                },
        )

        val logged = transitionLogs.single()
        assertEquals(ManualJournalTransition.SUBMIT.name, logged.transition)
        assertEquals(ManualJournalStatus.DRAFT.name, logged.fromState)
        assertEquals(ManualJournalStatus.PENDING_APPROVAL.name, logged.toState)
        assertEquals(journal.id.toString(), logged.aggregateId)
        assertEquals(ORGANISATION.toString(), logged.metadata["organisationId"])
        // A submission is recorded as a transition, not as an audit event.
        assertTrue(auditEvents.isEmpty())
    }

    @Test
    fun `submission validates the lines the store holds, not the ones the maker last sent`() {
        val unbalanced = seed(lines = balancedLines(debit = "10.00", credit = "9.00"))

        val failure =
            assertFailsWith<InvalidOperationException> { service.submit(move(unbalanced, MAKER)) }
        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, failure.code)

        // An account that was manual-postable when the draft was keyed, and is not any more.
        val revoked = seed(lines = balancedLines(debitAccount = RULE_FED_ACCOUNT))
        val closed =
            assertFailsWith<InvalidOperationException> { service.submit(move(revoked, MAKER)) }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, closed.code)
        assertTrue(journals.statusMoves.isEmpty())
    }

    @Test
    fun `only the maker may submit their own draft`() {
        val journal = seed()

        val refused =
            assertFailsWith<ForbiddenOperationException> { service.submit(move(journal, CHECKER)) }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, refused.code)
        assertTrue(journals.statusMoves.isEmpty())
        assertTrue(transitionLogs.isEmpty())
    }

    @Test
    fun `a journal already awaiting a checker cannot be submitted again`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)

        val refused =
            assertFailsWith<ConflictException> { service.submit(move(journal, MAKER)) }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED, refused.code)
        assertTrue(journals.statusMoves.isEmpty())
    }

    // ---- approve ----------------------------------------------------------------------------

    @Test
    fun `approval posts through the engine and records the journal it produced`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)
        makers.performedBy(journal.id, ManualJournalTransition.SUBMIT, MAKER)

        val approval = service.approve(move(journal, CHECKER, reason = "Reviewed"))

        assertEquals(listOf(AccountingPermissions.JOURNAL_APPROVE), permissions.checkedCodes)
        assertEquals(ManualJournalStatus.POSTED, approval.journal.status)
        assertEquals(approval.receipt.journalEntryId, approval.journal.journalEntryId)
        assertEquals(journals.statusMoves.single().journalEntryId, approval.receipt.journalEntryId)
        assertEquals(2, approval.receipt.lineCount)

        val request = postings.requests.single()
        assertEquals("accounting", request.sourceModule)
        assertEquals("MANUAL_JOURNAL", request.sourceEntityType)
        assertEquals(journal.id, request.sourceEntityId)
        assertEquals("manual-journal:${journal.id}", request.sourceReference)
        assertEquals("MANUAL_JOURNAL", request.eventCode)

        val entry = postings.entries.single()
        assertEquals(JournalEntryType.MANUAL, entry.entryType)
        assertEquals(2, entry.lineCount)
        assertEquals(journal.narrative, entry.narrative)
        assertEquals(listOf(DEBIT_ACCOUNT, CREDIT_ACCOUNT), postings.lines.map { it.leg.accountId })
        // A manual journal resolves no posting rule, exactly as the column documents.
        assertEquals(listOf<UUID?>(null), postings.ruleVersions)
    }

    @Test
    fun `the approval is audited as critical, naming the journal and its number`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)
        makers.performedBy(journal.id, ManualJournalTransition.SUBMIT, MAKER)

        val approval = service.approve(move(journal, CHECKER, reason = "Reviewed"))

        val audited = auditEvents.single()
        assertEquals(AccountingAuditActions.JOURNAL_APPROVE, audited.action)
        assertEquals(AuditSeverity.CRITICAL, audited.severity)
        assertEquals("Reviewed", audited.reason)
        assertEquals(journal.id.toString(), audited.resourceId)
        assertEquals(approval.receipt.journalEntryId.toString(), audited.metadata["journalEntryId"])
        assertEquals(approval.receipt.journalReference, audited.metadata["entryNumber"])
        assertEquals("ADV-4471", audited.metadata["externalReference"])
    }

    @Test
    fun `the actor who submitted a manual journal cannot approve it`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)
        makers.performedBy(journal.id, ManualJournalTransition.SUBMIT, CHECKER)

        val refused =
            assertFailsWith<ForbiddenOperationException> {
                service.approve(move(journal, CHECKER))
            }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_SELF_APPROVAL, refused.code)
        assertEquals(
            listOf(journal.id to ManualJournalTransition.SUBMIT),
            makers.lookups,
        )
        // Refused before the engine is entered, so nothing was claimed and nothing moved.
        assertTrue(postings.requests.isEmpty())
        assertTrue(journals.statusMoves.isEmpty())
        assertEquals(
            ManualJournalStatus.PENDING_APPROVAL,
            journals.journals.getValue(journal.id).status,
        )
    }

    @Test
    fun `a journal whose log records no submitter is approvable by any permitted checker`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)
        makers.neverPerformed(journal.id, ManualJournalTransition.SUBMIT)

        val approval = service.approve(move(journal, CHECKER))

        assertEquals(ManualJournalStatus.POSTED, approval.journal.status)
        assertEquals(1, postings.entries.size)
    }

    @Test
    fun `a posting the engine refuses leaves the journal pending approval and nothing posted`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)
        makers.performedBy(journal.id, ManualJournalTransition.SUBMIT, MAKER)
        numbers.next = null

        val failure =
            assertFailsWith<ConflictException> { service.approve(move(journal, CHECKER)) }

        assertEquals(PostingErrorCodes.JOURNAL_SEQUENCE_MISSING, failure.code)
        assertEquals(
            ManualJournalStatus.PENDING_APPROVAL,
            journals.journals.getValue(journal.id).status,
        )
        assertNull(journals.journals.getValue(journal.id).journalEntryId)
        // The status move is attempted only after the posting returns a receipt.
        assertTrue(journals.statusMoves.isEmpty())
        assertTrue(postings.entries.isEmpty())
        assertTrue(postings.posted.isEmpty())
        assertTrue(transitionLogs.isEmpty())
        assertTrue(auditEvents.isEmpty())
    }

    @Test
    fun `a draft cannot be approved, and the checker rule is not even consulted`() {
        val journal = seed()

        val refused =
            assertFailsWith<ConflictException> { service.approve(move(journal, CHECKER)) }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED, refused.code)
        // Legality is decided before the separation-of-duties lookup and before the engine.
        assertTrue(makers.lookups.isEmpty())
        assertTrue(postings.requests.isEmpty())
    }

    @Test
    fun `a status compare-and-set that loses its race is a stale conflict`() {
        val journal = seed()
        val losing = serviceOver(StoreLosingTheStatusRace(journals))

        val stale =
            assertFailsWith<ConflictException> { losing.submit(move(journal, MAKER)) }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_STALE, stale.code)
        assertTrue(transitionLogs.isEmpty())
    }

    // ---- reject and cancel ------------------------------------------------------------------

    @Test
    fun `a rejection without a reason is refused before the header is even read`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)

        listOf(null, "", "   ").forEach { reason ->
            val refused =
                assertFailsWith<InvalidOperationException> {
                    service.reject(move(journal, CHECKER, reason = reason))
                }
            assertEquals(
                PostingErrorCodes.MANUAL_JOURNAL_REASON_REQUIRED,
                refused.code,
                "reason [$reason]",
            )
        }
        assertTrue(journals.accesses.isEmpty())
        assertTrue(journals.statusMoves.isEmpty())
    }

    @Test
    fun `a checker rejects a submitted journal back to draft with the reason recorded`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)

        val rejected = service.reject(move(journal, CHECKER, reason = "Wrong account"))

        assertEquals(listOf(AccountingPermissions.JOURNAL_APPROVE), permissions.checkedCodes)
        assertEquals(ManualJournalStatus.DRAFT, rejected.status)
        assertEquals("Wrong account", rejected.statusReason)
        assertEquals("Wrong account", journals.statusMoves.single().reason)
        // Rejection is the checker's act: it deliberately does not require the maker.
        assertEquals(CHECKER, journals.statusMoves.single().actorId)
        val audited = auditEvents.single()
        assertEquals(AccountingAuditActions.JOURNAL_REJECT, audited.action)
        assertEquals(AuditSeverity.CRITICAL, audited.severity)
    }

    @Test
    fun `a draft cannot be rejected`() {
        val journal = seed()

        val refused =
            assertFailsWith<ConflictException> {
                service.reject(move(journal, CHECKER, reason = "Wrong account"))
            }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED, refused.code)
        assertTrue(journals.statusMoves.isEmpty())
    }

    @Test
    fun `the maker cancels their own draft, and nobody else can`() {
        val journal = seed()

        val refused =
            assertFailsWith<ForbiddenOperationException> { service.cancel(move(journal, CHECKER)) }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER, refused.code)
        assertTrue(journals.statusMoves.isEmpty())

        val cancelled = service.cancel(move(journal, MAKER, reason = "Keyed twice"))

        assertEquals(
            listOf(
                AccountingPermissions.JOURNAL_CREATE_MANUAL,
                AccountingPermissions.JOURNAL_CREATE_MANUAL,
            ),
            permissions.checkedCodes,
        )
        assertEquals(ManualJournalStatus.CANCELLED, cancelled.status)
        assertEquals("Keyed twice", cancelled.statusReason)
    }

    @Test
    fun `a submitted journal cannot be cancelled behind the checker's back`() {
        val journal = seed(status = ManualJournalStatus.PENDING_APPROVAL)

        val refused =
            assertFailsWith<ConflictException> { service.cancel(move(journal, MAKER)) }

        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED, refused.code)
        assertTrue(journals.statusMoves.isEmpty())
    }

    @Test
    fun `a posted journal is terminal, and so is a cancelled one`() {
        listOf(ManualJournalStatus.POSTED, ManualJournalStatus.CANCELLED).forEach { status ->
            val journal = seed(status = status)
            val command = move(journal, MAKER, reason = "Reason")

            listOf<(ManualJournalTransitionCommand) -> Any>(
                service::submit,
                service::approve,
                service::reject,
                service::cancel,
            ).forEach { transition ->
                assertEquals(
                    PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED,
                    assertFailsWith<ConflictException> { transition(command) }.code,
                    "status $status",
                )
            }
        }
        assertTrue(journals.statusMoves.isEmpty())
        assertTrue(postings.requests.isEmpty())
    }
}

/** Loses every status compare-and-set, as a second mover on the same journal would. */
private class StoreLosingTheStatusRace(
    delegate: FakeManualJournalStore,
) : ManualJournalStore by delegate {
    @Suppress("LongParameterList")
    override fun updateStatus(
        organisationId: UUID,
        journalId: UUID,
        from: ManualJournalStatus,
        to: ManualJournalStatus,
        reason: String?,
        journalEntryId: UUID?,
        actorId: UUID,
    ): Boolean = false
}
