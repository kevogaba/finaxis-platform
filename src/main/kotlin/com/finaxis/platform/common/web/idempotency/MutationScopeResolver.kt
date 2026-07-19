package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiJsonCodec
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves durable idempotency scope only from validated server-side request context. */
@Component
class MutationScopeResolver(
    private val apiJsonCodec: ApiJsonCodec,
) {
    /** Resolves selection, platform, or active-tenant scope for one mutation. */
    fun resolve(
        request: HttpServletRequest,
        kind: IdempotencyScopeKind,
    ): IdempotencyScope {
        val organisationId =
            when (kind) {
                IdempotencyScopeKind.ORGANISATION_SELECTION -> requestedOrganisation(request)
                IdempotencyScopeKind.PLATFORM -> reservedPlatformContext()
                IdempotencyScopeKind.TENANT -> activeTenant()
            }
        return IdempotencyScope(organisationId)
    }

    private fun requestedOrganisation(request: HttpServletRequest): UUID {
        val wrapper = request as? BoundedContentCachingRequestWrapper ?: denied()
        val node =
            runCatching {
                apiJsonCodec.mapper.readTree(
                    wrapper.contentAsByteArray,
                )
            }.getOrNull()
        return node
            ?.get("organisation_id")
            ?.asString()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: denied()
    }

    private fun reservedPlatformContext(): UUID {
        val active = RequestContexts.current()?.tenant?.organisationId
        if (active != PlatformOrganisation.ID) denied()
        return PlatformOrganisation.ID
    }

    private fun activeTenant(): UUID = RequestContexts.current()?.tenant?.organisationId ?: denied()

    private fun denied(): Nothing =
        throw ForbiddenOperationException(
            safeDetail = "Active tenant context is required for this mutation.",
        )
}
