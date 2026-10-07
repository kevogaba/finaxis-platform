package com.finaxis.platform.iam.domain

/** A view permission a role lacks, and the mutation permission that needs it. */
data class MissingView(
    val viewCode: String,
    val neededBy: String,
)

/**
 * The role composition rule of ADR 0030 point 2: a role that holds a mutation permission holds
 * every view permission the catalogue pairs with it, so the user can see their writes.
 *
 * Framework-free and stateless. Callers pass the **ACTIVE** codes a role holds (runtime resolution
 * honours nothing else, so a deprecated or disabled view counts as missing) and the catalogue's
 * `permission_view_requirement` pairings as a map from a mutation code to its view codes. A code
 * absent from that map (a view or a context code) needs nothing.
 */
object RoleComposition {
    /**
     * The views [mutationCode] needs ([requiredViews]) that the role does not hold, sorted by view
     * code. Empty when the grant complies, which includes any view or context code.
     *
     * Only the code being granted is checked, never the rest of the role, so a legacy violating
     * role can be repaired one code at a time.
     */
    fun missingViewsForGrant(
        mutationCode: String,
        requiredViews: Set<String>,
        held: Set<String>,
    ): List<MissingView> =
        requiredViews
            .filterNot { it in held }
            .sorted()
            .map { MissingView(it, mutationCode) }

    /**
     * The held mutation codes that need [viewCode], sorted: who stops being able to see their
     * writes if the role loses it. [requirements] maps mutation codes to their view codes.
     */
    fun dependantsOfView(
        viewCode: String,
        held: Set<String>,
        requirements: Map<String, Set<String>>,
    ): List<String> =
        held
            .filter { code -> viewCode in requirements[code].orEmpty() }
            .sorted()

    /** The `validation_failed` detail for a grant that leaves [missing] views unheld. */
    fun grantRefusal(missing: List<MissingView>): String =
        "Missing view permissions: " +
            missing.joinToString("; ") { "${it.viewCode} (required by ${it.neededBy})" } +
            "."

    /** The `validation_failed` detail for removing [viewCode] while [dependants] need it. */
    fun removalRefusal(
        viewCode: String,
        dependants: List<String>,
    ): String =
        "Permission $viewCode is required by held permissions: ${dependants.joinToString(", ")}."
}
