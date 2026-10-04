package com.finaxis.platform.iam.application.role

import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.iam.application.port.outbound.RolePermissionPersistence
import com.finaxis.platform.iam.domain.RoleComposition
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Enforces the role composition rule of ADR 0030 point 2 against the stored role.
 *
 * Both checks read the role's held `ACTIVE` codes, so the caller must already hold the role row
 * lock (`RolePersistence.lockRole`): the read then includes whatever a concurrent composition just
 * committed, and two requests cannot each validate against their own snapshot and both commit a
 * violating role. The pure rule is [RoleComposition].
 */
@Component
class RoleCompositionGuard(
    private val persistence: RolePermissionPersistence,
) {
    /**
     * A mutation is granted only with every view it needs.
     *
     * Nothing is checked in two cases: a code the role already holds as `ACTIVE` (re-granting is
     * an idempotent no-op, even on a role that already violates the rule) and a code that is not
     * `ACTIVE` (it grants nothing at runtime, as the removal check and the report also treat it;
     * the row is still written). A **new** `ACTIVE` mutation is checked only against its own
     * views.
     *
     * @throws InvalidRequestException `validation_failed`, listing every missing view and the code
     *   that needs it, when [permissionCode] needs a view the role does not hold as an `ACTIVE`
     *   grant. A view, or a code that needs no view, never fails.
     */
    fun requireViewsHeld(
        organisationId: UUID,
        roleId: UUID,
        permissionCode: String,
    ) {
        val held = persistence.activePermissionCodes(organisationId, roleId)
        if (permissionCode in held || !persistence.isActivePermission(permissionCode)) return
        val required =
            persistence.requiredViewCodes(listOf(permissionCode))[permissionCode].orEmpty()
        val missing = RoleComposition.missingViewsForGrant(permissionCode, required, held)
        if (missing.isNotEmpty()) {
            throw InvalidRequestException(VALIDATION_FAILED, RoleComposition.grantRefusal(missing))
        }
    }

    /**
     * A view is removed only while no held mutation needs it. A code that does not currently grant
     * anything (not held as `ACTIVE`) cannot take a view away, so it is not checked.
     *
     * @throws InvalidRequestException `validation_failed`, naming the dependants.
     */
    fun requireNoDependants(
        organisationId: UUID,
        roleId: UUID,
        permissionCode: String,
    ) {
        val held = persistence.activePermissionCodes(organisationId, roleId)
        if (permissionCode !in held) return
        val dependants =
            RoleComposition.dependantsOfView(
                permissionCode,
                held,
                persistence.requiredViewCodes(held),
            )
        if (dependants.isNotEmpty()) {
            throw InvalidRequestException(
                VALIDATION_FAILED,
                RoleComposition.removalRefusal(permissionCode, dependants),
            )
        }
    }

    private companion object {
        const val VALIDATION_FAILED = "validation_failed"
    }
}
