package com.finaxis.platform.lifecycle

import java.util.UUID

/**
 * Public lifecycle module API for activating invited users during their first successful login.
 */
interface UserFirstLoginActivation {
    /**
     * Activates an invited user for [organisationId] and refreshes the user's last-login marker.
     *
     * Implementations are idempotent for already-active users.
     */
    fun activateOnFirstLogin(
        userId: UUID,
        organisationId: UUID,
    )
}
