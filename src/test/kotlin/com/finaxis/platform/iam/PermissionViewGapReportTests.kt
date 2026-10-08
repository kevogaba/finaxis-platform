package com.finaxis.platform.iam

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The operator view-gap report of ADR 0030 decision 3
 * (`docs/operations/permission-view-gap-report.md`): the SQL text is read **from its files** and
 * run against crafted data, so the documented query is the one tested. Each scenario is a
 * membership or role that either must or must not be listed.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PermissionViewGapReportTests(
    private val jdbcTemplate: JdbcTemplate,
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `the roles report lists exactly the roles that hold a mutation without its view`() {
        val scenario = Scenario()

        val rows = roleReport().filter { it["organisation_code"] == scenario.organisationCode }

        assertEquals(
            setOf(
                Triple("GAP_NOVIEW", "branch.suspend", "branch.view"),
                Triple("GAP_TWO", "user.invite", "membership.view"),
            ),
            rows
                .map {
                    Triple(
                        it["role_code"],
                        it["mutation_code"],
                        it["missing_view_code"],
                    )
                }.toSet(),
        )
        assertEquals(
            rows.size,
            rows.distinctBy { listOf(it["role_code"], it["mutation_code"]) }.size,
        )
        assertTrue(rows.none { it["role_code"] == "TENANT_ADMIN" }, "a seeded admin complies")
    }

    @Test
    fun `the memberships report uses the effective set, overrides and branch contexts`() {
        val scenario = Scenario()

        val rows =
            membershipReport()
                .filter { it["organisation_code"] == scenario.organisationCode }
                .map {
                    Gap(
                        it["user_id"] as UUID,
                        it["membership_status"] as String,
                        it["branch_id"] as UUID?,
                        it["mutation_code"] as String,
                        it["missing_view_code"] as String,
                    )
                }

        assertEquals(
            setOf(
                // A role with a mutation and no view.
                Gap(scenario.noView, "ACTIVE", null, "branch.suspend", "branch.view"),
                // A compliant role, but a direct DENY of the view it needs.
                Gap(scenario.deniedView, "ACTIVE", null, "branch.suspend", "branch.view"),
                // A role-clean membership with a direct ALLOW of a mutation and no view.
                Gap(scenario.allowedMutation, "ACTIVE", null, "user.approve", "membership.view"),
                // The tenant-wide gap that a branch-scoped role closes only in that branch.
                Gap(scenario.viewOnlyAtBranch, "ACTIVE", null, "branch.suspend", "branch.view"),
                // One mutation needs two views and one is held.
                Gap(scenario.oneOfTwoViews, "ACTIVE", null, "user.invite", "membership.view"),
                // A suspended membership is listed with its status; a revoked one is not.
                Gap(scenario.suspended, "SUSPENDED", null, "branch.suspend", "branch.view"),
                // A membership whose ONLY role is BRANCH-scope: the row carries that branch.
                Gap(
                    scenario.branchOnly,
                    "ACTIVE",
                    scenario.branchId,
                    "branch.suspend",
                    "branch.view",
                ),
                // A tenant-wide gap plus an unrelated BRANCH-scope role: repeated for that branch.
                Gap(scenario.gapAndBranchRole, "ACTIVE", null, "branch.suspend", "branch.view"),
                Gap(
                    scenario.gapAndBranchRole,
                    "ACTIVE",
                    scenario.branchId,
                    "branch.suspend",
                    "branch.view",
                ),
            ),
            rows.toSet(),
        )
        assertEquals(rows.size, rows.toSet().size, "no row is repeated")
    }

    @Test
    fun `platform roles that no API lists are covered by both sections`() {
        val actor = uuidV7()
        seedUser(actor)
        fixture.grantPlatformPermissionsExactly(actor, "tenant.approve")

        val roles =
            roleReport().filter {
                it["organisation_code"] == PLATFORM_CODE &&
                    (it["role_code"] as String).startsWith("PLATFORM_EXACT_")
            }
        val memberships =
            membershipReport().filter { it["user_id"] == actor }

        assertTrue(
            roles.any {
                it["mutation_code"] == "tenant.approve" &&
                    it["missing_view_code"] == "tenant.view"
            },
        )
        assertEquals(
            listOf("tenant.approve" to "tenant.view"),
            memberships.map { it["mutation_code"] to it["missing_view_code"] },
        )
        val seeded = roleReport().filter { it["organisation_code"] == PLATFORM_CODE }
        assertTrue(
            seeded.none {
                it["role_code"] == "PLATFORM_SUPER_ADMIN" ||
                    it["role_code"] == "PLATFORM_SUPPORT"
            },
            "the seeded platform roles comply",
        )
    }

    private fun roleReport() = jdbcTemplate.queryForList(sql("permission-view-gap-roles.sql"))

    private fun membershipReport() =
        jdbcTemplate.queryForList(sql("permission-view-gap-memberships.sql"))

    private fun sql(file: String): String = Files.readString(sqlDirectory().resolve(file))

    /** Walks up from the working directory, so a Gradle or an IDE run both find the docs. */
    private fun sqlDirectory(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/operations/sql") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("docs/operations/sql not found above ${System.getProperty("user.dir")}")

    private data class Gap(
        val userId: UUID,
        val status: String,
        val branchId: UUID?,
        val mutation: String,
        val view: String,
    )

    /** One organisation holding every membership and role shape the report must tell apart. */
    private inner class Scenario {
        private val administrator = uuidV7().also({ seedUser(it) })
        val organisationId: UUID = fixture.createActiveOrganisation("gap-report", administrator)
        val organisationCode: String =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT tenant_code FROM organisation WHERE id = ?",
                    String::class.java,
                    organisationId,
                ),
            )
        val branchId = insertBranch()

        private val noViewRole = role("GAP_NOVIEW", "branch.suspend")
        private val cleanRole = role("GAP_CLEAN", "branch.view", "branch.suspend")
        private val roleViewRole = role("GAP_ROLEVIEW", "role.view")
        private val twoViewsRole = role("GAP_TWO", "user.invite", "user.view")
        private val branchViewRole = role("GAP_BRANCHVIEW", "branch.view")

        val noView = member(noViewRole)
        val deniedView = member(cleanRole).also { directOverride(it, "branch.view", "DENY") }
        val allowedMutation =
            member(roleViewRole).also { directOverride(it, "user.approve", "ALLOW") }
        val oneOfTwoViews = member(twoViewsRole)
        val suspended = member(noViewRole, status = "SUSPENDED")
        val branchOnly = member(null).also { assignRole(it, noViewRole, branchId) }
        val gapAndBranchRole = member(noViewRole).also { assignRole(it, roleViewRole, branchId) }
        val viewOnlyAtBranch = member(noViewRole).also { assignRole(it, branchViewRole, branchId) }

        init {
            // Complying or neutralised memberships: nothing of these may be listed.
            member(null).also {
                directOverride(it, "branch.suspend", "ALLOW")
                directOverride(it, "branch.view", "ALLOW")
            }
            member(noViewRole).also { directOverride(it, "branch.suspend", "DENY") }
            member(branchViewRole).also { assignRole(it, noViewRole, branchId) }
            member(noViewRole, status = "REVOKED")
            // A direct ALLOW of a DEPRECATED mutation grants nothing at runtime: not listed.
            member(null).also { directOverride(it, "branch.activate", "ALLOW") }
        }

        private fun role(
            code: String,
            vararg permissionCodes: String,
        ): UUID {
            val now = OffsetDateTime.now()
            val roleId =
                requireNotNull(
                    dsl
                        .insertInto(ROLE)
                        .set(ROLE.ORGANISATION_ID, organisationId)
                        .set(ROLE.ROLE_CODE, code)
                        .set(ROLE.ROLE_NAME, code)
                        .set(ROLE.SYSTEM_ROLE, false)
                        .set(ROLE.STATUS, "ACTIVE")
                        .set(ROLE.CREATED_AT, now)
                        .set(ROLE.CREATED_BY, SystemActor.ID)
                        .set(ROLE.UPDATED_AT, now)
                        .set(ROLE.UPDATED_BY, SystemActor.ID)
                        .returning(ROLE.ID)
                        .fetchOne()
                        ?.id,
                )
            permissionCodes.forEach { code ->
                dsl
                    .insertInto(ROLE_PERMISSION)
                    .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
                    .set(ROLE_PERMISSION.ROLE_ID, roleId)
                    .set(ROLE_PERMISSION.PERMISSION_ID, permissionId(code))
                    .set(ROLE_PERMISSION.GRANTED_AT, now)
                    .set(ROLE_PERMISSION.CREATED_AT, now)
                    .set(ROLE_PERMISSION.UPDATED_AT, now)
                    .execute()
            }
            return roleId
        }

        /** A membership holding [roleId] tenant-wide; returns the user id. */
        private fun member(
            roleId: UUID?,
            status: String = "ACTIVE",
        ): UUID {
            val userId = uuidV7().also({ seedUser(it) })
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
                .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
                .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
                .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                .execute()
            roleId?.let { assignRole(userId, it, null) }
            return userId
        }

        private fun assignRole(
            userId: UUID,
            roleId: UUID,
            branchId: UUID?,
        ) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ROLE_ASSIGNMENT)
                .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
                .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
                .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
                .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
                .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, if (branchId == null) "TENANT" else "BRANCH")
                .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
                .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
                .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
                .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
                .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
                .execute()
        }

        private fun directOverride(
            userId: UUID,
            code: String,
            effect: String,
        ) {
            val now = OffsetDateTime.now()
            val membershipId =
                requireNotNull(
                    dsl
                        .select(USER_ORGANISATION_MEMBERSHIP.ID)
                        .from(USER_ORGANISATION_MEMBERSHIP)
                        .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                        .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                        .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
                )
            dsl
                .insertInto(MEMBERSHIP_PERMISSION)
                .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
                .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
                .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId(code))
                .set(MEMBERSHIP_PERMISSION.EFFECT, effect)
                .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
                .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
                .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
                .execute()
        }

        private fun insertBranch(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(BRANCH)
                .set(BRANCH.ID, id)
                .set(BRANCH.ORGANISATION_ID, organisationId)
                .set(BRANCH.BRANCH_CODE, "gap-$id")
                .set(BRANCH.BRANCH_NAME, "Gap branch")
                .set(BRANCH.BRANCH_TYPE, "MAIN")
                .set(BRANCH.STATUS, "ACTIVE")
                .set(BRANCH.TIMEZONE, "Africa/Nairobi")
                .set(BRANCH.CREATED_AT, now)
                .set(BRANCH.UPDATED_AT, now)
                .execute()
            return id
        }
    }

    private fun permissionId(code: String): UUID =
        requireNotNull(
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(code))
                .fetchOne(PERMISSION.ID),
        ) { "$code must be in the catalogue" }

    private fun seedUser(userId: UUID) {
        val now = OffsetDateTime.now()
        val label = userId.toString().takeLast(LABEL_LENGTH)
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, "gap-$label")
            .set(USER_ACCOUNT.EMAIL, "gap-$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Gap $label")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }

    private companion object {
        const val LABEL_LENGTH = 12
        const val PLATFORM_CODE = "PLATFORM"
    }
}
