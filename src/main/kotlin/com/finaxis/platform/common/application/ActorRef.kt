package com.finaxis.platform.common.application

import java.util.UUID

/**
 * A user as a record names them for "who made this": [id] and [displayName].
 *
 * [member] says whether the user belongs to the organisation the record is in. It decides who may
 * see them: a platform operator who acts for a tenant is not one of its members, so a tenant
 * caller is not shown them (see [shownTo]), while a platform caller sees every maker.
 */
data class ActorRef(
    val id: UUID,
    val displayName: String?,
    val member: Boolean,
)

/** The reference as [platformCaller] may see it: a tenant caller sees only a tenant member. */
fun ActorRef?.shownTo(platformCaller: Boolean): ActorRef? =
    this?.takeIf { platformCaller || member }
