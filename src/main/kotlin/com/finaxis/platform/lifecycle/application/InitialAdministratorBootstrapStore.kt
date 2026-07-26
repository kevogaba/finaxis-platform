package com.finaxis.platform.lifecycle.application

import java.util.UUID

/**
 * Outbound port for persisting initial administrator bootstrap metadata and maker-checker state.
 */
interface InitialAdministratorBootstrapStore {
    /** Creates the initial bootstrap record in DRAFT status. */
    fun createDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
        requestedBy: UUID,
    )

    /** Amends the bootstrap draft details. */
    fun amendDraft(
        organisationId: UUID,
        admin: InitialAdministratorDraft,
    )

    /** Submits the bootstrap record for approval, transitioning status to PENDING_ACTIVATION. */
    fun submit(
        organisationId: UUID,
        actorId: UUID,
    )

    /** Approves the bootstrap record, transitioning status to QUEUED. */
    fun approve(
        organisationId: UUID,
        actorId: UUID,
    )

    /** Rejects the approval request, returning the status to DRAFT. */
    fun reject(organisationId: UUID)

    /** Finds the bootstrap record for an organisation. */
    fun find(organisationId: UUID): InitialAdministratorBootstrapRecord?

    /**
     * Updates the bootstrap status and optionally records a safe failure code.
     * When [incrementAttempts] is true, the attempt counter is incremented atomically.
     */
    fun updateStatus(
        organisationId: UUID,
        status: InitialAdministratorBootstrapStatus,
        lastFailureCode: String? = null,
        incrementAttempts: Boolean = false,
    )

    /** Stores references to resolved platform entities. */
    fun linkResolvedEntities(
        organisationId: UUID,
        userId: UUID?,
        membershipId: UUID?,
        headOfficeId: UUID?,
        roleId: UUID?,
    )
}
