package com.finaxis.platform.architecture

import com.finaxis.platform.iam.adapter.inbound.web.RoleAssignmentController
import com.finaxis.platform.lifecycle.adapter.inbound.web.BranchAssignmentController
import com.finaxis.platform.lifecycle.adapter.inbound.web.BranchController
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ADR 0030 point 5: the branch-resource reads are authorised at the branch they concern, by the
 * application layer alone. An endpoint-level `@PreAuthorize("hasAuthority('...view')")` would test
 * the *selected* branch's authority set and answer 403 to a caller pinned to A who holds the view
 * only through a role scoped to B, before the target-aware check ran. This pins the absence of
 * that gate on exactly those routes; the application-layer 403 for a caller with no grant is
 * covered by `TargetAwareReadsIntegrationTests`, which also proves no route is open.
 */
class TargetAwareReadGateRuleTests {
    @Test
    fun `target aware read routes carry no pinned context view gate`() {
        val routes =
            mapOf(
                BranchController::class.java to listOf("searchBranches", "getBranch"),
                BranchAssignmentController::class.java to
                    listOf("searchBranchAssignments", "getBranchAssignment"),
                RoleAssignmentController::class.java to
                    listOf("searchRoleAssignments", "getRoleAssignment"),
            )

        routes.forEach { (controller, names) ->
            names.forEach { name ->
                val method = controller.methods.single { it.name == name }
                assertNotNull(method.getAnnotation(GetMapping::class.java), "$name is a GET read")
                assertNull(
                    method.getAnnotation(PreAuthorize::class.java),
                    "${controller.simpleName}.$name must not carry a coarse view gate (ADR 0030)",
                )
            }
        }
    }

    @Test
    fun `mutation routes of the same controllers keep their coarse gates`() {
        val gated =
            mapOf(
                BranchController::class.java to listOf("createDraft", "update", "suspend"),
                BranchAssignmentController::class.java to listOf("assign", "revoke"),
                RoleAssignmentController::class.java to listOf("assignRole", "revokeRole"),
            )

        gated.forEach { (controller, names) ->
            names.forEach { name ->
                val method = controller.methods.single { it.name == name }
                assertEquals(
                    true,
                    method.isAnnotationPresent(PreAuthorize::class.java),
                    "${controller.simpleName}.$name keeps its coarse gate",
                )
            }
        }
    }
}
