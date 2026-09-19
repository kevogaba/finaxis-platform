package com.finaxis.platform.accounting.subledger

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerPosition
import com.finaxis.platform.accounting.SubledgerStatementProvider
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import org.jooq.DSLContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID

/**
 * The conformance suite, run against the general ledger's own implementation of it.
 *
 * This is what keeps [SubledgerStatementContract] from being a document with a test-shaped wrapper:
 * every obligation it states is demonstrated here against PostgreSQL, so a future product module
 * extending the suite is inheriting assertions that are known to be satisfiable rather than merely
 * reasonable.
 *
 * The seeding writes journal lines directly. These tests are about what a *statement* makes of a
 * set of lines, and they need posting dates and recording dates chosen freely — a movement recorded
 * four days after the day it lands on is the case the suite exists for, and the engine would refuse
 * to let a test arrange it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GeneralLedgerStatementContractTests(
    private val dsl: DSLContext,
    private val statements: SubledgerStatementProvider,
) : SubledgerStatementContract() {
    private val fixture = JournalSchemaFixture(dsl)

    override fun provider(): SubledgerStatementProvider = statements

    override fun seed(movements: List<SeededMovement>): SubledgerPosition {
        val tenant = fixture.createTenant("statement-contract")
        val reference = "POS-${UUID.randomUUID()}"
        // The position lives on a control account, which is what makes its class name it:
        // `uq_gl_account_control_kind` allows one per tenant per class.
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, true)
            .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, ControlSubledgerKind.SAVINGS_DEPOSITS.name)
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(tenant.organisationId))
            .and(GL_ACCOUNT.ID.eq(tenant.creditAccountId))
            .execute()
        movements.forEach { movement ->
            // A signed movement becomes an ordinary balanced journal. The position's leg always
            // sits on the control account and changes *direction* with the sign - it does not move
            // to another account, which is what a real position does and what makes the reversal
            // case meaningful: a positive amount against a negative position IS the reversal.
            val credit = movement.signedAmount.signum() < 0
            val amount = movement.signedAmount.abs()
            val journalId =
                fixture.insertJournalEntry(
                    tenant,
                    totalDebit = amount,
                    totalCredit = amount,
                    businessDate = movement.recordedOn,
                    postingDate = movement.postingDate,
                )
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 1,
                glAccountId = tenant.creditAccountId,
                direction = if (credit) "CREDIT" else "DEBIT",
                amount = amount,
                postingDate = movement.postingDate,
                subledgerReference = reference,
            )
            // The contra leg carries the reference too, because `PostingRulePolicy.allocate`
            // stamps a fact's position reference on every leg it drives - both sides of the same
            // fact. A fixture that referenced only the position's own leg would hide the defect
            // this restriction exists for: matching on the reference alone selects both halves of
            // a balanced posting and sums them to zero.
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 2,
                glAccountId = tenant.debitAccountId,
                direction = if (credit) "DEBIT" else "CREDIT",
                amount = amount,
                postingDate = movement.postingDate,
                subledgerReference = reference,
            )
        }
        return SubledgerPosition(
            organisationId = tenant.organisationId,
            ownerModule = OWNER_MODULE,
            kind = ControlSubledgerKind.SAVINGS_DEPOSITS,
            reference = reference,
        )
    }

    private companion object {
        /** `JournalSchemaFixture` stamps every line it writes with this module. */
        const val OWNER_MODULE = "savings"
    }
}
