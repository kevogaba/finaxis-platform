package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof of the close-of-business readiness read (#168): `GET
 * /api/v1/tenant/business-date/cob/readiness` reports BLOCKING and ADVISORY checks over the
 * lifecycle row state and the accounting snapshot, gated by `cob.start` (with the
 * `business_date.view` the catalogue pairs with it), and changes nothing: `cob/start` and
 * `cob/complete` keep their behaviour with no new refusal.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("TooManyFunctions")
class CobReadinessIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val journalFixture = JournalSchemaFixture(dsl)
        private val admin = seedUser("admin")

        @Test
        fun `a tenant with no accounting data is ready with every check passing`() {
            val organisationId = fixture.createActiveOrganisation("cob-clean", admin)

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
                jsonPath("$.status") { value("OPEN") }
                jsonPath("$.business_date") { exists() }
                jsonPath("$.checks.length()") { value(5) }
                CHECK_CODES.forEach { code -> checkStatus(code, "PASS") }
                // Severity is fixed per check, and a passing check carries no count.
                jsonPath("$.checks[?(@.code=='business_date_open')].severity") {
                    value("BLOCKING")
                }
                jsonPath("$.checks[?(@.code=='organisation_active')].severity") {
                    value("BLOCKING")
                }
                jsonPath("$.checks[?(@.code=='manual_journals_pending_approval')].severity") {
                    value("BLOCKING")
                }
                jsonPath("$.checks[?(@.code=='manual_journals_draft')].severity") {
                    value("ADVISORY")
                }
                jsonPath("$.checks[?(@.code=='reconciliation_breaks')].severity") {
                    value("ADVISORY")
                }
            }
        }

        @Test
        fun `a pending approval manual journal blocks and carries its count`() {
            val organisationId = fixture.createActiveOrganisation("cob-pending", admin)
            insertManualJournal(organisationId, "PENDING_APPROVAL")
            insertManualJournal(organisationId, "PENDING_APPROVAL")
            insertManualJournal(organisationId, "CANCELLED")

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(false) }
                checkStatus("manual_journals_pending_approval", "FAIL")
                jsonPath("$.checks[?(@.code=='manual_journals_pending_approval')].count") {
                    value(2)
                }
                checkStatus("manual_journals_draft", "PASS")
                checkStatus("reconciliation_breaks", "PASS")
                checkStatus("business_date_open", "PASS")
            }
        }

        @Test
        fun `a draft manual journal only warns and the tenant stays ready`() {
            val organisationId = fixture.createActiveOrganisation("cob-draft", admin)
            insertManualJournal(organisationId, "DRAFT")

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
                checkStatus("manual_journals_draft", "WARN")
                jsonPath("$.checks[?(@.code=='manual_journals_draft')].count") { value(1) }
                checkStatus("manual_journals_pending_approval", "PASS")
            }
        }

        @Test
        fun `only the latest run per control account counts as a break`() {
            val organisationId = fixture.createActiveOrganisation("cob-breaks", admin)
            val superseded =
                journalFixture.insertControlAccount(
                    organisationId,
                    "2100",
                    "LIABILITY",
                    ControlSubledgerKind.SAVINGS_DEPOSITS,
                )
            val stillBroken =
                journalFixture.insertControlAccount(
                    organisationId,
                    "2200",
                    "LIABILITY",
                    ControlSubledgerKind.SHARE_CAPITAL,
                )
            // 2100: BREAK on the 1st, MATCHED on the 2nd. The old BREAK is superseded.
            insertRun(organisationId, superseded, LocalDate.of(2026, 8, 1), "BREAK")
            insertRun(organisationId, superseded, LocalDate.of(2026, 8, 2), "MATCHED")
            // 2200: MATCHED on the 1st, BREAK on the 2nd. The newest run is the break.
            insertRun(organisationId, stillBroken, LocalDate.of(2026, 8, 1), "MATCHED")
            insertRun(organisationId, stillBroken, LocalDate.of(2026, 8, 2), "BREAK")

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
                checkStatus("reconciliation_breaks", "WARN")
                jsonPath("$.checks[?(@.code=='reconciliation_breaks')].count") { value(1) }
            }
        }

        @Test
        fun `a superseded break alone leaves the check passing`() {
            val organisationId = fixture.createActiveOrganisation("cob-superseded", admin)
            val account =
                journalFixture.insertControlAccount(
                    organisationId,
                    "2100",
                    "LIABILITY",
                    ControlSubledgerKind.SAVINGS_DEPOSITS,
                )
            insertRun(organisationId, account, LocalDate.of(2026, 8, 1), "BREAK")
            insertRun(organisationId, account, LocalDate.of(2026, 8, 2), "MATCHED")

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                checkStatus("reconciliation_breaks", "PASS")
            }
        }

        @Test
        fun `another tenant's pending journals and breaks are never counted`() {
            val organisationId = fixture.createActiveOrganisation("cob-mine", admin)
            val other = fixture.createActiveOrganisation("cob-other", seedUser("other-admin"))
            insertManualJournal(other, "PENDING_APPROVAL")
            insertManualJournal(other, "DRAFT")

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
                checkStatus("manual_journals_pending_approval", "PASS")
                checkStatus("manual_journals_draft", "PASS")
            }
        }

        @Test
        fun `a business date that is not open blocks`() {
            val organisationId = fixture.createActiveOrganisation("cob-closing", admin)
            post(
                "${ApiPaths.BUSINESS_DATE}/cob/start",
                token(admin, organisationId, "cob.start"),
            ).andExpect { status { isOk() } }

            readiness(organisationId, admin).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(false) }
                jsonPath("$.status") { value("CLOSING") }
                checkStatus("business_date_open", "FAIL")
                checkStatus("organisation_active", "PASS")
            }
        }

        @Test
        fun `a caller without cob start is refused by name and a holder with its views reads`() {
            val organisationId = fixture.createActiveOrganisation("cob-perm", admin)
            val viewOnly = seedUser("view-only")
            fixture.grantTenantPermissionsExactly(organisationId, viewOnly, "business_date.view")
            val noView = seedUser("start-only")
            fixture.grantTenantPermissionsExactly(organisationId, noView, "cob.start")
            val holder = seedUser("holder")
            fixture.grantTenantPermissionsWithViews(organisationId, holder, "cob.start")

            // The token carries the coarse authority, so the service check names the missing code.
            readiness(organisationId, viewOnly).andExpect { forbiddenNaming("cob.start") }
            readiness(organisationId, noView).andExpect { forbiddenNaming("business_date.view") }
            readiness(organisationId, holder).andExpect {
                status { isOk() }
                jsonPath("$.ready") { value(true) }
            }
        }

        @Test
        fun `cob start and complete are unchanged by pending work`() {
            val organisationId = fixture.createActiveOrganisation("cob-advisory", admin)
            insertManualJournal(organisationId, "PENDING_APPROVAL")
            readiness(organisationId, admin).andExpect { jsonPath("$.ready") { value(false) } }

            // The readiness is advisory: a not-ready tenant still starts and completes its COB.
            post("${ApiPaths.BUSINESS_DATE}/cob/start", token(admin, organisationId, "cob.start"))
                .andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("CLOSING") }
                }
            post(
                "${ApiPaths.BUSINESS_DATE}/cob/complete",
                token(admin, organisationId, "cob.complete"),
            ).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CLOSED") }
            }
        }

        private fun MockMvcResultMatchersDsl.checkStatus(
            code: String,
            status: String,
        ) {
            jsonPath("$.checks[?(@.code=='$code')].status") { value(status) }
        }

        private fun MockMvcResultMatchersDsl.forbiddenNaming(code: String) {
            status { isForbidden() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.code") { value("forbidden") }
            jsonPath("$.detail") { value("Missing permission: $code.") }
        }

        private fun readiness(
            organisationId: UUID,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.get("${ApiPaths.BUSINESS_DATE}/cob/readiness") {
                with(authentication(token(actor, organisationId, "cob.start")))
            }

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
                with(authentication(token))
            }

        private fun insertManualJournal(
            organisationId: UUID,
            status: String,
        ) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(MANUAL_JOURNAL)
                .set(MANUAL_JOURNAL.ORGANISATION_ID, organisationId)
                .set(MANUAL_JOURNAL.TITLE, "Adjustment")
                .set(MANUAL_JOURNAL.NARRATIVE, "Readiness test")
                .set(MANUAL_JOURNAL.STATUS, status)
                .set(MANUAL_JOURNAL.CREATED_AT, now)
                .set(MANUAL_JOURNAL.UPDATED_AT, now)
                .execute()
        }

        private fun insertRun(
            organisationId: UUID,
            accountId: UUID,
            asOf: LocalDate,
            status: String,
        ) {
            val broken = status == "BREAK"
            val now = OffsetDateTime.now()
            dsl
                .insertInto(CONTROL_ACCOUNT_RECONCILIATION_RUN)
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID, organisationId)
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_ACCOUNT_ID, accountId)
                .set(
                    CONTROL_ACCOUNT_RECONCILIATION_RUN.CONTROL_SUBLEDGER_KIND,
                    kindOf(accountId),
                ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.AS_OF_DATE, asOf)
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CURRENCY_CODE, "KES")
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_BALANCE, BigDecimal("-100"))
                .set(
                    CONTROL_ACCOUNT_RECONCILIATION_RUN.SUBLEDGER_BALANCE,
                    BigDecimal(if (broken) "-90" else "-100"),
                ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, status)
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.PROVIDER, "test")
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CREATED_AT, now)
                .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_AT, now)
                .execute()
        }

        private fun kindOf(accountId: UUID): String =
            dsl
                .fetchOne("SELECT control_subledger_kind FROM gl_account WHERE id = ?", accountId)!!
                .get(0, String::class.java)

        private fun token(
            userId: UUID,
            organisationId: UUID,
            permission: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@cob-readiness.test",
                fullName = "Cob Readiness User",
                permissions = setOf(permission),
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@cob-readiness.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            val CHECK_CODES =
                listOf(
                    "business_date_open",
                    "organisation_active",
                    "manual_journals_pending_approval",
                    "manual_journals_draft",
                    "reconciliation_breaks",
                )
        }
    }
