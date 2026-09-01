package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.AdvanceBusinessDateCommand
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accounting atomicity gate (GitHub issue #29): a financial operation either commits every
 * durable effect it produces or none of them, and no effect is observable to another connection
 * before commit.
 *
 * The four failure modes the gate requires are covered here against real PostgreSQL through the
 * production Spring and jOOQ wiring: an application exception after a successful write, a
 * PostgreSQL constraint violation after earlier writes, a failure after an integration event has
 * been registered for publication, and nested `@Transactional` application services participating
 * in one transaction.
 *
 * Accounting tables do not exist yet. Rather than wait for them, the proof is expressed against
 * [FinancialTransactionAtomicityFixture], whose probes are supplied per test - so the posting
 * engine of issue #41 adds `posting_request`, `journal_entry`, `journal_line` and sub-ledger probes
 * to these same helpers without changing the harness.
 *
 * See `docs/architecture/financial-transaction-atomicity.md` and ADR 0018.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FinancialTransactionAtomicityIntegrationTests(
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val tenantSettingsService: TenantSettingsService,
    private val businessDateService: BusinessDateService,
    private val transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `an application exception rolls back the write the audit row and the outbox record`() {
        val organisationId = fixture.createActiveOrganisation("atomicity-a", ACTOR_ID)
        seedSetting(organisationId, "KES")

        val harness = settingsHarness(organisationId)
        harness.assertRollsBackAtomically(IllegalStateException::class) {
            withRequestContext { updateSetting(organisationId, "USD") }
            error("simulated failure after a successful settings write")
        }

        assertEquals(
            "KES",
            effectiveSettingValue(organisationId),
            "the previously effective row must still be the effective one: a settings update " +
                "closes the old row and inserts a replacement, so the UPDATE half rolls back too",
        )
    }

    @Test
    fun `a postgres constraint violation rolls back earlier writes in the same transaction`() {
        val organisationId = fixture.createActiveOrganisation("atomicity-b", ACTOR_ID)
        seedSetting(organisationId, "KES")

        val harness = settingsHarness(organisationId)
        // Violates uq_organisation_setting_effective (organisation_id, setting_key,
        // effective_from) by reusing the effective_from of the row written moments earlier.
        harness.assertRollsBackAtomically(org.springframework.dao.DataAccessException::class) {
            withRequestContext { updateSetting(organisationId, "USD") }
            val effectiveFrom =
                requireNotNull(
                    dsl
                        .select(ORGANISATION_SETTING.EFFECTIVE_FROM)
                        .from(ORGANISATION_SETTING)
                        .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                        .and(ORGANISATION_SETTING.SETTING_KEY.eq(SETTING_KEY))
                        .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
                        .fetchOne(ORGANISATION_SETTING.EFFECTIVE_FROM),
                )
            insertDuplicateSetting(organisationId, effectiveFrom)
        }

        assertEquals("KES", effectiveSettingValue(organisationId))
    }

    @Test
    fun `a failure after an event is registered rolls back the history audit and outbox rows`() {
        val organisationId = fixture.createActiveOrganisation("atomicity-c", ACTOR_ID)
        val currentDate = currentBusinessDate(organisationId)

        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                listOf(
                    FoundationAtomicityProbes.businessDateHistoryRows(organisationId),
                    FoundationAtomicityProbes.auditEventRows(
                        organisationId,
                        BUSINESS_DATE_ADVANCE_ACTION,
                        SUCCESS_OUTCOME,
                    ),
                    FoundationAtomicityProbes.outboxRecordRows(organisationId.toString()),
                    FoundationAtomicityProbes.eventPublicationRows(),
                ),
            )

        harness.assertRollsBackAtomically(IllegalStateException::class) {
            withRequestContext {
                businessDateService.advance(
                    AdvanceBusinessDateCommand(
                        organisationId = organisationId,
                        newBusinessDate = currentDate.plusDays(1),
                        actorId = ACTOR_ID,
                    ),
                )
            }
            error("simulated failure after history, audit and event registration")
        }

        assertEquals(
            currentDate,
            currentBusinessDate(organisationId),
            "the business date itself must be unchanged",
        )
    }

    @Test
    fun `nested transactional services roll back together while the rejection audit survives`() {
        val organisationId = createDraftOrganisation()
        withRequestContext {
            organisationProvisioningService.submitForApproval(
                SubmitOrganisationForApprovalCommand(organisationId),
            )
        }

        val transitionLogProbe =
            FoundationAtomicityProbes.organisationTransitionLogRows(organisationId)
        val outboxProbe = FoundationAtomicityProbes.outboxRecordRows(organisationId.toString())
        // The probe that gives this test teeth. submitForApproval writes the bootstrap record
        // BEFORE delegating to the transition executor, so it is the only durable effect the
        // rejected attempt can leave behind. The transition executor rejects before writing a
        // transition log or an outbox row, so those two assertions hold whether or not the outer
        // write rolled back - they compare "never written" with "never written".
        val bootstrapProbe = FoundationAtomicityProbes.bootstrapSubmissionAttempts(organisationId)
        val transitionLogsBefore = transitionLogProbe.countRows(dsl)
        val outboxBefore = outboxProbe.countRows(dsl)
        val bootstrapAttemptsBefore = bootstrapProbe.countRows(dsl)

        // submitForApproval is @Transactional and calls FoundationLifecycleService.transition,
        // itself @Transactional, so the FSM rejection propagates out of two nested proxies.
        val secondAttempt =
            runCatching {
                withRequestContext {
                    organisationProvisioningService.submitForApproval(
                        SubmitOrganisationForApprovalCommand(organisationId),
                    )
                }
            }
        assertTrue(
            secondAttempt.isFailure,
            "submitting an already-submitted organisation must fail",
        )

        assertEquals(transitionLogsBefore, transitionLogProbe.countRows(dsl))
        assertEquals(outboxBefore, outboxProbe.countRows(dsl))
        assertEquals(
            bootstrapAttemptsBefore,
            bootstrapProbe.countRows(dsl),
            "the outer bootstrap write must roll back with the nested transition rejection",
        )

        val rejectionAudits =
            FoundationAtomicityProbes
                .auditEventRows(organisationId, ORGANISATION_SUBMIT_ACTION, FAILURE_OUTCOME)
                .countRows(dsl)
        assertTrue(
            rejectionAudits >= 1L,
            "the FSM rejection audit uses REQUIRES_NEW and must SURVIVE the rollback - " +
                "this is the single documented, intentional exception to the atomicity invariant",
        )
    }

    @Test
    fun `durable effects are invisible to another connection until the transaction commits`() {
        val organisationId = fixture.createActiveOrganisation("atomicity-commit", ACTOR_ID)
        val currentDate = currentBusinessDate(organisationId)

        val historyProbe = FoundationAtomicityProbes.businessDateHistoryRows(organisationId)
        val auditProbe =
            FoundationAtomicityProbes.auditEventRows(
                organisationId,
                BUSINESS_DATE_ADVANCE_ACTION,
                SUCCESS_OUTCOME,
            )
        val outboxProbe = FoundationAtomicityProbes.outboxRecordRows(organisationId.toString())
        // The advance's PRIMARY effect is an update to the existing business_date row, not an
        // appended row. Probing only the appended effects would leave this test passing if that
        // update were committed on an independent transaction, since the appended rows would still
        // be invisible until the outer commit.
        val advancedProbe =
            FoundationAtomicityProbes.advancedBusinessDateRows(
                organisationId,
                currentDate.plusDays(1),
            )
        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                listOf(historyProbe, auditProbe, outboxProbe, advancedProbe),
            )

        harness.assertVisibleOnlyAfterCommit(
            expectedDeltas =
                mapOf(
                    historyProbe.name to 1L,
                    auditProbe.name to 1L,
                    outboxProbe.name to 1L,
                    advancedProbe.name to 1L,
                ),
        ) {
            withRequestContext {
                businessDateService.advance(
                    AdvanceBusinessDateCommand(
                        organisationId = organisationId,
                        newBusinessDate = currentDate.plusDays(1),
                        actorId = ACTOR_ID,
                    ),
                )
            }
        }
    }

    private fun settingsHarness(organisationId: UUID) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            listOf(
                FoundationAtomicityProbes.organisationSettingRows(organisationId, SETTING_KEY),
                FoundationAtomicityProbes.openOrganisationSettingRows(organisationId, SETTING_KEY),
                FoundationAtomicityProbes.auditEventRows(
                    organisationId,
                    SETTINGS_UPDATE_ACTION,
                    SUCCESS_OUTCOME,
                ),
                FoundationAtomicityProbes.outboxRecordRows(organisationId.toString()),
                FoundationAtomicityProbes.eventPublicationRows(),
            ),
        )

    private fun createDraftOrganisation(): UUID =
        organisationProvisioningService
            .createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "atomicity-d-${uuidV7()}",
                    displayName = "Atomicity Nested Organisation",
                    legalName = "Atomicity Nested Organisation Limited",
                    registrationNumber = "ATOMICITY-D-${uuidV7()}",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = ACTOR_ID,
                ),
            ).organisationId

    private fun seedSetting(
        organisationId: UUID,
        value: String,
    ) {
        withRequestContext { updateSetting(organisationId, value) }
    }

    private fun updateSetting(
        organisationId: UUID,
        value: String,
    ) {
        tenantSettingsService.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(
                organisationId = organisationId,
                key = SETTING_KEY,
                value = value,
                actorId = ACTOR_ID,
            ),
        )
    }

    private fun insertDuplicateSetting(
        organisationId: UUID,
        effectiveFrom: OffsetDateTime,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, SETTING_KEY)
            .set(ORGANISATION_SETTING.SETTING_VALUE, org.jooq.JSONB.jsonb("\"EUR\""))
            .set(ORGANISATION_SETTING.VALUE_TYPE, "CURRENCY")
            .set(ORGANISATION_SETTING.IS_SENSITIVE, false)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, effectiveFrom)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .execute()
    }

    private fun effectiveSettingValue(organisationId: UUID): String =
        requireNotNull(
            dsl
                .select(ORGANISATION_SETTING.SETTING_VALUE)
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                .and(ORGANISATION_SETTING.SETTING_KEY.eq(SETTING_KEY))
                .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
                .fetchOne(ORGANISATION_SETTING.SETTING_VALUE),
        ).data().trim('"')

    private fun currentBusinessDate(organisationId: UUID): LocalDate =
        requireNotNull(
            dsl
                .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                .from(BUSINESS_DATE)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
        )

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val SETTING_KEY = "base_currency"
        const val SETTINGS_UPDATE_ACTION = "settings.update"
        const val BUSINESS_DATE_ADVANCE_ACTION = "business_date.advance"
        const val ORGANISATION_SUBMIT_ACTION = "organisation.submit"
        const val SUCCESS_OUTCOME = "SUCCESS"
        const val FAILURE_OUTCOME = "FAILURE"
    }
}
