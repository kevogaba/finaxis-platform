package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.application.ActorRef
import java.util.UUID

/** Who made a record, as the maker fields of the approvable resources show them. */
data class ActorRefResponse(
    val id: UUID,
    val displayName: String?,
)

/** The public form of a maker, or null when there is none to show. */
fun ActorRef?.toResponse(): ActorRefResponse? =
    this?.let {
        ActorRefResponse(it.id, it.displayName)
    }
