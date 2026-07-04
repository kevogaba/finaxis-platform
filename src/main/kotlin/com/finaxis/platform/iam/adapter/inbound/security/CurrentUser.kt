package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.AppPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Inbound adapter helper for reading the current application principal from Spring Security.
 */
@Component
class CurrentUser {
    fun principal(): AppPrincipal {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal is AppPrincipal) {
            return principal
        }
        throw AccessDeniedException("No active application principal is available")
    }
}
