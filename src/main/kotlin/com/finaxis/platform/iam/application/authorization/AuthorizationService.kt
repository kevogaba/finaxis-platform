package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.OrganisationStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Raised when an application authorization rule denies an action.
 */
class AccessDeniedException(
    @Suppress("UNUSED_PARAMETER") message: String,
) : ForbiddenOperationException()

/**
 * Resource metadata used for domain-specific authorization checks.
 */
data class ResourceRef(
    val resourceType: String,
    val resourceId: UUID,
    val organisationId: UUID,
    val branchId: UUID? = null,
    val warehouseId: UUID? = null,
    val ownerId: UUID? = null,
)

/**
 * Central authorization facade used by application services.
 */
@Service
class AuthorizationService(
    private val membershipSelectionLookup: MembershipSelectionLookup,
    private val requestPermissionCache: RequestPermissionCache,
    private val permissionResolutionQueries: PermissionResolutionQueries,
) {
    /**
     * Returns whether the principal has the permission code in the active membership context.
     */
    fun hasPermission(
        principal: AppPrincipal,
        permissionCode: String,
    ): Boolean = permissionCode in principal.permissions

    /**
     * Requires the permission code in the active membership context.
     */
    fun requirePermission(
        principal: AppPrincipal,
        permissionCode: String,
    ) {
        if (hasPermission(principal, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    /**
     * Returns whether the principal can use a permission against a resource.
     */
    fun can(
        principal: AppPrincipal,
        permissionCode: String,
        resourceRef: ResourceRef,
    ): Boolean {
        if (!hasPermission(principal, permissionCode)) {
            return false
        }
        return principal.organisationId == resourceRef.organisationId
    }

    /**
     * Requires permission and resource-specific access.
     */
    fun require(
        principal: AppPrincipal,
        permissionCode: String,
        resourceRef: ResourceRef,
    ) {
        if (can(principal, permissionCode, resourceRef)) {
            return
        }
        throw AccessDeniedException(
            "Access denied for ${resourceRef.resourceType}:${resourceRef.resourceId} " +
                "with permission $permissionCode",
        )
    }

    /**
     * Lists effective permission codes for [userId] in [organisationId].
     */
    fun listEffectivePermissions(
        userId: UUID,
        organisationId: UUID,
    ): Set<String> {
        if (!isOrganisationActive(organisationId)) {
            return emptySet()
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return emptySet()
        return requestPermissionCache.effectivePermissions(membership.membershipId, null)
    }

    /**
     * Lists effective permission codes for [userId] in [organisationId] and [branchId].
     */
    fun listEffectiveBranchPermissions(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
    ): Set<String> {
        if (!isOrganisationActive(organisationId)) {
            return emptySet()
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return emptySet()
        return requestPermissionCache.effectivePermissions(membership.membershipId, branchId)
    }

    /**
     * Requires [userId] to hold a break-glass [permissionCode] in [organisationId], with **no**
     * system-actor exemption, **no** dependency on an active web request, and **no** cache.
     *
     * Three deliberate differences from [requirePermission]:
     *
     * Unlike [hasPermission], this does not short-circuit for the system-actor sentinels. That
     * exemption is right for background provisioning and wrong for a ledger control - a batch job
     * would otherwise exercise authority no principal holds.
     *
     * It does not route through `RequestPermissionCache`, which is `@RequestScope`. Dereferencing
     * that proxy outside a web request raises `ScopeNotActiveException`, so routing a background
     * caller through the cached path would fail with a scope error rather than evaluating its
     * grant - defeating the point of denying the system-actor bypass in the first place, since the
     * documented alternative is precisely a background job running under a real service identity.
     *
     * And it no longer routes through [EffectivePermissionResolver] either, which is the change
     * issue #123 asked for. That resolver is cache-first, and it is reached from the posting path
     * inside a `SERIALIZABLE` transaction, which made the answer wrong in two compounding ways: a
     * cache hit answered from a set resolved before the revocation, and a cache *miss* - which is
     * exactly what a revocation produces, since revoking evicts - fell through to a database read
     * taken from the posting's pinned, pre-revocation snapshot. The eviction that should have
     * denied the posting was what routed the check onto the path where the revocation was
     * invisible. [PermissionResolutionQueries.lockedBreakGlassGrant] answers instead, from rows
     * this transaction holds `FOR SHARE`, so a revocation either waits for the posting or aborts
     * it. See `docs/adr/0026-real-time-gates-on-the-serializable-posting-path.md`.
     *
     * Outside a transaction the locks would be taken and released by their own statements and
     * guarantee nothing, so the query adapter refuses the call. Every current caller -
     * `PostingPeriodResolver` and `FiscalPeriodLifecycleService` - is already inside one.
     */
    fun requireBreakGlassPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (holdsBreakGlassPermission(userId, organisationId, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing break-glass permission: $permissionCode")
    }

    private fun holdsBreakGlassPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): Boolean {
        // Unlocked, and unchanged by issue #123, which is narrower than it may look: this read is
        // snapshot-bound for the same reason the grant read was, and it is left that way.
        //
        // On the posting path it happens to be covered - `functionalCurrencyForPosting` already
        // holds the `organisation` row `FOR SHARE` several statements earlier, so a suspension
        // committed after the snapshot has aborted the transaction before this line runs. On the
        // *other* caller, `FiscalPeriodLifecycleService.reopen`/`lock`, nothing holds that row at
        // all, so a suspension racing a reopen is still decided from a stale snapshot. That is
        // pre-existing behaviour on a path this change does not touch, and closing it means adding
        // an `organisation` lock to a flow whose whole lock chain is one fiscal-period row - a
        // decision for whoever takes that on, not a side effect of the break-glass repair.
        if (!isOrganisationActive(organisationId)) {
            return false
        }
        val membership =
            membershipSelectionLookup.findMembership(
                userId = userId,
                organisationId = organisationId,
            ) ?: return false
        return permissionResolutionQueries.lockedBreakGlassGrant(
            membership.membershipId,
            permissionCode,
        )
    }

    /**
     * Returns whether [userId] has [permissionCode] in [organisationId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): Boolean =
        SystemActor.isSystemActor(userId) ||
            permissionCode in listEffectivePermissions(userId, organisationId)

    /**
     * Returns whether [userId] has [permissionCode] in [organisationId] and [branchId].
     */
    fun hasPermission(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ): Boolean =
        SystemActor.isSystemActor(userId) ||
            permissionCode in
            listEffectiveBranchPermissions(
                userId = userId,
                organisationId = organisationId,
                branchId = branchId,
            )

    /**
     * Requires [userId] to have [permissionCode] in [organisationId].
     */
    fun requirePermission(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (hasPermission(userId, organisationId, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    /**
     * Requires [userId] to have [permissionCode] in [organisationId] and [branchId].
     */
    fun requirePermission(
        userId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        if (hasPermission(userId, organisationId, branchId, permissionCode)) {
            return
        }
        throw AccessDeniedException("Missing permission: $permissionCode")
    }

    private fun isOrganisationActive(organisationId: UUID): Boolean =
        membershipSelectionLookup.organisationStatus(organisationId) == OrganisationStatus.ACTIVE
}
