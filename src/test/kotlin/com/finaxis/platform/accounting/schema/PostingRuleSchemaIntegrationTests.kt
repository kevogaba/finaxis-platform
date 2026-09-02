package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.POSTING_RULE
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_LEG
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * What PostgreSQL enforces about posting rules, proved against PostgreSQL.
 *
 * Every rule here is stated in `docs/database/accounting-erd.md` under *"Column definitions for
 * the issue #44 tables"*. Each rejection asserts the **named** constraint.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleSchemaIntegrationTests(
    private val dsl: DSLContext,
) {
    private val journals = JournalSchemaFixture(dsl)

    @Test
    fun `a rule with an approved two-leg version is accepted`() {
        // The positive control every rejection below stands beside.
        val tenant = journals.createTenant("rule-positive")
        val ruleId = insertRule(tenant.organisationId, "SAVINGS-DEPOSIT")
        val versionId = insertVersion(tenant.organisationId, ruleId, 1, "ACTIVE", FROM)
        insertLeg(tenant, versionId, 1, "DEBIT", tenant.debitAccountId)
        insertLeg(tenant, versionId, 2, "CREDIT", tenant.creditAccountId)

        assertEquals(
            2,
            dsl.fetchCount(
                POSTING_RULE_LEG,
                POSTING_RULE_LEG.POSTING_RULE_VERSION_ID.eq(versionId),
            ),
        )
    }

    @Test
    fun `selectors are unique per tenant with null treated as a value`() {
        val tenant = journals.createTenant("rule-selector")
        val other = journals.createTenant("rule-selector-other")
        insertRule(tenant.organisationId, "R1", event = "SAVINGS_DEPOSIT")

        assertViolates("uq_posting_rule_selector") {
            insertRule(tenant.organisationId, "R2", event = "SAVINGS_DEPOSIT")
        }
        // A different product class or currency is a different selector; another tenant is free.
        insertRule(
            tenant.organisationId,
            "R3",
            event = "SAVINGS_DEPOSIT",
            productClass = "SAVINGS:REGULAR",
        )
        insertRule(tenant.organisationId, "R4", event = "SAVINGS_DEPOSIT", currency = "KES")
        insertRule(other.organisationId, "R1", event = "SAVINGS_DEPOSIT")

        assertViolates("uq_posting_rule_organisation_code") {
            insertRule(tenant.organisationId, "R1", event = "LOAN_DISBURSEMENT")
        }
        assertViolates("chk_posting_rule_event_code") {
            insertRule(tenant.organisationId, "R5", event = "savings deposit")
        }
    }

    @Test
    fun `approved versions of one rule never overlap, and drafts may`() {
        val tenant = journals.createTenant("rule-overlap")
        val ruleId = insertRule(tenant.organisationId, "R")
        insertVersion(tenant.organisationId, ruleId, 1, "SUPERSEDED", FROM, FROM.plusMonths(6))
        insertVersion(tenant.organisationId, ruleId, 2, "ACTIVE", FROM.plusMonths(6).plusDays(1))

        // A third approved version inside either window, or open-ended over the open-ended one.
        assertViolates("ex_posting_rule_version_no_overlap") {
            insertVersion(tenant.organisationId, ruleId, 3, "ACTIVE", FROM.plusMonths(3))
        }
        assertViolates("ex_posting_rule_version_no_overlap") {
            insertVersion(
                tenant.organisationId,
                ruleId,
                3,
                "RETIRED",
                FROM.plusYears(2),
                FROM.plusYears(3),
            )
        }
        // A draft or proposal overlapping an approved version is how a successor is prepared.
        insertVersion(tenant.organisationId, ruleId, 3, "DRAFT", FROM.plusMonths(8))
        insertVersion(tenant.organisationId, ruleId, 4, "PENDING_APPROVAL", FROM.plusMonths(9))
        // Another rule's version on the same dates is unrelated.
        val otherRule = insertRule(tenant.organisationId, "R-OTHER", event = "LOAN_DISBURSEMENT")
        insertVersion(tenant.organisationId, otherRule, 1, "ACTIVE", FROM)
    }

    @Test
    fun `a version's shape is bounded`() {
        val tenant = journals.createTenant("rule-version-shape")
        val ruleId = insertRule(tenant.organisationId, "R")

        assertViolates("chk_posting_rule_version_effective") {
            insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM, FROM.minusDays(1))
        }
        assertViolates("chk_posting_rule_version_closed_when_ended") {
            insertVersion(tenant.organisationId, ruleId, 1, "RETIRED", FROM, null)
        }
        assertViolates("chk_posting_rule_version_status") {
            insertVersion(tenant.organisationId, ruleId, 1, "REJECTED", FROM)
        }
        assertViolates("chk_posting_rule_version_number") {
            insertVersion(tenant.organisationId, ruleId, 0, "DRAFT", FROM)
        }
        insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM)
        assertViolates("uq_posting_rule_version_number") {
            insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM.plusYears(1))
        }
    }

    @Test
    fun `legs are tenant-safe and ordered`() {
        val tenant = journals.createTenant("rule-legs")
        val other = journals.createTenant("rule-legs-other")
        val ruleId = insertRule(tenant.organisationId, "R")
        val versionId = insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM)
        val otherRule = insertRule(other.organisationId, "R")
        val otherVersion = insertVersion(other.organisationId, otherRule, 1, "DRAFT", FROM)
        insertLeg(tenant, versionId, 1, "DEBIT", tenant.debitAccountId)

        assertViolates("fk_posting_rule_leg_account") {
            insertLeg(tenant, versionId, 2, "CREDIT", other.creditAccountId)
        }
        assertViolates("fk_posting_rule_leg_version") {
            insertLeg(tenant, otherVersion, 2, "CREDIT", tenant.creditAccountId)
        }
        assertViolates("uq_posting_rule_leg_number") {
            insertLeg(tenant, versionId, 1, "CREDIT", tenant.creditAccountId)
        }
    }

    @Test
    fun `legs are constrained to one admitted strategy, a direction, a share and a fact code`() {
        val tenant = journals.createTenant("rule-leg-checks")
        val ruleId = insertRule(tenant.organisationId, "R")
        val versionId = insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM)
        insertLeg(tenant, versionId, 1, "DEBIT", tenant.debitAccountId)

        assertViolates("chk_posting_rule_leg_resolution") {
            insertLeg(
                tenant,
                versionId,
                2,
                "CREDIT",
                tenant.creditAccountId,
                resolution = "PRODUCT_PARAMETER",
            )
        }
        assertViolates("chk_posting_rule_leg_direction") {
            insertLeg(tenant, versionId, 2, "CR", tenant.creditAccountId)
        }
        assertViolates("chk_posting_rule_leg_percentage") {
            insertLeg(
                tenant,
                versionId,
                2,
                "CREDIT",
                tenant.creditAccountId,
                percentage = BigDecimal.ZERO,
            )
        }
        assertViolates("chk_posting_rule_leg_percentage") {
            insertLeg(
                tenant,
                versionId,
                2,
                "CREDIT",
                tenant.creditAccountId,
                percentage = BigDecimal("100.000001"),
            )
        }
        assertViolates("chk_posting_rule_leg_amount_source") {
            insertLeg(
                tenant,
                versionId,
                2,
                "CREDIT",
                tenant.creditAccountId,
                amountSource = "principal",
            )
        }
    }

    @Test
    fun `at most one residual leg per fact per side`() {
        val tenant = journals.createTenant("rule-residual")
        val ruleId = insertRule(tenant.organisationId, "R")
        val versionId = insertVersion(tenant.organisationId, ruleId, 1, "DRAFT", FROM)
        insertLeg(tenant, versionId, 1, "DEBIT", tenant.debitAccountId, residual = true)

        // The same fact split across both sides carries a residual on each: the rounding remainder
        // of the debits is not the rounding remainder of the credits.
        insertLeg(tenant, versionId, 2, "CREDIT", tenant.creditAccountId, residual = true)

        assertViolates("uq_posting_rule_leg_residual") {
            insertLeg(tenant, versionId, 3, "DEBIT", tenant.debitAccountId, residual = true)
        }

        // A residual for a different fact is fine.
        insertLeg(
            tenant,
            versionId,
            3,
            "CREDIT",
            tenant.creditAccountId,
            amountSource = "FEE",
            residual = true,
        )
    }

    @Test
    fun `a posting request cannot name another tenant's rule version`() {
        val tenant = journals.createTenant("rule-request-a")
        val other = journals.createTenant("rule-request-b")
        val ruleId = insertRule(other.organisationId, "R")
        val otherVersion = insertVersion(other.organisationId, ruleId, 1, "ACTIVE", FROM)
        val requestId = journals.insertPostingRequest(tenant.organisationId)

        assertViolates("fk_posting_request_rule_version") {
            dsl
                .update(POSTING_REQUEST)
                .set(POSTING_REQUEST.POSTING_RULE_VERSION_ID, otherVersion)
                .where(POSTING_REQUEST.ID.eq(requestId))
                .execute()
        }
    }

    // ---- fixture -----------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun insertRule(
        organisationId: UUID,
        code: String,
        event: String = "SAVINGS_DEPOSIT",
        productClass: String? = null,
        currency: String? = null,
    ): UUID =
        dsl
            .insertInto(POSTING_RULE)
            .set(POSTING_RULE.ORGANISATION_ID, organisationId)
            .set(POSTING_RULE.RULE_CODE, code)
            .set(POSTING_RULE.RULE_NAME, "Rule $code")
            .set(POSTING_RULE.EVENT_CODE, event)
            .set(POSTING_RULE.PRODUCT_CLASS, productClass)
            .set(POSTING_RULE.CURRENCY_CODE, currency)
            .set(POSTING_RULE.CREATED_AT, now())
            .set(POSTING_RULE.UPDATED_AT, now())
            .returning(POSTING_RULE.ID)
            .fetchOne()!!
            .id!!

    @Suppress("LongParameterList")
    private fun insertVersion(
        organisationId: UUID,
        ruleId: UUID,
        number: Int,
        status: String,
        from: LocalDate,
        to: LocalDate? = null,
    ): UUID =
        dsl
            .insertInto(POSTING_RULE_VERSION)
            .set(POSTING_RULE_VERSION.ORGANISATION_ID, organisationId)
            .set(POSTING_RULE_VERSION.POSTING_RULE_ID, ruleId)
            .set(POSTING_RULE_VERSION.VERSION_NUMBER, number)
            .set(POSTING_RULE_VERSION.STATUS, status)
            .set(POSTING_RULE_VERSION.EFFECTIVE_FROM, from)
            .set(POSTING_RULE_VERSION.EFFECTIVE_TO, to)
            .set(POSTING_RULE_VERSION.CREATED_AT, now())
            .set(POSTING_RULE_VERSION.UPDATED_AT, now())
            .returning(POSTING_RULE_VERSION.ID)
            .fetchOne()!!
            .id!!

    @Suppress("LongParameterList")
    private fun insertLeg(
        tenant: JournalSchemaFixture.Tenant,
        versionId: UUID,
        number: Int,
        direction: String,
        accountId: UUID,
        resolution: String = "FIXED_ACCOUNT",
        amountSource: String = "PRINCIPAL",
        percentage: BigDecimal = BigDecimal("100"),
        residual: Boolean = false,
    ): UUID =
        dsl
            .insertInto(POSTING_RULE_LEG)
            .set(POSTING_RULE_LEG.ORGANISATION_ID, tenant.organisationId)
            .set(POSTING_RULE_LEG.POSTING_RULE_VERSION_ID, versionId)
            .set(POSTING_RULE_LEG.LEG_NUMBER, number)
            .set(POSTING_RULE_LEG.DIRECTION, direction)
            .set(POSTING_RULE_LEG.ACCOUNT_RESOLUTION, resolution)
            .set(POSTING_RULE_LEG.GL_ACCOUNT_ID, accountId)
            .set(POSTING_RULE_LEG.AMOUNT_SOURCE, amountSource)
            .set(POSTING_RULE_LEG.AMOUNT_PERCENTAGE, percentage)
            .set(POSTING_RULE_LEG.IS_RESIDUAL, residual)
            .set(POSTING_RULE_LEG.CREATED_AT, now())
            .set(POSTING_RULE_LEG.UPDATED_AT, now())
            .returning(POSTING_RULE_LEG.ID)
            .fetchOne()!!
            .id!!

    private fun now(): OffsetDateTime = OffsetDateTime.now()

    private companion object {
        val FROM: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
