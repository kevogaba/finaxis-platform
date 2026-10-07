package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.IamAdministrationPersistence
import com.finaxis.platform.iam.application.role.AssignPermissionToRole
import com.finaxis.platform.iam.application.role.CreateTenantRole
import com.finaxis.platform.iam.application.role.RemovePermissionFromRole
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Role composition against real Postgres (ADR 0030 point 2 and 3): the 400s of assign and remove,
 * the `missing_view_permissions` report on role list and detail, the row lock that makes two
 * concurrent compositions of one role serialise, and the repair path for a legacy violating role.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
// LargeClass: one class holds the scenarios of one real-Postgres fixture (tenant, actor, helpers).
@Suppress("LargeClass")
class RoleCompositionIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val roleManagementService: RoleManagementService,
        private val persistence: IamAdministrationPersistence,
        private val transactionManager: PlatformTransactionManager,
    ) {
        private val actorId = uuidV7()

        private val organisationId: UUID by lazy {
            seedUserAccount(actorId)
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
                .createActiveOrganisation("role-composition", actorId)
        }

        @Test
        fun `assigning a mutation without its view answers 400 listing every missing view`() {
            val roleId = createRole()

            val refusal = assign(roleId, "user.invite")

            refusal.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.detail") {
                    value(
                        "Missing view permissions: membership.view (required by user.invite); " +
                            "user.view (required by user.invite).",
                    )
                }
            }
            assertEquals(emptySet(), heldCodes(roleId))
        }

        @Test
        fun `a mutation is granted once its view is held and the role then reports nothing`() {
            val roleId = createRole()

            assign(roleId, "branch.view").andExpect { status { isCreated() } }
            assign(roleId, "branch.suspend").andExpect { status { isCreated() } }

            assertEquals(setOf("branch.view", "branch.suspend"), heldCodes(roleId))
            roleDetail(roleId).andExpect { jsonPath("$.missing_view_permissions") { isEmpty() } }
            roleList(
                roleId,
            ).andExpect { jsonPath("$.items[0].missing_view_permissions") { isEmpty() } }
        }

        @Test
        fun `removing a view a held mutation needs answers 400 naming the dependants`() {
            val roleId = createRole()
            grant(roleId, "branch.view", "branch.suspend", "branch.close")

            remove(roleId, "branch.view").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.detail") {
                    value(
                        "Permission branch.view is required by held permissions: " +
                            "branch.close, branch.suspend.",
                    )
                }
            }
            assertEquals(setOf("branch.view", "branch.suspend", "branch.close"), heldCodes(roleId))

            remove(roleId, "branch.suspend").andExpect { status { isOk() } }
            remove(roleId, "branch.close").andExpect { status { isOk() } }
            remove(roleId, "branch.view").andExpect { status { isOk() } }
            assertEquals(emptySet(), heldCodes(roleId))
        }

        @Test
        fun `a legacy violating role is reported and repaired one code at a time`() {
            val roleId = createRole()
            // What an earlier release allowed: a mutation held with no view at all.
            grant(roleId, "user.approve", "branch.suspend", "role.view")

            roleDetail(roleId).andExpect {
                status { isOk() }
                jsonPath("$.missing_view_permissions.length()") { value(2) }
                jsonPath("$.missing_view_permissions[0]") { value("branch.view") }
                jsonPath("$.missing_view_permissions[1]") { value("membership.view") }
            }
            roleList(roleId).andExpect {
                jsonPath("$.items[0].missing_view_permissions[0]") { value("branch.view") }
                jsonPath("$.items[0].missing_view_permissions[1]") { value("membership.view") }
            }

            // Adding another mutation fails only on its own missing view.
            assign(roleId, "membership.suspend").andExpect { status { isBadRequest() } }
            // Adding a view never fails, and repairs one code at a time.
            assign(roleId, "branch.view").andExpect { status { isCreated() } }
            roleDetail(roleId).andExpect {
                jsonPath("$.missing_view_permissions.length()") { value(1) }
                jsonPath("$.missing_view_permissions[0]") { value("membership.view") }
            }
            assign(roleId, "membership.view").andExpect { status { isCreated() } }
            roleDetail(roleId).andExpect { jsonPath("$.missing_view_permissions") { isEmpty() } }
        }

        @Test
        fun `re-granting a held mutation is a no-op even on a legacy violating role`() {
            val roleId = createRole()
            grant(roleId, "branch.suspend")

            assign(roleId, "branch.suspend").andExpect { status { isCreated() } }
            // A NEW mutation still fails only on its own missing view.
            assign(roleId, "branch.close").andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") {
                    value("Missing view permissions: branch.view (required by branch.close).")
                }
            }
            assertEquals(setOf("branch.suspend"), heldCodes(roleId))
        }

        @Test
        fun `a deprecated mutation can be granted without its view`() {
            val roleId = createRole()

            assign(roleId, "branch.activate").andExpect { status { isCreated() } }

            roleDetail(roleId).andExpect { jsonPath("$.missing_view_permissions") { isEmpty() } }
        }

        @Test
        fun `activating a legacy violating role stays allowed`() {
            val roleId = createRole()
            grant(roleId, "branch.suspend")

            listOf("deactivate", "activate").forEach { action ->
                mockMvc
                    .post("${ApiPaths.ROLES}/$roleId/$action") {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        with(authentication(token(setOf("role.$action", "role.view"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.missing_view_permissions[0]") { value("branch.view") }
                    }
            }
        }

        @Test
        fun `the report is computed for the roles on the page only`() {
            val violating = createRole()
            grant(violating, "branch.suspend")
            val compliant = createRole()
            grant(compliant, "branch.view", "branch.suspend")

            mockMvc
                .get(ApiPaths.ROLES) {
                    param("q", "RC_")
                    param("size", "50")
                    with(authentication(token(setOf("role.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[?(@.id == '$violating')].missing_view_permissions[0]") {
                        value("branch.view")
                    }
                    jsonPath("$.items[?(@.id == '$compliant')].missing_view_permissions[0]") {
                        doesNotExist()
                    }
                }
            // A page that holds only the compliant role reports nothing, whatever the others hold.
            roleList(compliant).andExpect {
                jsonPath("$.items[0].missing_view_permissions") { isEmpty() }
            }
        }

        @Test
        fun `a seeded administrator role complies and stays immutable`() {
            val adminRoleId =
                requireNotNull(
                    dsl
                        .select(ROLE.ID)
                        .from(ROLE)
                        .where(ROLE.ORGANISATION_ID.eq(organisationId))
                        .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                        .fetchOne(ROLE.ID),
                )

            roleDetail(
                adminRoleId,
            ).andExpect { jsonPath("$.missing_view_permissions") { isEmpty() } }
            assign(adminRoleId, "branch.view").andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("conflict") }
            }
        }

        @Test
        fun `a competing composition waits for the role lock and then sees what committed`() {
            val roleId = createRole()
            grant(roleId, "branch.view")
            val template = TransactionTemplate(transactionManager)
            val started = CountDownLatch(1)
            val outcome =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    template
                        .execute {
                            persistence.lockRole(organisationId, roleId)
                            val grantAttempt =
                                executor.submit<Result<Unit>> {
                                    started.countDown()
                                    runCatching { composeAssign(roleId, "branch.suspend") }
                                }
                            started.await()
                            // The competitor cannot finish while this transaction holds the lock: a
                            // snapshot validated without the lock would have granted the mutation.
                            assertFailsWith<java.util.concurrent.TimeoutException> {
                                grantAttempt.get(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                            }
                            persistence.removePermission(
                                organisationId,
                                roleId,
                                permissionId("branch.view"),
                                actorId,
                            )
                            grantAttempt
                        }!!
                        .let { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
                }

            // After the commit the competitor validated against the empty role and was refused.
            val failure = assertFailsWith<InvalidRequestException> { outcome.getOrThrow() }
            assertEquals("validation_failed", failure.code)
            assertEquals(emptySet(), heldCodes(roleId))
        }

        @Test
        fun `concurrent removal of a view and grant of its mutation never leave a violation`() {
            repeat(RACE_ROUNDS) {
                val roleId = createRole()
                grant(roleId, "branch.view")
                val gate = CountDownLatch(1)
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    val removal =
                        executor.submit<Result<Unit>> {
                            gate.await()
                            runCatching { composeRemove(roleId, "branch.view") }
                        }
                    val grant =
                        executor.submit<Result<Unit>> {
                            gate.await()
                            runCatching { composeAssign(roleId, "branch.suspend") }
                        }
                    gate.countDown()
                    val results =
                        listOf(
                            removal,
                            grant,
                        ).map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
                    results.forEach { result ->
                        result.exceptionOrNull()?.let {
                            assertTrue(
                                it is InvalidRequestException,
                                "$it",
                            )
                        }
                    }
                    assertEquals(
                        1,
                        results.count { it.isSuccess },
                        "exactly one composition commits",
                    )
                }
                val held = heldCodes(roleId)
                assertFalse(
                    "branch.suspend" in held && "branch.view" !in held,
                    "the role holds a mutation without its view: $held",
                )
            }
        }

        private fun composeAssign(
            roleId: UUID,
            code: String,
        ) = withRequestContext {
            roleManagementService.assignPermissionToRole(
                AssignPermissionToRole(organisationId, roleId, code, actorId, null),
            )
        }

        private fun composeRemove(
            roleId: UUID,
            code: String,
        ) = withRequestContext {
            roleManagementService.removePermissionFromRole(
                RemovePermissionFromRole(organisationId, roleId, code, actorId, null),
            )
        }

        private fun createRole(): UUID =
            withRequestContext {
                roleManagementService
                    .createTenantRole(
                        CreateTenantRole(
                            organisationId,
                            "RC_${uuidV7().toString().takeLast(ROLE_CODE_SUFFIX)}".uppercase(),
                            "Composition test role",
                            null,
                            actorId,
                            null,
                        ),
                    ).roleId
            }

        /** Writes grants directly, as an earlier release (or a SQL hotfix) could have left them. */
        private fun grant(
            roleId: UUID,
            vararg codes: String,
        ) {
            val now = OffsetDateTime.now()
            codes.forEach { code ->
                dsl
                    .insertInto(ROLE_PERMISSION)
                    .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
                    .set(ROLE_PERMISSION.ROLE_ID, roleId)
                    .set(ROLE_PERMISSION.PERMISSION_ID, permissionId(code))
                    .set(ROLE_PERMISSION.GRANTED_AT, now)
                    .set(ROLE_PERMISSION.GRANTED_BY, SystemActor.ID)
                    .set(ROLE_PERMISSION.CREATED_AT, now)
                    .set(ROLE_PERMISSION.CREATED_BY, SystemActor.ID)
                    .set(ROLE_PERMISSION.UPDATED_AT, now)
                    .set(ROLE_PERMISSION.UPDATED_BY, SystemActor.ID)
                    .execute()
            }
        }

        private fun heldCodes(roleId: UUID): Set<String> =
            dsl
                .select(PERMISSION.PERMISSION_CODE)
                .from(ROLE_PERMISSION)
                .join(PERMISSION)
                .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .fetch(PERMISSION.PERMISSION_CODE)
                .filterNotNull()
                .toSet()

        private fun permissionId(code: String): UUID =
            requireNotNull(
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(code))
                    .fetchOne(PERMISSION.ID),
            ) { "$code must be in the catalogue" }

        private fun assign(
            roleId: UUID,
            code: String,
        ) = mockMvc.post("${ApiPaths.ROLES}/$roleId/permissions") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = apiJsonCodec.mapper.writeValueAsString(AssignPermissionRequest(code))
            with(authentication(token(setOf("role.assign_permission", "role.view"))))
        }

        private fun remove(
            roleId: UUID,
            code: String,
        ) = mockMvc.delete("${ApiPaths.ROLES}/$roleId/permissions/${grantId(roleId, code)}") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
            with(authentication(token(setOf("role.remove_permission", "role.view"))))
        }

        private fun grantId(
            roleId: UUID,
            code: String,
        ): UUID =
            requireNotNull(
                dsl
                    .select(ROLE_PERMISSION.ID)
                    .from(ROLE_PERMISSION)
                    .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                    .and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId(code)))
                    .fetchOne(ROLE_PERMISSION.ID),
            )

        private fun roleDetail(roleId: UUID) =
            mockMvc.get("${ApiPaths.ROLES}/$roleId") {
                with(authentication(token(setOf("role.view"))))
            }

        private fun roleList(roleId: UUID) =
            mockMvc.get(ApiPaths.ROLES) {
                param("q", roleCode(roleId))
                with(authentication(token(setOf("role.view"))))
            }

        private fun roleCode(roleId: UUID): String =
            requireNotNull(
                dsl
                    .select(
                        ROLE.ROLE_CODE,
                    ).from(ROLE)
                    .where(ROLE.ID.eq(roleId))
                    .fetchOne(ROLE.ROLE_CODE),
            )

        private fun token(permissions: Set<String>) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = actorId,
                    keycloakSubject = "tenant-user-$actorId",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    email = "tenant@finaxis.test",
                    fullName = "Tenant Administrator",
                    permissions = permissions,
                ),
            )

        private fun seedUserAccount(userId: UUID) {
            val now = OffsetDateTime.now()
            val label = userId.toString().takeLast(ROLE_CODE_SUFFIX)
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, userId)
                .set(USER_ACCOUNT.USERNAME, "role-composition-$label")
                .set(USER_ACCOUNT.EMAIL, "role-composition-$label@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Role composition $label")
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
        }

        private companion object {
            const val ROLE_CODE_SUFFIX = 10
            const val LOCK_WAIT_MILLIS = 750L
            const val WAIT_SECONDS = 30L
            const val RACE_ROUNDS = 20
        }
    }
