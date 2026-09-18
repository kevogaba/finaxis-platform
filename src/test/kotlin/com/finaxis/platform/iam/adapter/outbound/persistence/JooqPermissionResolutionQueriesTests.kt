package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.domain.PermissionEffect
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqPermissionResolutionQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqPermissionResolutionQueries,
    private val resolver: EffectivePermissionResolver,
) {
    @Test
    fun `rolePermissionCodes excludes a branch-scoped grant when that branch is not selected`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val branchId = insertBranch(organisationId)
        val otherBranchId = insertBranch(organisationId)
        val permissionId = insertPermission("branch.report.view")
        val roleId = insertRole(organisationId)
        insertRolePermission(organisationId, roleId, permissionId)
        insertUserRoleAssignment(organisationId, userId, roleId, branchId)

        assertEquals(
            setOf("branch.report.view"),
            queries.rolePermissionCodes(membershipId, branchId),
        )
        assertEquals(emptySet(), queries.rolePermissionCodes(membershipId, otherBranchId))
        assertEquals(emptySet(), queries.rolePermissionCodes(membershipId, null))
    }

    @Test
    fun `rolePermissionCodes always includes tenant-scoped grants regardless of selected branch`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val branchId = insertBranch(organisationId)
        val permissionId = insertPermission("tenant.report.view")
        val roleId = insertRole(organisationId)
        insertRolePermission(organisationId, roleId, permissionId)
        insertUserRoleAssignment(organisationId, userId, roleId, branchId = null)

        assertEquals(
            setOf("tenant.report.view"),
            queries.rolePermissionCodes(membershipId, branchId),
        )
        assertEquals(
            setOf("tenant.report.view"),
            queries.rolePermissionCodes(membershipId, null),
        )
    }

    @Test
    fun `directPermissionEffects returns allow and deny overrides for the membership`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val allowedPermissionId = insertPermission("reports.export")
        val deniedPermissionId = insertPermission("reports.delete")
        insertMembershipPermission(
            organisationId,
            membershipId,
            allowedPermissionId,
            PermissionEffect.ALLOW,
        )
        insertMembershipPermission(
            organisationId,
            membershipId,
            deniedPermissionId,
            PermissionEffect.DENY,
        )

        val effects = queries.directPermissionEffects(membershipId).associateBy { it.code }

        assertEquals(PermissionEffect.ALLOW, effects.getValue("reports.export").effect)
        assertEquals(PermissionEffect.DENY, effects.getValue("reports.delete").effect)
    }

    @Test
    fun `directPermissionEffects is scoped to the requested membership only`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val otherUserId = insertUser()
        val otherMembershipId = insertMembership(organisationId, otherUserId)
        val permissionId = insertPermission("reports.export")
        insertMembershipPermission(
            organisationId,
            otherMembershipId,
            permissionId,
            PermissionEffect.ALLOW,
        )

        val effects = queries.directPermissionEffects(membershipId)

        assertEquals(emptyList(), effects)
    }

    /**
     * The locking break-glass read must answer exactly what the cached resolver answers.
     *
     * `lockedBreakGlassGrant` re-implements `EffectivePermissionResolver.resolve`'s rule by hand,
     * for one code, in three locking statements. A hand-written copy of an authorization predicate
     * is worth only as much as the evidence that it still agrees with the original, so each case
     * below asserts the expected decision **and** that the two implementations reach it together.
     * A divergence in any branch - a dropped status filter, a widened scope, an inverted
     * ALLOW/DENY precedence - fails here rather than shipping as a break-glass bypass or a refusal
     * of a legitimate backdated posting.
     *
     * Every case builds its own membership, so the resolver's application cache is cold for each
     * and cannot mask a wrong database read.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("breakGlassCases")
    internal fun `the locking break-glass read agrees with the cached resolver`(
        case: BreakGlassCase,
    ) {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val code = breakGlassCode()
        case.arrange(Fixture(this, organisationId, userId, membershipId, code))

        val locked = queries.lockedBreakGlassGrant(membershipId, code)
        val resolved = code in resolver.effectivePermissions(membershipId, null)

        assertEquals(case.granted, locked, "the locking read decided ${case.name} wrongly")
        assertEquals(
            resolved,
            locked,
            "the locking read and the cached resolver disagree on ${case.name}: one of them is " +
                "now wrong about who may exercise a break-glass code",
        )
    }

    /**
     * Fail-closed outside a transaction, rather than quietly answering without the locks.
     *
     * A lock taken and released by its own statement guarantees nothing, so a caller that reached
     * this outside a transaction would get an answer that merely *looks* linearizable. Refusing is
     * the only safe option, and this pins it: `NOT_SUPPORTED` suspends the class-level transaction
     * for this test alone.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `the locking break-glass read refuses to answer outside a transaction`() {
        assertFailsWith<IllegalStateException> {
            queries.lockedBreakGlassGrant(uuidV7(), breakGlassCode())
        }
    }

    /** The handles a case needs to arrange its scenario, so the cases stay declarative. */
    internal class Fixture(
        private val tests: JooqPermissionResolutionQueriesTests,
        val organisationId: UUID,
        val userId: UUID,
        val membershipId: UUID,
        val code: String,
    ) {
        fun grantThroughRole(
            branchId: UUID? = null,
            assignmentStatus: String = "ACTIVE",
            roleStatus: String = "ACTIVE",
            permissionStatus: String = "ACTIVE",
        ) {
            val permissionId = tests.insertPermission(code, permissionStatus)
            val roleId = tests.insertRole(organisationId, roleStatus)
            tests.insertRolePermission(organisationId, roleId, permissionId)
            tests.insertUserRoleAssignment(
                organisationId,
                userId,
                roleId,
                branchId,
                assignmentStatus,
            )
        }

        fun grantDirectly(effect: PermissionEffect) {
            val permissionId = tests.findOrInsertPermission(code)
            tests.insertMembershipPermission(organisationId, membershipId, permissionId, effect)
        }

        fun branch(): UUID = tests.insertBranch(organisationId)

        fun setMembershipStatus(status: String) {
            tests.setMembershipStatus(membershipId, status)
        }
    }

    /** One arrangement of the grant graph and the decision both implementations must reach. */
    internal data class BreakGlassCase(
        val name: String,
        val granted: Boolean,
        val arrange: (Fixture) -> Unit,
    ) {
        override fun toString(): String = name
    }

    internal fun setMembershipStatus(
        membershipId: UUID,
        status: String,
    ) {
        dsl
            .update(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .execute()
    }

    internal fun findOrInsertPermission(code: String): UUID =
        dsl
            .select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(code))
            .fetchOne(PERMISSION.ID) ?: insertPermission(code)

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    internal fun insertBranch(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    internal fun insertRole(
        organisationId: UUID,
        status: String = "ACTIVE",
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, id)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, "role-$id")
            .set(ROLE.ROLE_NAME, "Test Role")
            .set(ROLE.STATUS, status)
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.UPDATED_AT, now)
            .execute()
        return id
    }

    internal fun insertRolePermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ID, uuidV7())
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .execute()
    }

    internal fun insertUserRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        branchId: UUID?,
        status: String = "ACTIVE",
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, if (branchId != null) "BRANCH" else "TENANT")
            .set(USER_ROLE_ASSIGNMENT.STATUS, status)
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
    }

    internal fun insertPermission(
        code: String,
        status: String = "ACTIVE",
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(PERMISSION)
            .set(PERMISSION.ID, id)
            .set(PERMISSION.PERMISSION_CODE, code)
            .set(PERMISSION.PERMISSION_NAME, code)
            .set(PERMISSION.MODULE_CODE, "TEST")
            .set(PERMISSION.RISK_LEVEL, "LOW")
            .set(PERMISSION.STATUS, status)
            .set(PERMISSION.CREATED_AT, now)
            .set(PERMISSION.UPDATED_AT, now)
            .execute()
        return id
    }

    internal fun insertMembershipPermission(
        organisationId: UUID,
        membershipId: UUID,
        permissionId: UUID,
        effect: PermissionEffect,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.ID, uuidV7())
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, effect.name)
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .execute()
    }

    private companion object {
        /**
         * A fresh code per case, not `journal.post_prior_period`.
         *
         * The queries filter on whatever is passed, so the value is immaterial to what is being
         * proved - but a real code is already in the seeded catalogue, and a case that needs it
         * `INACTIVE` would have to mutate platform-wide reference data to arrange itself. A
         * per-case code keeps every scenario building only rows it owns.
         */
        fun breakGlassCode(): String = "test.break_glass.${uuidV7()}"

        /**
         * One row per branch of the predicate. `DENY` beside a role grant is the precedence case;
         * the status cases are the filters most easily dropped in a rewrite, and each enumerates
         * the non-`ACTIVE` values its `CHECK` constraint actually admits rather than one
         * representative; the branch case is the scope widening that would hand a branch-scoped
         * holder a tenant-wide authority.
         *
         * A direct `ALLOW` and a direct `DENY` for one code cannot coexist, because
         * `membership_permission` is unique on `(organisation_id, membership_id, permission_id)`,
         * so that pair is not a case that can be arranged. Its absence is deliberate.
         */
        @JvmStatic
        fun breakGlassCases(): List<BreakGlassCase> =
            listOf(
                BreakGlassCase("an active tenant-scoped role grant", granted = true) {
                    it.grantThroughRole()
                },
                BreakGlassCase("no grant of any kind", granted = false) { },
                BreakGlassCase("a direct ALLOW with no role grant", granted = true) {
                    it.grantDirectly(PermissionEffect.ALLOW)
                },
                BreakGlassCase("a direct DENY with no role grant", granted = false) {
                    it.grantDirectly(PermissionEffect.DENY)
                },
                BreakGlassCase("a direct DENY over a role grant", granted = false) {
                    it.grantThroughRole()
                    it.grantDirectly(PermissionEffect.DENY)
                },
                BreakGlassCase("a branch-scoped role grant only", granted = false) {
                    it.grantThroughRole(branchId = it.branch())
                },
                BreakGlassCase("a revoked role assignment", granted = false) {
                    it.grantThroughRole(assignmentStatus = "REVOKED")
                },
                BreakGlassCase("a disabled role", granted = false) {
                    it.grantThroughRole(roleStatus = "DISABLED")
                },
                BreakGlassCase("an archived role", granted = false) {
                    it.grantThroughRole(roleStatus = "ARCHIVED")
                },
                BreakGlassCase("a deprecated permission in the catalogue", granted = false) {
                    it.grantThroughRole(permissionStatus = "DEPRECATED")
                },
                BreakGlassCase("a disabled permission in the catalogue", granted = false) {
                    it.grantThroughRole(permissionStatus = "DISABLED")
                },
                BreakGlassCase("an inactive role assignment", granted = false) {
                    it.grantThroughRole(assignmentStatus = "INACTIVE")
                },
                BreakGlassCase("a role grant held by a suspended membership", granted = false) {
                    it.grantThroughRole()
                    it.setMembershipStatus("SUSPENDED")
                },
            )
    }
}
