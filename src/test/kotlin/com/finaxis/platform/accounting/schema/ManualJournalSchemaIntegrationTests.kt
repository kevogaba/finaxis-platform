package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_LINE
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * What PostgreSQL enforces about manual-journal drafts, proved against PostgreSQL. Every rule is
 * stated in `docs/database/accounting-erd.md` under the issue #48 column definitions.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ManualJournalSchemaIntegrationTests(
    private val dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a draft with two lines is accepted and its status domain is bounded`() {
        val tenant = fixture.createTenant("manual-schema")
        val draft = insertDraft(tenant.organisationId)
        insertLine(tenant, draft, 1, "DEBIT")
        insertLine(tenant, draft, 2, "CREDIT", account = tenant.creditAccountId)
        assertEquals(
            2,
            dsl.fetchCount(MANUAL_JOURNAL_LINE, MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID.eq(draft)),
        )

        assertViolates("chk_manual_journal_status") {
            insertDraft(tenant.organisationId, status = "APPROVED")
        }
        assertViolates("chk_manual_journal_posted_has_entry") {
            insertDraft(tenant.organisationId, status = "POSTED")
        }
        assertViolates("chk_manual_journal_narrative") {
            insertDraft(tenant.organisationId, narrative = "")
        }
        assertViolates("chk_manual_journal_title") {
            insertDraft(tenant.organisationId, title = "")
        }
    }

    @Test
    fun `a posted draft names exactly one tenant-safe journal, at most once`() {
        val tenant = fixture.createTenant("manual-schema-posted")
        val other = fixture.createTenant("manual-schema-other")
        val journal = fixture.insertBalancedJournal(tenant)
        val otherJournal = fixture.insertBalancedJournal(other)
        insertDraft(tenant.organisationId, status = "POSTED", journalEntryId = journal)

        assertViolates("uq_manual_journal_entry") {
            insertDraft(tenant.organisationId, status = "POSTED", journalEntryId = journal)
        }
        assertViolates("fk_manual_journal_entry") {
            insertDraft(tenant.organisationId, status = "POSTED", journalEntryId = otherJournal)
        }
        assertViolates("chk_manual_journal_posted_has_entry") {
            insertDraft(
                tenant.organisationId,
                status = "DRAFT",
                journalEntryId = fixture.insertBalancedJournal(tenant),
            )
        }
    }

    @Test
    fun `lines are tenant-safe ordered positive and directed`() {
        val tenant = fixture.createTenant("manual-schema-lines")
        val other = fixture.createTenant("manual-schema-lines-other")
        val draft = insertDraft(tenant.organisationId)
        insertLine(tenant, draft, 1, "DEBIT")

        assertViolates("uq_manual_journal_line_number") { insertLine(tenant, draft, 1, "CREDIT") }
        assertViolates("chk_manual_journal_line_number") { insertLine(tenant, draft, 0, "CREDIT") }
        assertViolates("chk_manual_journal_line_direction") { insertLine(tenant, draft, 2, "DR") }
        assertViolates("chk_manual_journal_line_amount") {
            insertLine(tenant, draft, 2, "CREDIT", amount = "0")
        }
        assertViolates("fk_manual_journal_line_account") {
            insertLine(tenant, draft, 2, "CREDIT", account = other.creditAccountId)
        }
        assertViolates("fk_manual_journal_line_journal") {
            insertLine(tenant, insertDraft(other.organisationId), 1, "DEBIT")
        }
    }

    private fun insertDraft(
        organisationId: UUID,
        status: String = "DRAFT",
        journalEntryId: UUID? = null,
        title: String = "Adjustment",
        narrative: String = "Correct a mis-posting",
    ): UUID =
        dsl
            .insertInto(MANUAL_JOURNAL)
            .set(MANUAL_JOURNAL.ORGANISATION_ID, organisationId)
            .set(MANUAL_JOURNAL.TITLE, title)
            .set(MANUAL_JOURNAL.NARRATIVE, narrative)
            .set(MANUAL_JOURNAL.STATUS, status)
            .set(MANUAL_JOURNAL.JOURNAL_ENTRY_ID, journalEntryId)
            .set(MANUAL_JOURNAL.CREATED_AT, OffsetDateTime.now())
            .set(MANUAL_JOURNAL.UPDATED_AT, OffsetDateTime.now())
            .returning(MANUAL_JOURNAL.ID)
            .fetchOne()!!
            .id!!

    @Suppress("LongParameterList")
    private fun insertLine(
        tenant: JournalSchemaFixture.Tenant,
        draft: UUID,
        number: Int,
        direction: String,
        amount: String = "100",
        account: UUID = tenant.debitAccountId,
    ): UUID =
        dsl
            .insertInto(MANUAL_JOURNAL_LINE)
            .set(MANUAL_JOURNAL_LINE.ORGANISATION_ID, tenant.organisationId)
            .set(MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID, draft)
            .set(MANUAL_JOURNAL_LINE.LINE_NUMBER, number)
            .set(MANUAL_JOURNAL_LINE.GL_ACCOUNT_ID, account)
            .set(MANUAL_JOURNAL_LINE.DIRECTION, direction)
            .set(MANUAL_JOURNAL_LINE.AMOUNT, BigDecimal(amount))
            .set(MANUAL_JOURNAL_LINE.CURRENCY_CODE, "KES")
            .set(MANUAL_JOURNAL_LINE.CREATED_AT, OffsetDateTime.now())
            .set(MANUAL_JOURNAL_LINE.UPDATED_AT, OffsetDateTime.now())
            .returning(MANUAL_JOURNAL_LINE.ID)
            .fetchOne()!!
            .id!!
}
