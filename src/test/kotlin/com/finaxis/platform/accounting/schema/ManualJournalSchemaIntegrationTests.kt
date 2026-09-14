package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_TRANSITION_LOG
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
 *
 * All three tables of the aggregate are covered - the header, its lines and its transition log -
 * because a rule nothing asserts is a rule a later migration can drop without a failing test.
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
    fun `a header's prose is bounded above and its row version never goes negative`() {
        val tenant = fixture.createTenant("manual-schema-header-bounds")

        // Both bounds are inclusive, so the longest admissible title and narrative are stored.
        insertDraft(
            tenant.organisationId,
            title = "T".repeat(200),
            narrative = "N".repeat(500),
        )

        assertViolates("chk_manual_journal_title") {
            insertDraft(tenant.organisationId, title = "T".repeat(201))
        }
        assertViolates("chk_manual_journal_narrative") {
            insertDraft(tenant.organisationId, narrative = "N".repeat(501))
        }
        // row_version counts optimistic-locking revisions, so a negative one is a defect in
        // whatever wrote it rather than a state the aggregate can reach.
        assertViolates("chk_manual_journal_version") {
            insertDraft(tenant.organisationId, rowVersion = -1)
        }
    }

    @Test
    fun `a draft names a branch of its own tenant, or no branch at all`() {
        val tenant = fixture.createTenant("manual-schema-branch")
        val other = fixture.createTenant("manual-schema-branch-other")

        insertDraft(tenant.organisationId, branchId = tenant.branchId)
        // Branch is optional: a head-office adjustment belongs to the tenant, not to a branch.
        insertDraft(tenant.organisationId, branchId = null)

        // The foreign key is composite, so it is the database - not the application - that stops
        // a draft borrowing another tenant's branch.
        assertViolates("fk_manual_journal_branch") {
            insertDraft(tenant.organisationId, branchId = other.branchId)
        }
    }

    @Test
    fun `an external reference is optional, bounded and never blank`() {
        val tenant = fixture.createTenant("manual-schema-reference")

        // Absent is the ordinary case, and so is a real document number at the bound.
        insertDraft(tenant.organisationId, externalReference = null)
        insertDraft(tenant.organisationId, externalReference = "BANK-ADVICE-4471")
        insertDraft(tenant.organisationId, externalReference = "R".repeat(100))

        // Blank is refused rather than stored: indistinguishable from nothing, while still
        // occupying a column reports are grouped by.
        assertViolates("chk_manual_journal_external_reference") {
            insertDraft(tenant.organisationId, externalReference = "")
        }
        assertViolates("chk_manual_journal_external_reference") {
            insertDraft(tenant.organisationId, externalReference = "   ")
        }
        // Whitespace that is not a space, too. The first revision of this constraint was
        // `btrim(external_reference) <> ''`, and btrim strips spaces only - so a tab-only or
        // newline-only reference was stored by a column whose service refuses it as blank, and
        // the two contracts disagreed about one value.
        assertViolates("chk_manual_journal_external_reference") {
            insertDraft(tenant.organisationId, externalReference = "\t")
        }
        assertViolates("chk_manual_journal_external_reference") {
            insertDraft(tenant.organisationId, externalReference = "\n\t ")
        }
        assertViolates("chk_manual_journal_external_reference") {
            insertDraft(tenant.organisationId, externalReference = "R".repeat(101))
        }
        // The bound is characters, as `ManualJournalPolicy` counts them: 100 supplementary
        // characters is 200 UTF-16 units and the column takes it.
        insertDraft(tenant.organisationId, externalReference = "🏦".repeat(100))
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
        assertViolates("chk_manual_journal_line_amount") {
            insertLine(tenant, draft, 2, "CREDIT", amount = "-1")
        }
        assertViolates("fk_manual_journal_line_account") {
            insertLine(tenant, draft, 2, "CREDIT", account = other.creditAccountId)
        }
        assertViolates("fk_manual_journal_line_journal") {
            insertLine(tenant, insertDraft(other.organisationId), 1, "DEBIT")
        }
    }

    @Test
    fun `a line's currency, narrative and row version are bounded`() {
        val tenant = fixture.createTenant("manual-schema-line-bounds")
        val draft = insertDraft(tenant.organisationId)

        // A line narrative is optional, unlike the header's, and 500 characters is the bound.
        insertLine(tenant, draft, 1, "DEBIT", narrative = null)
        insertLine(
            tenant,
            draft,
            2,
            "CREDIT",
            account = tenant.creditAccountId,
            narrative = "N".repeat(500),
        )

        // ISO 4217 is upper case; a lower-case or digit-bearing code is a currency nothing can
        // report on, and CHAR(3) alone would happily store either.
        assertViolates("chk_manual_journal_line_currency") {
            insertLine(tenant, draft, 3, "DEBIT", currency = "kes")
        }
        assertViolates("chk_manual_journal_line_currency") {
            insertLine(tenant, draft, 3, "DEBIT", currency = "K1S")
        }
        assertViolates("chk_manual_journal_line_narrative") {
            insertLine(tenant, draft, 3, "DEBIT", narrative = "N".repeat(501))
        }
        assertViolates("chk_manual_journal_line_version") {
            insertLine(tenant, draft, 3, "DEBIT", rowVersion = -1)
        }
    }

    @Test
    fun `a transition log row belongs to a draft of its own tenant`() {
        val tenant = fixture.createTenant("manual-schema-log")
        val other = fixture.createTenant("manual-schema-log-other")
        val draft = insertDraft(tenant.organisationId)
        val otherDraft = insertDraft(other.organisationId)

        val logId = insertTransitionLog(tenant.organisationId, draft)
        assertEquals(
            "{}",
            dsl
                .select(MANUAL_JOURNAL_TRANSITION_LOG.METADATA_JSONB)
                .from(MANUAL_JOURNAL_TRANSITION_LOG)
                .where(MANUAL_JOURNAL_TRANSITION_LOG.ID.eq(logId))
                .fetchOne()!!
                .value1()!!
                .data(),
            "metadata_jsonb defaults to an empty object, so a reader never meets NULL",
        )

        // The foreign key is composite on (organisation_id, entity_id): neither half alone
        // decides, so a log row can name neither another tenant's draft nor its own draft under
        // another tenant's identifier - which is what makes the history answerable per tenant.
        assertViolates("fk_manual_journal_transition_log_journal") {
            insertTransitionLog(tenant.organisationId, otherDraft)
        }
        assertViolates("fk_manual_journal_transition_log_journal") {
            insertTransitionLog(other.organisationId, draft)
        }
        assertViolates("chk_manual_journal_transition_log_version") {
            insertTransitionLog(tenant.organisationId, draft, rowVersion = -1)
        }
    }

    @Test
    fun `guid is a unique alternate key on each manual-journal table`() {
        val tenant = fixture.createTenant("manual-schema-guid")
        // One value across three tables: uniqueness is per table, so this is admissible and the
        // second row of each table is the one that must be refused.
        val shared = UUID.randomUUID()
        val draft = insertDraft(tenant.organisationId, guid = shared)
        insertLine(tenant, draft, 1, "DEBIT", guid = shared)
        insertTransitionLog(tenant.organisationId, draft, guid = shared)

        assertViolates("uq_manual_journal_guid") {
            insertDraft(tenant.organisationId, guid = shared)
        }
        assertViolates("uq_manual_journal_line_guid") {
            insertLine(tenant, draft, 2, "CREDIT", guid = shared)
        }
        assertViolates("uq_manual_journal_transition_log_guid") {
            insertTransitionLog(tenant.organisationId, draft, guid = shared)
        }
    }

    // ---- fixture ---------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun insertDraft(
        organisationId: UUID,
        status: String = "DRAFT",
        journalEntryId: UUID? = null,
        title: String = "Adjustment",
        narrative: String = "Correct a mis-posting",
        externalReference: String? = null,
        branchId: UUID? = null,
        rowVersion: Long = 0,
        guid: UUID? = null,
    ): UUID =
        dsl
            .insertInto(MANUAL_JOURNAL)
            .apply { if (guid != null) set(MANUAL_JOURNAL.GUID, guid) }
            .set(MANUAL_JOURNAL.ORGANISATION_ID, organisationId)
            .set(MANUAL_JOURNAL.BRANCH_ID, branchId)
            .set(MANUAL_JOURNAL.TITLE, title)
            .set(MANUAL_JOURNAL.EXTERNAL_REFERENCE, externalReference)
            .set(MANUAL_JOURNAL.NARRATIVE, narrative)
            .set(MANUAL_JOURNAL.STATUS, status)
            .set(MANUAL_JOURNAL.JOURNAL_ENTRY_ID, journalEntryId)
            .set(MANUAL_JOURNAL.ROW_VERSION, rowVersion)
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
        currency: String = "KES",
        narrative: String? = null,
        rowVersion: Long = 0,
        guid: UUID? = null,
    ): UUID =
        dsl
            .insertInto(MANUAL_JOURNAL_LINE)
            .apply { if (guid != null) set(MANUAL_JOURNAL_LINE.GUID, guid) }
            .set(MANUAL_JOURNAL_LINE.ORGANISATION_ID, tenant.organisationId)
            .set(MANUAL_JOURNAL_LINE.MANUAL_JOURNAL_ID, draft)
            .set(MANUAL_JOURNAL_LINE.LINE_NUMBER, number)
            .set(MANUAL_JOURNAL_LINE.GL_ACCOUNT_ID, account)
            .set(MANUAL_JOURNAL_LINE.DIRECTION, direction)
            .set(MANUAL_JOURNAL_LINE.AMOUNT, BigDecimal(amount))
            .set(MANUAL_JOURNAL_LINE.CURRENCY_CODE, currency)
            .set(MANUAL_JOURNAL_LINE.NARRATIVE, narrative)
            .set(MANUAL_JOURNAL_LINE.ROW_VERSION, rowVersion)
            .set(MANUAL_JOURNAL_LINE.CREATED_AT, OffsetDateTime.now())
            .set(MANUAL_JOURNAL_LINE.UPDATED_AT, OffsetDateTime.now())
            .returning(MANUAL_JOURNAL_LINE.ID)
            .fetchOne()!!
            .id!!

    @Suppress("LongParameterList")
    private fun insertTransitionLog(
        organisationId: UUID,
        entityId: UUID,
        transitionName: String = "submit",
        statusFrom: String = "DRAFT",
        statusTo: String = "PENDING_APPROVAL",
        rowVersion: Long = 0,
        guid: UUID? = null,
    ): UUID =
        dsl
            .insertInto(MANUAL_JOURNAL_TRANSITION_LOG)
            .apply { if (guid != null) set(MANUAL_JOURNAL_TRANSITION_LOG.GUID, guid) }
            .set(MANUAL_JOURNAL_TRANSITION_LOG.ORGANISATION_ID, organisationId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.ENTITY_ID, entityId)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.TRANSITION_NAME, transitionName)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.STATUS_FROM, statusFrom)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.STATUS_TO, statusTo)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.ROW_VERSION, rowVersion)
            .set(MANUAL_JOURNAL_TRANSITION_LOG.CREATED_AT, OffsetDateTime.now())
            .set(MANUAL_JOURNAL_TRANSITION_LOG.UPDATED_AT, OffsetDateTime.now())
            .returning(MANUAL_JOURNAL_TRANSITION_LOG.ID)
            .fetchOne()!!
            .id!!
}
