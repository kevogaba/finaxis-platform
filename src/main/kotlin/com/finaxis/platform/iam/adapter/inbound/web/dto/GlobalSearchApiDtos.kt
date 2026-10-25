package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Tenant global search response (ADR 0039): one bounded array per type, at most 10 hits each, in
 * the order of that type's own list. A key is **omitted**, not empty, for a type the caller may
 * not view; an empty array means viewable with no match. There is no count and no `has_more`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class GlobalSearchResponse(
    @field:Schema(description = "Present only with user.view.")
    val users: List<UserSearchHitResponse>?,
    @field:Schema(description = "Present only with branch.view (on at least one active branch).")
    val branches: List<BranchSearchHitResponse>?,
    @field:Schema(description = "Present only with role.view. Archived roles are never hits.")
    val roles: List<RoleSearchHitResponse>?,
    @field:Schema(description = "Present only with membership.view.")
    val memberships: List<MembershipSearchHitResponse>?,
)

/** A user hit: [id] is the user id, [label] the display name, [status] the user status. */
data class UserSearchHitResponse(
    val id: UUID,
    val label: String,
    val status: String,
    val email: String,
)

/** A branch hit: [label] is the branch name, [status] the branch status. */
data class BranchSearchHitResponse(
    val id: UUID,
    val label: String,
    val status: String,
    val branchCode: String,
)

/** A role hit: [label] is the role name, [status] the role status. */
data class RoleSearchHitResponse(
    val id: UUID,
    val label: String,
    val status: String,
    val roleCode: String,
)

/**
 * A membership hit: [id] is the membership id (not the user's), [label] the member's display name
 * and [status] the membership status.
 */
data class MembershipSearchHitResponse(
    val id: UUID,
    val label: String,
    val status: String,
    val membershipType: String,
)
