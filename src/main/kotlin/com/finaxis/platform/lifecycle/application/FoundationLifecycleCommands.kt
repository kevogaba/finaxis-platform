package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import java.util.UUID

/** Explicit application commands prevent lifecycle mutations through generic status updates. */
data class OrganisationTransitionCommand(
    val organisationId: UUID,
    val transition: OrganisationLifecycleTransition,
    val command: TransitionCommand = TransitionCommand(),
)

/** Explicit command for a branch lifecycle transition within an organisation. */
data class BranchTransitionCommand(
    val organisationId: UUID,
    val branchId: UUID,
    val transition: BranchLifecycleTransition,
    val command: TransitionCommand = TransitionCommand(),
)

/** Explicit command for a global user lifecycle transition in an organisation workflow. */
data class UserTransitionCommand(
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID? = null,
    val transition: UserLifecycleTransition,
    val command: TransitionCommand = TransitionCommand(),
)

/** Explicit command for an organisation-membership lifecycle transition. */
data class MembershipTransitionCommand(
    val organisationId: UUID,
    val membershipId: UUID,
    val branchId: UUID? = null,
    val transition: MembershipLifecycleTransition,
    val command: TransitionCommand = TransitionCommand(),
)
