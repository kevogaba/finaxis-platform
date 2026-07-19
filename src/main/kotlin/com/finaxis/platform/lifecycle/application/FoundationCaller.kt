package com.finaxis.platform.lifecycle.application

import java.util.UUID

/**
 * Caller context used by lifecycle and IAM application services to carry the actor's active
 * organisation/branch context into use-case methods without coupling those methods to HTTP
 * concerns.
 *
 * Use [TenantCaller] when the actor is operating inside a tenant organisation context and
 * [PlatformCaller] when the actor is operating against the reserved platform organisation.
 */
sealed interface FoundationCaller {
    val actorId: UUID
}

/**
 * Caller acting inside an active tenant organisation, optionally with an active branch.
 */
data class TenantCaller(
    override val actorId: UUID,
    val activeOrganisationId: UUID,
    val activeBranchId: UUID? = null,
) : FoundationCaller

/**
 * Caller acting inside the reserved platform organisation context.
 */
data class PlatformCaller(
    override val actorId: UUID,
    val platformOrganisationId: UUID,
) : FoundationCaller
