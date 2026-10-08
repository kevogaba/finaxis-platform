package com.finaxis.platform.lifecycle.application

import java.util.UUID

/**
 * Outbound port for persisting initial administrator bootstrap metadata and maker-checker state.
 */
@Suppress("TooManyFunctions") // One cohesive record port; splitting it buys nothing.
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

    /**
     * Returns the record to a draft after a reject or a return for changes: status DRAFT,
     * submitter and approver cleared, the requester and the administrator block untouched.
     */
    fun reject(organisationId: UUID)

    /**
     * Resolves, by email, the account that already exists for the draft's named initial
     * administrator, or null when none exists yet (the bootstrap will create it).
     */
    fun existingAdministratorUserId(organisationId: UUID): UUID?

    /** Finds the bootstrap record for an organisation. */
    fun find(organisationId: UUID): InitialAdministratorBootstrapRecord?

    /**
     * Updates the bootstrap status and optionally records a failure code from the closed set.
     * When [incrementAttempts] is true, the attempt counter is incremented atomically.
     */
    fun updateStatus(
        organisationId: UUID,
        status: InitialAdministratorBootstrapStatus,
        lastFailureCode: InitialAdministratorBootstrapFailureCode? = null,
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
