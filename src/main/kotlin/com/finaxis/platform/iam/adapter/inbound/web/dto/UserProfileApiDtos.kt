package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.finaxis.platform.iam.application.port.outbound.PermissionScope
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.profile.UserProfile
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** The authenticated user's profile for the selected organisation and optional branch. */
data class UserProfileResponse(
    val userId: UUID,
    val keycloakSubject: String,
    val email: String?,
    val fullName: String?,
    val organisation: ProfileOrganisation,
    val membership: ProfileMembership,
    val selectedBranch: ProfileBranch?,
    val branches: List<ProfileBranch>,
    val roles: List<ProfileRole>,
    @field:Schema(
        description =
            "Effective permission codes for the current request: tenant-wide grants plus the " +
                "selected branch's grants, sorted.",
    )
    val permissions: List<String>,
    @field:Schema(
        description =
            "Where each permission is held, across every ACTIVE branch rather than only the " +
                "selected one, sorted by code. A code held tenant-wide is reported once as " +
                "TENANT. A code held only on branches is reported as BRANCH with those " +
                "branches. Direct DENY overrides are subtracted, direct ALLOW overrides are " +
                "tenant-wide, and a branch that is not ACTIVE contributes nothing.",
    )
    val permissionScopes: List<PermissionScopeResponse>,
)

/** One permission code and the reach of the caller's grant. */
data class PermissionScopeResponse(
    val code: String,
    @field:Schema(description = "TENANT: every branch. BRANCH: only the branches in branch_ids.")
    val scope: PermissionScope.Scope,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(
        description = "ACTIVE branches the code is held on, ascending. Present only for BRANCH.",
        nullable = true,
    )
    val branchIds: List<UUID>?,
)

internal fun UserProfile.toResponse() =
    UserProfileResponse(
        userId = userId,
        keycloakSubject = keycloakSubject,
        email = email,
        fullName = fullName,
        organisation = organisation,
        membership = membership,
        selectedBranch = selectedBranch,
        branches = branches,
        roles = roles,
        permissions = permissions,
        permissionScopes =
            permissionScopes.map {
                PermissionScopeResponse(it.code, it.scope, it.branchIds)
            },
    )
