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
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-stack proof that the branch maker-checker refusals are audited (#251, the branch mirror of
 * the tenant rule's #245 audit): on the real tenant `/branches/{id}/activate` and platform
 * `/platform/tenants/{tenant_id}/branches/{id}/activate` routes, the creator, the platform
 * submitter and anyone with a successful `branch.update` on the branch are refused with their
 * unchanged `403`, nothing changes, and each refusal leaves one committed `DENIED`, `HIGH`
 * `branch.activate` audit row (reason the response code) that the request's rollback did not take.
 * A checker who is none of these approves and leaves no `DENIED` row.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchCheckerRuleIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val maker = seedUser("maker")
        private val checker = seedUser("checker")
        private val amender = seedUser("amender")
        private val organisationId = fixture.createActiveOrganisation("branch-checker", maker)

        init {
            fixture.grantTenantAdmin(organisationId, checker)
            fixture.grantTenantAdmin(organisationId, amender)
        }

        private fun platformAdmin() = seedUser("padmin").also(fixture::grantPlatformSuperAdmin)

        @Test
        fun `the tenant creator and an amender are refused and each refusal is audited`() {
            val branchId = createBranch()
            amend(branchId, amender).andExpect { status { isOk() } }
            submit(branchId, maker)

            // Both callers are pinned to another active branch, the head office: the row must name
            // the branch they tried to approve, not the one their request context carries.
            val pinned = headOfficeId()
            activate(branchId, maker, pinned).andExpect { refusedAs(FORBIDDEN, FORBIDDEN_DETAIL) }
            activate(branchId, amender, pinned).andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }

            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
            assertEquals(0, activations(branchId))
            assertEquals(
                listOf(maker to FORBIDDEN, amender to MODIFIER),
                deniedRows(branchId),
            )
            assertEquals(listOf(branchId, branchId), deniedBranches(branchId))

            activate(branchId, checker).andExpect { status { isOk() } }
            assertEquals("ACTIVE", branchStatus(branchId))
            // The accepted checker added no DENIED row.
            assertEquals(2, deniedRows(branchId).size)
        }

        @Test
        fun `a tenant submitter who neither created nor amended approves with no denied row`() {
            val branchId = createBranch()
            submit(branchId, checker)

            activate(branchId, checker).andExpect { status { isOk() } }

            assertEquals("ACTIVE", branchStatus(branchId))
            assertTrue(deniedRows(branchId).isEmpty())
        }

        @Test
        fun `the platform creator, submitter and amender are refused and audited`() {
            val creator = platformAdmin()
            val submitter = platformAdmin()
            // One account holding branch.update in the tenant and the platform authority.
            val platformAmender =
                platformAdmin().also { fixture.grantTenantAdmin(organisationId, it) }
            val branchId = createPlatformBranch(creator)
            amend(branchId, platformAmender).andExpect { status { isOk() } }
            submitPlatform(branchId, submitter)

            platformActivate(branchId, creator)
                .andExpect { refusedAs(FORBIDDEN, FORBIDDEN_DETAIL) }
            platformActivate(branchId, submitter)
                .andExpect { refusedAs(FORBIDDEN, FORBIDDEN_DETAIL) }
            platformActivate(branchId, platformAmender)
                .andExpect { refusedAs(MODIFIER, MODIFIER_DETAIL) }

            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
            assertEquals(0, activations(branchId))
            assertEquals(
                listOf(creator to FORBIDDEN, submitter to FORBIDDEN, platformAmender to MODIFIER),
                deniedRows(branchId),
            )
            // A platform request carries no branch context; the row still names the target.
            assertEquals(listOf(branchId, branchId, branchId), deniedBranches(branchId))

            platformActivate(branchId, platformAdmin()).andExpect { status { isOk() } }
            assertEquals("ACTIVE", branchStatus(branchId))
            assertEquals(3, deniedRows(branchId).size)
        }

        @Test
        fun `a refusal before the maker-checker rule writes no denied row`() {
            val branchId = createBranch()
            submit(branchId, maker)

            // A caller with no tenant grant fails the permission check, before the rule is judged.
            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/activate") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                    with(authentication(tenantToken(seedUser("outsider"), "branch.approve")))
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value(FORBIDDEN) }
                    jsonPath("$.detail") { value("Missing permission: branch.approve.") }
                }

            assertTrue(deniedRows(branchId).isEmpty())
            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
        }

        // -- helpers -----------------------------------------------------------------------

        private fun MockMvcResultMatchersDsl.refusedAs(
            code: String,
            detail: String,
        ) {
            status { isForbidden() }
            jsonPath("$.code") { value(code) }
            jsonPath("$.detail") { value(detail) }
        }

        private fun createBranch(): UUID =
            idOf(
                post(ApiPaths.BRANCHES, tenantToken(maker, "branch.create"), branchBody())
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
            )

        private fun createPlatformBranch(actor: UUID): UUID =
            idOf(
                post(
                    "${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches",
                    platformToken(actor),
                    branchBody(),
                ).andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString,
            )

        private fun branchBody() =
            apiJsonCodec.mapper.writeValueAsString(
                CreateBranchRequest(
                    branchCode = "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                    branchName = "Checker Branch",
                    branchType = "OPERATIONAL",
                    timezone = "Africa/Nairobi",
                ),
            )

        private fun amend(
            branchId: UUID,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.BRANCHES}/$branchId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_name":"Amended Branch"}"""
                with(authentication(tenantToken(actor, "branch.update")))
            }

        private fun submit(
            branchId: UUID,
            actor: UUID,
        ) {
            post("${ApiPaths.BRANCHES}/$branchId/submit", tenantToken(actor, "branch.create"), "{}")
                .andExpect { status { isOk() } }
        }

        private fun submitPlatform(
            branchId: UUID,
            actor: UUID,
        ) {
            post(platformPath(branchId, "submit"), platformToken(actor), "{}")
                .andExpect { status { isOk() } }
        }

        private fun activate(
            branchId: UUID,
            actor: UUID,
            pinnedTo: UUID? = null,
        ) = post(
            "${ApiPaths.BRANCHES}/$branchId/activate",
            tenantToken(actor, "branch.approve", pinnedTo),
            "{}",
        )

        private fun platformActivate(
            branchId: UUID,
            actor: UUID,
        ) = post(platformPath(branchId, "activate"), platformToken(actor), "{}")

        private fun platformPath(
            branchId: UUID,
            action: String,
        ) = "${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches/$branchId/$action"

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun idOf(body: String): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get("branch_id")
                    .asString(),
            )

        private fun branchStatus(branchId: UUID): String =
            dsl
                .fetchOne("SELECT status::text FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun activations(branchId: UUID): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM branch_transition_log " +
                        "WHERE branch_id = ? AND transition_name = 'ACTIVATE'",
                    branchId,
                )!!
                .get(0, Int::class.java)

        /**
         * The actor and `reason` of each committed `DENIED`, `HIGH` `branch.activate` row on the
         * branch, oldest first: read after the refused request rolled back, so only a row written
         * in its own transaction can be here.
         */
        private fun deniedRows(branchId: UUID): List<Pair<UUID, String>> =
            dsl
                .fetch(
                    "SELECT actor_user_id, reason FROM audit_event WHERE organisation_id = ? " +
                        "AND entity_type = 'BRANCH' AND entity_id = ? " +
                        "AND action = 'branch.activate' AND outcome = 'DENIED' " +
                        "AND severity = 'HIGH' ORDER BY event_time",
                    organisationId,
                    branchId,
                ).map { it.get(0, UUID::class.java) to it.get(1, String::class.java) }

        /** The `branch_id` of each committed `DENIED` `branch.activate` row, oldest first. */
        private fun deniedBranches(branchId: UUID): List<UUID?> =
            dsl
                .fetch(
                    "SELECT branch_id FROM audit_event WHERE organisation_id = ? " +
                        "AND entity_type = 'BRANCH' AND entity_id = ? " +
                        "AND action = 'branch.activate' AND outcome = 'DENIED' " +
                        "ORDER BY event_time",
                    organisationId,
                    branchId,
                ).map { it.get(0, UUID::class.java) }

        private fun headOfficeId(): UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_TYPE.eq("HEAD_OFFICE"))
                    .and(BRANCH.STATUS.eq("ACTIVE"))
                    .fetchOne(BRANCH.ID),
            )

        private fun tenantToken(
            userId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
        ) = token(userId, organisationId, setOf(permission), pinnedTo)

        private fun platformToken(userId: UUID) = token(userId, PlatformOrganisation.ID, COARSE)

        private fun token(
            userId: UUID,
            organisation: UUID,
            permissions: Set<String>,
            pinnedTo: UUID? = null,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisation,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@branch-checker.test",
                fullName = "Branch Checker User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@branch-checker.test")
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
            const val FORBIDDEN = "forbidden"
            const val FORBIDDEN_DETAIL = "You are not permitted to perform this action."
            const val MODIFIER = "lifecycle.approver_is_branch_modifier"
            const val MODIFIER_DETAIL = "The approver cannot be someone who amended the branch."

            /** Coarse gate only; the service re-checks the caller's real platform grants. */
            val COARSE = setOf("branch.create", "branch.approve", "branch.view")
        }
    }
