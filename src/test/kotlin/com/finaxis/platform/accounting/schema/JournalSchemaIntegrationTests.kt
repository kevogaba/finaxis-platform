package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.PERIOD_DAY
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What PostgreSQL enforces about the journal, proved against PostgreSQL.
 *
 * Every rule here is stated in `docs/database/accounting-erd.md` under *"Column definitions for
 * the issue #40 tables"*. The document is the authority; these tests are what stop it and the
 * schema drifting apart. Each rejection asserts the **named** constraint, so a test cannot pass
 * because a different constraint happened to fire first.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JournalSchemaIntegrationTests(
    private val dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a balanced two-line journal is accepted and its signed amounts are generated`() {
        // The positive control. Every other test here asserts a rejection, and all of them would
        // pass against a table that rejected everything.
        val tenant = fixture.createTenant("journal-positive")

        val journalId = fixture.insertBalancedJournal(tenant)

        val signed =
            dsl
                .select(JOURNAL_LINE.DIRECTION, JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT)
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalId))
                .orderBy(JOURNAL_LINE.LINE_NUMBER)
                .fetch { it.value1() to it.value2()!!.toPlainString() }
        assertEquals(
            listOf("DEBIT" to "100.000000", "CREDIT" to "-100.000000"),
            signed,
            "signed_functional_amount is generated: positive for a debit, negative for a credit",
        )
    }

    @Test
    fun `the immutable tables carry no update columns and no row version`() {
        // ADR 0020: the absence is the point. There is no column for an update to maintain, so the
        // schema itself says the row is append-only.
        listOf("journal_entry", "journal_line").forEach { table ->
            val columns = columnsOf(table)
            assertTrue("created_at" in columns && "created_by" in columns, "$table audit set")
            assertTrue(
                columns.none { it in setOf("updated_at", "updated_by", "row_version") },
                "$table must carry no mutable audit column, but has $columns",
            )
        }
        assertTrue("row_version" in columnsOf("posting_request"), "the request stays mutable")
    }

    @Test
    fun `a zero or negative line amount is rejected`() {
        val tenant = fixture.createTenant("journal-amount")
        val journalId = fixture.insertJournalEntry(tenant)

        assertViolates("chk_journal_line_amount") {
            fixture.insertJournalLine(tenant, journalId, amount = BigDecimal.ZERO)
        }
        assertViolates("chk_journal_line_amount") {
            fixture.insertJournalLine(tenant, journalId, amount = BigDecimal("-1"))
        }
        // The invariant column, independently: a conversion defect writing a zero functional
        // amount would satisfy every other constraint while cancelling a balance.
        assertViolates("chk_journal_line_functional_amount") {
            fixture.insertJournalLine(tenant, journalId, functionalAmount = BigDecimal.ZERO)
        }
        assertViolates("chk_journal_line_exchange_rate") {
            fixture.insertJournalLine(tenant, journalId, exchangeRate = BigDecimal.ZERO)
        }
    }

    @Test
    fun `an unbalanced or degenerate header is rejected`() {
        val tenant = fixture.createTenant("journal-header")

        assertViolates("chk_journal_entry_balanced") {
            fixture.insertJournalEntry(tenant, totalCredit = BigDecimal("99.000000"))
        }
        assertViolates("chk_journal_entry_total_positive") {
            fixture.insertJournalEntry(
                tenant,
                totalDebit = BigDecimal.ZERO,
                totalCredit = BigDecimal.ZERO,
            )
        }
        assertViolates("chk_journal_entry_line_count") {
            fixture.insertJournalEntry(tenant, lineCount = 1)
        }
        assertViolates("chk_journal_entry_number") {
            fixture.insertJournalEntry(tenant, entryNumber = 0)
        }
    }

    @Test
    fun `entry numbers are unique per tenant and a request produces at most one journal`() {
        val tenant = fixture.createTenant("journal-uniqueness")
        val requestId = fixture.insertPostingRequest(tenant.organisationId)
        fixture.insertJournalEntry(tenant, postingRequestId = requestId, entryNumber = 1)

        assertViolates("uq_journal_entry_number") {
            fixture.insertJournalEntry(tenant, entryNumber = 1)
        }
        assertViolates("uq_journal_entry_posting_request") {
            fixture.insertJournalEntry(tenant, postingRequestId = requestId, entryNumber = 2)
        }
        // The same number in another tenant is fine: numbering is per tenant, not global.
        val other = fixture.createTenant("journal-uniqueness-other")
        fixture.insertJournalEntry(other, entryNumber = 1)
    }

    @Test
    fun `the reversal link and the REVERSAL type are equivalent`() {
        val tenant = fixture.createTenant("journal-reversal-link")
        val original = fixture.insertBalancedJournal(tenant)

        assertViolates("chk_journal_entry_reversal_link") {
            fixture.insertJournalEntry(tenant, entryType = "REVERSAL")
        }
        assertViolates("chk_journal_entry_reversal_link") {
            fixture.insertJournalEntry(
                tenant,
                entryType = "STANDARD",
                reversesJournalEntryId = original,
            )
        }
        assertViolates("chk_journal_entry_type") {
            fixture.insertJournalEntry(tenant, entryType = "CORRECTION")
        }
    }

    @Test
    fun `a journal is reversed at most once and never by itself`() {
        val tenant = fixture.createTenant("journal-reversal-once")
        val original = fixture.insertBalancedJournal(tenant)
        fixture.insertJournalEntry(
            tenant,
            entryType = "REVERSAL",
            reversesJournalEntryId = original,
        )

        assertViolates("uq_journal_entry_reversal_once") {
            fixture.insertJournalEntry(
                tenant,
                entryType = "REVERSAL",
                reversesJournalEntryId = original,
            )
        }
        // Self-reversal needs the id before the insert, which the application never has; the
        // CHECK exists for a row written outside the application.
        val selfId = UUID.randomUUID()
        assertViolates("chk_journal_entry_not_self_reversal") {
            fixture.insertJournalEntry(
                tenant,
                entryType = "REVERSAL",
                reversesJournalEntryId = selfId,
                id = selfId,
            )
        }
    }

    @Test
    fun `cross-tenant lines journals and reversals are impossible`() {
        val tenant = fixture.createTenant("journal-tenant-a")
        val other = fixture.createTenant("journal-tenant-b")
        val journalId = fixture.insertBalancedJournal(tenant)
        val otherJournalId = fixture.insertBalancedJournal(other)

        // A line naming another tenant's account, journal or period.
        assertViolates("fk_journal_line_account") {
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 3,
                glAccountId = other.debitAccountId,
            )
        }
        assertViolates("fk_journal_line_entry") {
            fixture.insertJournalLine(tenant, otherJournalId, lineNumber = 3)
        }
        assertViolates("fk_journal_line_fiscal_period") {
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 3,
                fiscalPeriodId = other.fiscalPeriodId,
            )
        }
        // A journal bound to another tenant's period, branch or request.
        assertViolates("fk_journal_entry_fiscal_period") {
            fixture.insertJournalEntry(tenant, fiscalPeriodId = other.fiscalPeriodId)
        }
        assertViolates("fk_journal_entry_branch") {
            fixture.insertJournalEntry(tenant, branchId = other.branchId)
        }
        assertViolates("fk_journal_entry_posting_request") {
            fixture.insertJournalEntry(
                tenant,
                postingRequestId = fixture.insertPostingRequest(other.organisationId),
            )
        }
        // A reversal pointing across tenants.
        assertViolates("fk_journal_entry_reverses") {
            fixture.insertJournalEntry(
                tenant,
                entryType = "REVERSAL",
                reversesJournalEntryId = otherJournalId,
            )
        }
        // A correction pointing across tenants.
        assertViolates("fk_posting_request_corrects") {
            fixture.insertPostingRequest(
                tenant.organisationId,
                correctsPostingRequestId = fixture.insertPostingRequest(other.organisationId),
            )
        }
    }

    @Test
    fun `line numbers are unique within a journal`() {
        val tenant = fixture.createTenant("journal-line-number")
        val journalId = fixture.insertJournalEntry(tenant)
        fixture.insertJournalLine(tenant, journalId, lineNumber = 1)

        assertViolates("uq_journal_line_entry_number") {
            fixture.insertJournalLine(tenant, journalId, lineNumber = 1, direction = "CREDIT")
        }
        assertViolates("chk_journal_line_number") {
            fixture.insertJournalLine(tenant, journalId, lineNumber = 0)
        }
        assertViolates("chk_journal_line_direction") {
            fixture.insertJournalLine(tenant, journalId, lineNumber = 2, direction = "DR")
        }
    }

    @Test
    fun `the source reference is the tenant-scoped idempotency key`() {
        val tenant = fixture.createTenant("request-idempotency")
        val other = fixture.createTenant("request-idempotency-other")
        fixture.insertPostingRequest(tenant.organisationId, sourceReference = "deposit-1")

        assertViolates("uq_posting_request_source") {
            fixture.insertPostingRequest(tenant.organisationId, sourceReference = "deposit-1")
        }
        // Scoped by module and by tenant: the same reference from another module or tenant is a
        // different business event.
        fixture.insertPostingRequest(
            tenant.organisationId,
            sourceReference = "deposit-1",
            sourceModule = "loans",
        )
        fixture.insertPostingRequest(other.organisationId, sourceReference = "deposit-1")
    }

    @Test
    fun `a request's status and posted_at move together and its shape is bounded`() {
        val tenant = fixture.createTenant("request-status")

        assertViolates("chk_posting_request_posted_at") {
            fixture.insertPostingRequest(tenant.organisationId, status = "POSTED", postedAt = false)
        }
        assertViolates("chk_posting_request_posted_at") {
            fixture.insertPostingRequest(tenant.organisationId, status = "PENDING", postedAt = true)
        }
        assertViolates("chk_posting_request_status") {
            fixture.insertPostingRequest(
                tenant.organisationId,
                status = "REJECTED",
                postedAt = false,
            )
        }
        assertViolates("chk_posting_request_dates") {
            fixture.insertPostingRequest(
                tenant.organisationId,
                postingDate = PERIOD_DAY.plusDays(1),
            )
        }
        assertViolates("chk_posting_request_fingerprint") {
            fixture.insertPostingRequest(tenant.organisationId, fingerprint = "not-a-digest")
        }
        assertViolates("chk_posting_request_event_code") {
            fixture.insertPostingRequest(tenant.organisationId, eventCode = "savings deposit")
        }
        // A key column of idx_posting_request_source_entity, so it is bounded like the module.
        assertViolates("chk_posting_request_source_entity_type") {
            fixture.insertPostingRequest(
                tenant.organisationId,
                sourceEntityType = "a".repeat(300),
            )
        }
        // Whitespace is not a durable identity: it would make every later posting of that module
        // a retry of the first.
        assertViolates("chk_posting_request_source_reference") {
            fixture.insertPostingRequest(tenant.organisationId, sourceReference = "   ")
        }
        assertViolates("chk_posting_request_not_self_correction") {
            val id = UUID.randomUUID()
            fixture.insertPostingRequest(
                tenant.organisationId,
                status = "PENDING",
                correctsPostingRequestId = id,
                id = id,
            )
        }
    }

    @Test
    fun `a request is replaced at most once`() {
        val tenant = fixture.createTenant("request-corrects")
        val original = fixture.insertPostingRequest(tenant.organisationId)
        fixture.insertPostingRequest(
            tenant.organisationId,
            correctsPostingRequestId = original,
            sourceReference = "correction-1",
        )

        // A second correction of the same request, under a different source reference, would
        // duplicate the replacement financial effect.
        assertViolates("uq_posting_request_corrects") {
            fixture.insertPostingRequest(
                tenant.organisationId,
                correctsPostingRequestId = original,
                sourceReference = "correction-2",
            )
        }
    }

    @Test
    fun `the generated column cannot be written`() {
        // Plain SQL on purpose: jOOQ knows signed_functional_amount is generated and will not set
        // it, so the only way to prove the database refuses the write is to bypass jOOQ's own
        // protection.
        val tenant = fixture.createTenant("journal-generated")
        val journalId = fixture.insertJournalEntry(tenant)

        val failure =
            assertFailsWith<DataAccessException> {
                dsl.execute(
                    """
                    INSERT INTO journal_line (
                        organisation_id, journal_entry_id, line_number, gl_account_id,
                        fiscal_period_id, posting_date, direction, currency_code, amount,
                        functional_currency_code, functional_amount, exchange_rate,
                        signed_functional_amount, source_module, created_at
                    )
                    VALUES (?, ?, 1, ?, ?, ?, 'DEBIT', 'KES', 100, 'KES', 100, 1, 100, 'savings',
                            NOW())
                    """.trimIndent(),
                    tenant.organisationId,
                    journalId,
                    tenant.debitAccountId,
                    tenant.fiscalPeriodId,
                    PERIOD_DAY,
                )
            }
        assertTrue(
            failure.mostSpecificCause.message
                .orEmpty()
                .contains("generated"),
            "expected the generated-column error, got: ${failure.mostSpecificCause.message}",
        )
    }

    @Test
    fun `every organisation holds the three reference sequences after the V7 backfill`() {
        // The debt #36 recorded: V2 and V3 create organisations in SQL with no reference_sequence
        // rows, so the bootstrap tenants had no JOURNAL counter to lock. V7 backfills every
        // organisation that existed when it ran; a tenant provisioned by the application seeds
        // its own rows and is untouched. Organisations created by other tests after the
        // migration, through raw inserts, are legitimately outside the backfill.
        listOf(PLATFORM_ORGANISATION_ID, BOOTSTRAP_ORGANISATION_ID).forEach { organisationId ->
            val codes =
                dsl
                    .select(REFERENCE_SEQUENCE.SEQUENCE_CODE)
                    .from(REFERENCE_SEQUENCE)
                    .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(organisationId))
                    .fetch(REFERENCE_SEQUENCE.SEQUENCE_CODE)
                    .toSet()
            assertEquals(
                setOf("MEMBER", "TRANSACTION", "JOURNAL"),
                codes,
                "the SQL-created organisation $organisationId must hold exactly the seeded codes",
            )
        }
        assertTrue(
            dsl.fetchExists(
                DSL
                    .selectOne()
                    .from(ORGANISATION)
                    .where(ORGANISATION.ID.eq(BOOTSTRAP_ORGANISATION_ID)),
            ),
            "the bootstrap tenant the assertion above depends on exists",
        )
    }

    @Test
    fun `the header-versus-lines proof query returns no rows for a sound ledger`() {
        // The detector half of INV-4, fixed by the foundation document. It is a detector, not an
        // enforcer: it runs after commit and so proves the fixture's journals rather than guarding
        // a write. The engine's verification read (issue #41) is the enforcement point. Plain SQL
        // because the query is quoted verbatim from the document it must match.
        val tenant = fixture.createTenant("journal-proof")
        fixture.insertBalancedJournal(tenant)

        val drift =
            dsl.fetch(
                HEADER_VERSUS_LINES_PROOF,
                tenant.organisationId,
                PERIOD_DAY.withDayOfMonth(1),
                PERIOD_DAY.withDayOfMonth(PERIOD_DAY.lengthOfMonth()),
            )
        assertTrue(drift.isEmpty(), "a sound ledger returns zero rows, got $drift")
    }

    private fun columnsOf(table: String): Set<String> =
        dsl
            .fetch(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """.trimIndent(),
                table,
            ).map { it.get(0, String::class.java) }
            .toSet()

    private companion object {
        /** `V2`'s `PLATFORM` organisation. */
        val PLATFORM_ORGANISATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        /** `V3`'s bootstrap tenant. */
        val BOOTSTRAP_ORGANISATION_ID: UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222")

        /** The proof query as `docs/architecture/accounting-foundation.md` fixes it. */
        val HEADER_VERSUS_LINES_PROOF =
            """
            SELECT je.id, je.entry_number, je.total_debit_functional, je.total_credit_functional,
                   je.line_count, agg.debit_total, agg.credit_total, agg.lines
            FROM journal_entry je
            JOIN LATERAL (
                SELECT
                    COALESCE(SUM(jl.functional_amount)
                             FILTER (WHERE jl.direction = 'DEBIT'), 0)  AS debit_total,
                    COALESCE(SUM(jl.functional_amount)
                             FILTER (WHERE jl.direction = 'CREDIT'), 0) AS credit_total,
                    COUNT(*)                                            AS lines
                FROM journal_line jl
                WHERE jl.organisation_id = je.organisation_id
                  AND jl.journal_entry_id = je.id
            ) AS agg ON TRUE
            WHERE je.organisation_id = ?
              AND je.posting_date BETWEEN ? AND ?
              AND (je.total_debit_functional <> agg.debit_total
                OR je.total_credit_functional <> agg.credit_total
                OR je.line_count <> agg.lines
                OR agg.debit_total <> agg.credit_total)
            """.trimIndent()
    }
}
