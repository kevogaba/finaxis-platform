package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof for issue #175: `GET /api/v1/auth/me` reports, beside the flat `permissions`,
 * where each permission is held (`permission_scopes`), across every ACTIVE branch rather than only
 * the selected one.
 *
 * The bootstrap `local.admin` is a full tenant administrator, so a test that proves a grant held
 * ONLY at branch scope first withholds the code from the `local-admin` role. Everything changed is
 * put back in [restoreSeed].
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfilePermissionScopesIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var cacheManager: CacheManager

    private val createdRoleIds = mutableListOf<UUID>()
    private val withheldPermissionIds = mutableListOf<UUID>()

    @BeforeEach
    fun resetSeed() {
        setBranchStatus(OPERATIONS_BRANCH_ID, "ACTIVE")
        invalidateCache()
    }

    @AfterEach
    fun restoreSeed() {
        dsl
            .deleteFrom(MEMBERSHIP_PERMISSION)
            .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(MEMBERSHIP_ID))
            .execute()
        createdRoleIds.forEach { roleId ->
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
                .execute()
            dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute()
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
        val now = OffsetDateTime.now()
        withheldPermissionIds.forEach { permissionId ->
            dsl
                .insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
                .set(ROLE_PERMISSION.ROLE_ID, LOCAL_ADMIN_ROLE_ID)
                .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                .set(ROLE_PERMISSION.GRANTED_AT, now)
                .set(ROLE_PERMISSION.CREATED_AT, now)
                .set(ROLE_PERMISSION.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        }
        withheldPermissionIds.clear()
        setBranchStatus(OPERATIONS_BRANCH_ID, "ACTIVE")
        invalidateCache()
    }

    @Test
    fun `a tenant wide grant reports TENANT and carries no branch ids`() {
        val body = profile(branchId = null)

        val scope = requireNotNull(scopeOf(body, "iam.profile.read"))
        assertThat(scope["scope"]).isEqualTo("TENANT")
        assertThat(scope).doesNotContainKey("branch_ids")
    }

    @Test
    fun `a branch only grant on an active branch reports BRANCH with that branch`() {
        withholdFromLocalAdmin("branch.suspend")
        grantRole("ops-suspender", "branch.suspend", OPERATIONS_BRANCH_ID)

        // Pinned to the head office the flat list does not hold it, yet the scope names OPS.
        val pinnedToHeadOffice = profile(HEAD_OFFICE_BRANCH_ID)
        assertThat(JsonPath.read<List<String>>(pinnedToHeadOffice, "$.permissions"))
            .doesNotContain("branch.suspend")
        val scope = requireNotNull(scopeOf(pinnedToHeadOffice, "branch.suspend"))
        assertThat(scope["scope"]).isEqualTo("BRANCH")
        assertThat(scope["branch_ids"]).isEqualTo(listOf(OPERATIONS_BRANCH_ID.toString()))

        // Pinned to the branch itself the flat list holds it and the scope is the same.
        val pinnedToOperations = profile(OPERATIONS_BRANCH_ID)
        assertThat(JsonPath.read<List<String>>(pinnedToOperations, "$.permissions"))
            .contains("branch.suspend")
        assertThat(scopeOf(pinnedToOperations, "branch.suspend")).isEqualTo(scope)
    }

    @Test
    fun `a grant on a suspended branch is absent and read fresh without a cache flush`() {
        withholdFromLocalAdmin("branch.suspend")
        grantRole("ops-suspender", "branch.suspend", OPERATIONS_BRANCH_ID)
        assertThat(scopeOf(profile(null), "branch.suspend")).isNotNull()

        // No cache invalidation: the scopes must not sit under the resolver's cache key.
        setBranchStatus(OPERATIONS_BRANCH_ID, "SUSPENDED")

        assertThat(scopeOf(profile(null), "branch.suspend")).isNull()
    }

    @Test
    fun `a code held tenant wide and on a branch is reported once as TENANT`() {
        grantRole("ops-profile", "iam.profile.read", OPERATIONS_BRANCH_ID)

        val body = profile(null)

        val entries =
            JsonPath.read<List<Map<String, Any>>>(
                body,
                "$.permission_scopes[?(@.code=='iam.profile.read')]",
            )
        assertThat(entries).hasSize(1)
        assertThat(entries.single()["scope"]).isEqualTo("TENANT")
    }

    @Test
    fun `a direct DENY removes the code and a direct ALLOW is tenant wide`() {
        withholdFromLocalAdmin("branch.suspend")
        grantRole("ops-suspender", "branch.suspend", OPERATIONS_BRANCH_ID)
        directOverride("branch.close", "DENY")
        directOverride("branch.suspend", "ALLOW")

        val body = profile(null)

        assertThat(scopeOf(body, "branch.close")).isNull()
        assertThat(
            JsonPath.read<List<String>>(body, "$.permissions"),
        ).doesNotContain("branch.close")
        val allowed = requireNotNull(scopeOf(body, "branch.suspend"))
        assertThat(allowed["scope"]).isEqualTo("TENANT")
        assertThat(allowed).doesNotContainKey("branch_ids")
    }

    @Test
    fun `the scopes are sorted by code and the flat permissions stay a sorted code list`() {
        val body = profile(HEAD_OFFICE_BRANCH_ID)

        val codes = JsonPath.read<List<String>>(body, "$.permission_scopes[*].code")
        assertThat(codes).isNotEmpty().isSorted()
        val permissions = JsonPath.read<List<String>>(body, "$.permissions")
        assertThat(permissions).isNotEmpty().isSorted().contains("iam.profile.read")
        // The administrator holds every code tenant-wide, so the two lists name the same codes.
        assertThat(codes).containsExactlyElementsOf(permissions)
    }

    private fun profile(branchId: UUID?): String =
        mockMvc
            .get("/api/v1/auth/me") {
                with(jwt().jwt { token -> token.subject(USER_ID.toString()) })
                header(
                    ActiveOrganisationContextService.HEADER,
                    contextService.issue(
                        ActiveOrganisationContext(
                            USER_ID,
                            ORGANISATION_ID,
                            MEMBERSHIP_ID,
                            branchId,
                        ),
                    ),
                )
            }.andReturn()
            .response
            .contentAsString

    private fun scopeOf(
        body: String,
        code: String,
    ): Map<String, Any>? =
        JsonPath
            .read<List<Map<String, Any>>>(body, "$.permission_scopes[?(@.code=='$code')]")
            .singleOrNull()

    private fun setBranchStatus(
        branchId: UUID,
        status: String,
    ) {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, status)
            .where(BRANCH.ID.eq(branchId))
            .execute()
    }

    private fun invalidateCache() {
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    private fun permissionId(code: String): UUID =
        requireNotNull(
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(code))
                .fetchOne(PERMISSION.ID),
        )

    private fun withholdFromLocalAdmin(code: String) {
        val id = permissionId(code)
        withheldPermissionIds += id
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ROLE_ID.eq(LOCAL_ADMIN_ROLE_ID))
            .and(ROLE_PERMISSION.PERMISSION_ID.eq(id))
            .execute()
        invalidateCache()
    }

    private fun directOverride(
        code: String,
        effect: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, MEMBERSHIP_ID)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId(code))
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
            .set(MEMBERSHIP_PERMISSION.EFFECT, effect)
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .execute()
        invalidateCache()
    }

    private fun grantRole(
        roleName: String,
        code: String,
        branchId: UUID,
    ) {
        val now = OffsetDateTime.now()
        val roleId =
            requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, ORGANISATION_ID)
                    .set(ROLE.ROLE_CODE, "$roleName-${uuidV7().toString().takeLast(SHORT_ID)}")
                    .set(ROLE.ROLE_NAME, roleName)
                    .set(ROLE.STATUS, "ACTIVE")
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.UPDATED_AT, now)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        createdRoleIds += roleId
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId(code))
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, ORGANISATION_ID)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, USER_ID)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "BRANCH")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        invalidateCache()
    }

    private companion object {
        const val SHORT_ID = 8
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OPERATIONS_BRANCH_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
