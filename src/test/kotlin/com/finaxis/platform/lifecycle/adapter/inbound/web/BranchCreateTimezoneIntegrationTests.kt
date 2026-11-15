package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.domain.TimeZoneRule
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof of a branch's time zone on create (#276): a zone the request names is checked
 * against the one rule the organisation and `default_timezone` share, and a request that names none
 * takes the tenant's `default_timezone`, or else the organisation's own zone, on both the tenant
 * and the platform route.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchCreateTimezoneIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("zone-admin")
        private val organisationId = fixture.createActiveOrganisation("branch-zone", admin)

        @Test
        fun `a branch with no zone takes the default setting, else the organisation's`() {
            val organisationZone = organisationTimezone()

            val unset = createdBranchId(createTenantBranch(timezone = null))
            assertEquals(organisationZone, storedTimezone(unset))

            putDefaultTimezone("Africa/Kampala")
            val defaulted = createdBranchId(createTenantBranch(timezone = null))
            assertEquals("Africa/Kampala", storedTimezone(defaulted))

            val named = createdBranchId(createTenantBranch(timezone = "Europe/London"))
            assertEquals("Europe/London", storedTimezone(named))
        }

        @Test
        fun `a branch create naming an invalid or offset zone is refused and writes nothing`() {
            val before = branchCount()

            listOf("Mars/Olympus", "+03:00", "UTC+3").forEach { zone ->
                createTenantBranch(timezone = zone).andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.detail") { value(TimeZoneRule.SAFE_DETAIL) }
                }
            }
            createTenantBranch(timezone = " ").andExpect { status { isBadRequest() } }

            assertEquals(before, branchCount())
        }

        @Test
        fun `the platform route applies the same zone rule and default`() {
            val operator = fixture.createPlatformOperator("zone-operator")

            createPlatformBranch(operator, timezone = "+03:00").andExpect {
                status { isUnprocessableContent() }
            }

            val defaulted = createdBranchId(createPlatformBranch(operator, timezone = null))
            assertEquals(organisationTimezone(), storedTimezone(defaulted))
        }

        private fun createTenantBranch(timezone: String?): ResultActionsDsl =
            mockMvc.post(ApiPaths.BRANCHES) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = branchBody(timezone)
                with(authentication(token(admin, organisationId, "branch.create")))
            }

        private fun createPlatformBranch(
            operator: UUID,
            timezone: String?,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = branchBody(timezone)
                with(authentication(token(operator, PlatformOrganisation.ID, "branch.create")))
            }

        private fun branchBody(timezone: String?): String {
            val body =
                mutableMapOf<String, Any>(
                    "branch_code" to "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                    "branch_name" to "Zone Branch",
                    "branch_type" to "OPERATIONAL",
                )
            timezone?.let { body["timezone"] = it }
            return apiJsonCodec.mapper.writeValueAsString(body)
        }

        private fun putDefaultTimezone(zone: String) {
            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"value":"$zone"}"""
                    with(authentication(token(admin, organisationId, "settings.update")))
                }.andExpect { status { isOk() } }
        }

        private fun createdBranchId(result: ResultActionsDsl): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(
                        result
                            .andExpect { status { isCreated() } }
                            .andReturn()
                            .response.contentAsString,
                    ).get("branch_id")
                    .asString(),
            )

        private fun storedTimezone(branchId: UUID): String? =
            dsl
                .select(BRANCH.TIMEZONE)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(BRANCH.TIMEZONE)

        private fun organisationTimezone(): String =
            requireNotNull(
                dsl
                    .select(ORGANISATION.TIMEZONE)
                    .from(ORGANISATION)
                    .where(ORGANISATION.ID.eq(organisationId))
                    .fetchOne(ORGANISATION.TIMEZONE),
            )

        private fun branchCount(): Int =
            dsl.fetchCount(BRANCH, BRANCH.ORGANISATION_ID.eq(organisationId))

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permission: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@branch-zone.test",
                fullName = "Branch Zone User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-zone.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }
    }
