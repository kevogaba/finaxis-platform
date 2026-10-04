package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.MembershipDetailResponse
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail

/** Maps a membership detail projection to its public response; shared by tenant and platform. */
internal fun LifecycleMembershipDetail.toResponse() =
    MembershipDetailResponse(
        id = id,
        organisationId = organisationId,
        userId = userId,
        username = username,
        email = email,
        displayName = displayName,
        userStatus = userStatus,
        membershipStatus = membershipStatus,
        membershipType = membershipType,
        primaryBranchId = primaryBranchId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
