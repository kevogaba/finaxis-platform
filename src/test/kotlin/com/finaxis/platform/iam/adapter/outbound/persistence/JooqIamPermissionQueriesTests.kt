package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.application.query.PermissionFilter
import com.finaxis.platform.jooq.tables.references.PERMISSION
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The permission catalogue read port against the migrated database, `V22` metadata included. */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqIamPermissionQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqIamPermissionQueries,
) {
    @Test
    fun `searchPermissions publishes kind, grant scope and the views a mutation requires`() {
        val page = queries.searchPermissions(PermissionFilter(q = "user.", size = 100))
        val byCode = page.items.associateBy { it.permissionCode }

        val invite = byCode.getValue("user.invite")
        assertEquals("MUTATION", invite.kind)
        assertEquals("TENANT", invite.grantScope)
        assertEquals(listOf("membership.view", "user.view"), invite.requiredViewPermissions)

        val suspend = byCode.getValue("user.suspend")
        assertEquals("PLATFORM", suspend.grantScope)
        assertEquals(listOf("user.view"), suspend.requiredViewPermissions)

        val view = byCode.getValue("user.view")
        assertEquals("VIEW", view.kind)
        assertEquals(emptyList(), view.requiredViewPermissions)
    }

    @Test
    fun `searchPermissions keeps paging and sorting on the catalogue`() {
        val first =
            queries.searchPermissions(
                PermissionFilter(page = 0, size = 30, sortBy = "permissionCode", sortDir = "ASC"),
            )
        val last =
            queries.searchPermissions(
                PermissionFilter(page = 2, size = 30, sortBy = "permissionCode", sortDir = "ASC"),
            )

        assertEquals(81, first.page.totalItems)
        assertEquals(30, first.items.size)
        assertEquals(21, last.items.size)
        assertEquals("accounting_report.export", first.items.first().permissionCode)
        assertEquals(
            0,
            first.items.count { it.kind == "MUTATION" && it.requiredViewPermissions.isEmpty() },
        )
    }

    @Test
    fun `searchPermissions filters by status and by risk level`() {
        val deprecated =
            queries.searchPermissions(PermissionFilter(status = "DEPRECATED", size = 100))
        assertEquals(listOf("branch.activate"), deprecated.items.map { it.permissionCode })

        val high = queries.searchPermissions(PermissionFilter(riskLevel = "HIGH", size = 100))
        assertTrue(high.items.isNotEmpty())
        assertTrue(high.items.all { it.riskLevel == "HIGH" })
        assertTrue(high.items.any { it.permissionCode == "user.invite" })
        assertTrue(high.page.totalItems < 81)
    }

    @Test
    fun `searchPermissions sorts by every supported field in both directions`() {
        fun codes(
            sortBy: String,
            sortDir: String,
        ) = queries
            .searchPermissions(PermissionFilter(size = 100, sortBy = sortBy, sortDir = sortDir))
            .items

        // permission_code and permission_name are unique, so DESC is exactly ASC reversed.
        assertEquals(
            codes("permissionCode", "ASC").map { it.permissionCode }.reversed(),
            codes("permissionCode", "DESC").map { it.permissionCode },
        )
        assertEquals(
            codes("permissionName", "ASC").map { it.permissionCode }.reversed(),
            codes("permissionName", "DESC").map { it.permissionCode },
        )

        // status has one DEPRECATED row among ACTIVE ones, so it sits at one end per direction.
        assertEquals("branch.activate", codes("status", "DESC").first().permissionCode)
        assertEquals("ACTIVE", codes("status", "ASC").first().status)
        assertEquals("DEPRECATED", codes("status", "ASC").last().status)

        val riskAsc = codes("riskLevel", "ASC").map { it.riskLevel }
        assertEquals(riskAsc.sorted(), riskAsc)
        val riskDesc = codes("riskLevel", "DESC").map { it.riskLevel }
        assertEquals(riskDesc.sortedDescending(), riskDesc)
    }

    @Test
    fun `findPermissionById publishes the same metadata and hides nothing else`() {
        val inviteId = permissionId("user.invite")
        val viewId = permissionId("user.view")
        val contextId = permissionId("auth.select_branch")

        val invite = requireNotNull(queries.findPermissionById(inviteId))
        assertEquals("MUTATION", invite.kind)
        assertEquals("TENANT", invite.grantScope)
        assertEquals(listOf("membership.view", "user.view"), invite.requiredViewPermissions)
        assertEquals("HIGH", invite.riskLevel)
        assertEquals("iam", invite.moduleCode)

        assertEquals(emptyList(), queries.findPermissionById(viewId)?.requiredViewPermissions)
        assertEquals("CONTEXT", queries.findPermissionById(contextId)?.kind)
        assertNull(queries.findPermissionById(UUID.randomUUID()))
    }

    private fun permissionId(code: String): UUID =
        requireNotNull(
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(code))
                .fetchOne(PERMISSION.ID),
        )
}
