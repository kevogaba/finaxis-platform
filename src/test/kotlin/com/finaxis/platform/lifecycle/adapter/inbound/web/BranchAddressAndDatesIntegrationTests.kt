package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Full-stack check that a branch's address round-trips through create and both detail reads, and
 * that opening and closing stamp the tenant business date (never the wall clock) on `opened_on`
 * and `closed_on` (issue #165).
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchAddressAndDatesIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `a branch address round-trips and the business date stamps opening and closing`() {
            val maker = seedUser("maker")
            val checker = seedUser("checker")
            val platformAdmin = seedUser("platform")
            val organisationId = fixture.createActiveOrganisation("branch-api", maker)
            fixture.grantTenantAdmin(organisationId, checker)
            fixture.grantPlatformSuperAdmin(platformAdmin)

            // The head office was activated by tenant approval, on the business date it created.
            val approvalDate = businessDate(organisationId)
            assertEquals(approvalDate, headOfficeOpenedOn(organisationId))

            val opening = LocalDate.of(2031, 3, 9)
            assertNotEquals(LocalDate.now(), opening)
            setBusinessDate(organisationId, opening)

            val address = mapOf("line1" to "12 Riverside Drive", "city" to "Nairobi")
            val branchId = createBranch(organisationId, maker, address)

            assertTenantGet(organisationId, branchId, maker, "DRAFT", opened = null, closed = null)
            assertPlatformGet(organisationId, branchId, platformAdmin)

            post(
                "${ApiPaths.BRANCHES}/$branchId/submit",
                tenantToken(maker, organisationId, "branch.create"),
            )
            post(
                "${ApiPaths.BRANCHES}/$branchId/activate",
                tenantToken(checker, organisationId, "branch.activate"),
            )
            assertTenantGet(organisationId, branchId, maker, "ACTIVE", "09-03-2031", null)

            setBusinessDate(organisationId, LocalDate.of(2031, 4, 21))
            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/close") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Branch relocated"}"""
                    with(authentication(tenantToken(checker, organisationId, "branch.close")))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("CLOSED") }
                    jsonPath("$.opened_on") { value("09-03-2031") }
                    jsonPath("$.closed_on") { value("21-04-2031") }
                }
            assertTenantGet(organisationId, branchId, maker, "CLOSED", "09-03-2031", "21-04-2031")
        }

        @Test
        fun `a free-form address key that looks sensitive does not break replayable calls`() {
            val maker = seedUser("maker")
            val checker = seedUser("checker")
            val organisationId = fixture.createActiveOrganisation("branch-api-pin", maker)
            fixture.grantTenantAdmin(organisationId, checker)

            val branchId = createBranch(organisationId, maker, mapOf("pin_code" to "560001"))

            val submitted =
                post(
                    "${ApiPaths.BRANCHES}/$branchId/submit",
                    tenantToken(maker, organisationId, "branch.create"),
                )
            val activated =
                post(
                    "${ApiPaths.BRANCHES}/$branchId/activate",
                    tenantToken(checker, organisationId, "branch.activate"),
                )

            listOf(submitted, activated).forEach { body ->
                assertEquals(
                    "560001",
                    apiJsonCodec.mapper
                        .readTree(body)
                        .at("/address/pin_code")
                        .asString(),
                )
            }
            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(tenantToken(maker, organisationId, "branch.view")))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("ACTIVE") }
                    jsonPath("$.address.pin_code") { value("560001") }
                }
        }

        private fun createBranch(
            organisationId: UUID,
            maker: UUID,
            address: Map<String, String>,
        ): UUID {
            val body =
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                CreateBranchRequest(
                                    branchCode = "BR-${uuidV7().toString().takeLast(
                                        8,
                                    ).uppercase()}",
                                    branchName = "Riverside Branch",
                                    branchType = "OPERATIONAL",
                                    timezone = "Africa/Nairobi",
                                    address = address,
                                ),
                            )
                        with(authentication(tenantToken(maker, organisationId, "branch.create")))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )
        }

        private fun assertTenantGet(
            organisationId: UUID,
            branchId: UUID,
            viewer: UUID,
            status: String,
            opened: String?,
            closed: String?,
        ) {
            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(tenantToken(viewer, organisationId, "branch.view")))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value(status) }
                    jsonPath("$.address.line1") { value("12 Riverside Drive") }
                    jsonPath("$.address.city") { value("Nairobi") }
                    jsonPath("$.opened_on") { value(opened) }
                    jsonPath("$.closed_on") { value(closed) }
                }
        }

        private fun assertPlatformGet(
            organisationId: UUID,
            branchId: UUID,
            platformAdmin: UUID,
        ) {
            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches/$branchId") {
                    with(authentication(platformToken(platformAdmin)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.address.line1") { value("12 Riverside Drive") }
                    jsonPath("$.address.city") { value("Nairobi") }
                }
        }

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
        ): String =
            mockMvc
                .post(path) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(authentication(token))
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        private fun businessDate(organisationId: UUID): LocalDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            )

        private fun setBusinessDate(
            organisationId: UUID,
            date: LocalDate,
        ) {
            dsl
                .update(BUSINESS_DATE)
                .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, date)
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                .execute()
        }

        private fun headOfficeOpenedOn(organisationId: UUID): LocalDate? =
            dsl
                .select(BRANCH.OPENED_ON)
                .from(BRANCH)
                .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH.BRANCH_TYPE.eq("HEAD_OFFICE"))
                .fetchOne(BRANCH.OPENED_ON)

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-api.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun platformToken(userId: UUID) =
            principal(userId, PlatformOrganisation.ID, "branch.view")

        private fun tenantToken(
            userId: UUID,
            tenantId: UUID,
            permission: String,
        ) = principal(userId, tenantId, permission)

        private fun principal(
            userId: UUID,
            organisationId: UUID,
            permission: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "user@branch-api.test",
                fullName = "Branch API User",
                permissions = setOf(permission),
            ),
        )
    }
