package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import java.util.UUID

/**
 * Outbound read port for lifecycle aggregates. Tenant-owned records require the organisation id
 * at the lookup boundary.
 */
interface FoundationLifecycleReader {
    /** Finds an organisation aggregate by its platform identifier. */
    fun findOrganisation(id: UUID): LifecycleAggregate<OrganisationLifecycleState>?

    /** Finds a branch only when it belongs to [organisationId]. */
    fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ): LifecycleAggregate<BranchLifecycleState>?

    /** Finds a global user aggregate by its platform identifier. */
    fun findUser(userId: UUID): LifecycleAggregate<UserLifecycleState>?

    /** Finds a membership only when it belongs to [organisationId]. */
    fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleAggregate<MembershipLifecycleState>?

    /** Finds the global user associated with a membership scoped to [organisationId]. */
    fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID?
}

/** Outbound write port for lifecycle state changes and deactivation cleanup. */
interface FoundationLifecycleWriter {
    /** Persists an organisation state after the transition engine has validated it. */
    fun saveOrganisation(
        aggregate: LifecycleAggregate<OrganisationLifecycleState>,
    ): LifecycleAggregate<OrganisationLifecycleState>

    /** Persists a branch state after the transition engine has validated it. */
    fun saveBranch(
        aggregate: LifecycleAggregate<BranchLifecycleState>,
    ): LifecycleAggregate<BranchLifecycleState>

    /** Persists a user state after the transition engine has validated it. */
    fun saveUser(
        aggregate: LifecycleAggregate<UserLifecycleState>,
    ): LifecycleAggregate<UserLifecycleState>

    /** Persists a membership state after the transition engine has validated it. */
    fun saveMembership(
        aggregate: LifecycleAggregate<MembershipLifecycleState>,
    ): LifecycleAggregate<MembershipLifecycleState>

    /**
     * Revokes branch and role assignments during completed user deactivation and returns the
     * durable identity of every assignment revoked so the caller can audit and externalize the
     * outcome per assignment.
     */
    fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ): List<DeprovisionedAssignment>
}
