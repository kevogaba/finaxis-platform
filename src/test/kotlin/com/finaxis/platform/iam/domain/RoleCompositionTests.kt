package com.finaxis.platform.iam.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The framework-free role composition rule of ADR 0030 point 2. */
class RoleCompositionTests {
    private val requirements =
        mapOf(
            "branch.suspend" to setOf("branch.view"),
            "branch.close" to setOf("branch.view"),
            "user.invite" to setOf("membership.view", "user.view"),
        )

    @Test
    fun `granting a mutation reports every required view the role lacks`() {
        val missing =
            RoleComposition.missingViewsForGrant(
                "user.invite",
                requirements.getValue("user.invite"),
                held = setOf("role.view"),
            )

        assertEquals(
            listOf(
                MissingView("membership.view", "user.invite"),
                MissingView("user.view", "user.invite"),
            ),
            missing,
        )
    }

    @Test
    fun `granting a mutation reports only the views still missing`() {
        val missing =
            RoleComposition.missingViewsForGrant(
                "user.invite",
                requirements.getValue("user.invite"),
                held = setOf("user.view"),
            )

        assertEquals(listOf(MissingView("membership.view", "user.invite")), missing)
    }

    @Test
    fun `granting a view or a code that needs no view never fails`() {
        assertTrue(
            RoleComposition.missingViewsForGrant("branch.view", emptySet(), emptySet()).isEmpty(),
        )
    }

    @Test
    fun `a mutation whose views are all held is complete`() {
        assertTrue(
            RoleComposition
                .missingViewsForGrant(
                    "branch.suspend",
                    setOf("branch.view"),
                    setOf("branch.view", "x"),
                ).isEmpty(),
        )
    }

    @Test
    fun `removing a view names the held mutations that need it, sorted`() {
        val dependants =
            RoleComposition.dependantsOfView(
                "branch.view",
                held = setOf("branch.view", "branch.suspend", "branch.close", "user.invite"),
                requirements = requirements,
            )

        assertEquals(listOf("branch.close", "branch.suspend"), dependants)
    }

    @Test
    fun `removing a view nothing held needs names nobody`() {
        assertTrue(
            RoleComposition
                .dependantsOfView(
                    "user.view",
                    held = setOf("branch.view", "branch.suspend"),
                    requirements = requirements,
                ).isEmpty(),
        )
    }

    @Test
    fun `the refusal detail names each missing view with the code that needs it`() {
        val detail =
            RoleComposition.grantRefusal(
                listOf(
                    MissingView("membership.view", "user.invite"),
                    MissingView("user.view", "user.invite"),
                ),
            )

        assertEquals(
            "Missing view permissions: membership.view (required by user.invite); " +
                "user.view (required by user.invite).",
            detail,
        )
    }

    @Test
    fun `the removal detail names the dependants of the view`() {
        assertEquals(
            "Permission branch.view is required by held permissions: branch.close, branch.suspend.",
            RoleComposition.removalRefusal("branch.view", listOf("branch.close", "branch.suspend")),
        )
    }
}
