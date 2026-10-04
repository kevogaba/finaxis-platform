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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Full-stack proof of #206: the optional-reason bodies are validated like the required ones. A
 * reason over 500 characters is refused with a `validation_failed` 400 before anything is
 * written, so there is no state change, no transition row and no audit row; and the platform
 * tenant route no longer reaches `organisation.status_reason VARCHAR(1000)` as a 500.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OptionalReasonValidationIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser("maker")
        private val organisationId = fixture.createActiveOrganisation("optional-reason", maker)

        @Test
        fun `submitting a branch with a reason over 500 characters changes nothing`() {
            val branchId = createBranch()
            val auditBefore = auditCount(branchId)

            submit(branchId, reasonBody("x".repeat(501))).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }

            assertEquals("DRAFT", branchColumn(branchId, "status"))
            assertNull(branchColumn(branchId, "status_reason"))
            assertEquals(0, transitionCount(branchId, "SUBMIT"))
            assertEquals(auditBefore, auditCount(branchId))
        }

        @Test
        fun `submitting a branch with exactly 500 characters stores and audits the full reason`() {
            val branchId = createBranch()
            val reason = "x".repeat(500)

            submit(branchId, reasonBody(reason)).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
                jsonPath("$.status_reason") { value(reason) }
            }

            assertEquals(reason, branchColumn(branchId, "status_reason"))
            assertEquals(1, transitionCount(branchId, "SUBMIT"))
        }

        @Test
        fun `submitting a branch with a blank reason or no body records no reason`() {
            listOf(reasonBody("   "), null, "{}").forEach { body ->
                val branchId = createBranch()

                submit(branchId, body).andExpect { status { isOk() } }

                assertEquals("PENDING_APPROVAL", branchColumn(branchId, "status"))
                assertNull(branchColumn(branchId, "status_reason"))
            }
        }

        @Test
        fun `reactivating a tenant with a reason over 1000 characters is a 400 not a 500`() {
            val platformAdmin = seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
            setTenantStatus("SUSPENDED")

            listOf(501, 1001).forEach { length ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$organisationId/reactivate") {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content = reasonBody("x".repeat(length))
                        with(authentication(platformToken(platformAdmin)))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }

            val status =
                dsl
                    .fetchOne("SELECT status FROM organisation WHERE id = ?", organisationId)!!
                    .get(0, String::class.java)
            assertEquals("SUSPENDED", status)
        }

        private fun reasonBody(reason: String) =
            apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason))

        private fun createBranch(): UUID {
            val body =
                post(
                    ApiPaths.BRANCHES,
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateBranchRequest(
                            branchCode = "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                            branchName = "Riverside Branch",
                            branchType = "OPERATIONAL",
                            timezone = "Africa/Nairobi",
                        ),
                    ),
                ).andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )
        }

        private fun submit(
            branchId: UUID,
            body: String?,
        ): ResultActionsDsl = post("${ApiPaths.BRANCHES}/$branchId/submit", body)

        private fun post(
            path: String,
            body: String?,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(authentication(makerToken()))
            }

        private fun branchColumn(
            branchId: UUID,
            column: String,
        ): String? =
            dsl
                .fetchOne("SELECT $column::text FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun transitionCount(
            branchId: UUID,
            transition: String,
        ): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM branch_transition_log " +
                        "WHERE branch_id = ? AND transition_name = ?",
                    branchId,
                    transition,
                )!!
                .get(0, Int::class.java)

        private fun auditCount(branchId: UUID): Int =
            dsl
                .fetchOne("SELECT COUNT(*) FROM audit_event WHERE entity_id = ?", branchId)!!
                .get(0, Int::class.java)

        private fun setTenantStatus(status: String) {
            dsl.execute("UPDATE organisation SET status = ? WHERE id = ?", status, organisationId)
        }

        private fun makerToken() = token(maker, organisationId, setOf("branch.create"))

        private fun platformToken(userId: UUID) =
            token(userId, PlatformOrganisation.ID, setOf("tenant.reactivate"))

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@optional-reason.test",
                fullName = "Optional Reason User",
                permissions = permissions,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@optional-reason.test")
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
