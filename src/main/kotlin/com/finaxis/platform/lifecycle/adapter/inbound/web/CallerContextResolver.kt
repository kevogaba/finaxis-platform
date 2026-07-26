package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Holder for contextual details of the calling user.
 */
internal data class CallerContext(
    val userId: UUID,
    val organisationId: UUID,
    val branchId: UUID?,
)

/**
 * Resolver for retrieving authenticated caller information from request context.
 */
internal object CallerContextResolver {
    /**
     * Resolves the caller context from thread-bound request context or security context.
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun resolveCallerContext(): CallerContext? {
        val currentRequest = RequestContexts.current()
        if (currentRequest?.actor?.userId != null &&
            currentRequest.tenant?.organisationId != null
        ) {
            return CallerContext(
                userId = currentRequest.actor.userId,
                organisationId = currentRequest.tenant.organisationId,
                branchId = currentRequest.branch?.branchId,
            )
        }

        val auth = SecurityContextHolder.getContext().authentication ?: return null
        val principal = auth.principal ?: return null

        return try {
            val userId = getProperty<UUID>(principal, "userId") ?: return null
            val orgId = getProperty<UUID>(principal, "organisationId") ?: return null
            val branchId = getProperty<UUID>(principal, "branchId")
            CallerContext(userId = userId, organisationId = orgId, branchId = branchId)
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: ClassCastException) {
            null
        }
    }

    /**
     * Obtains the caller with platform administrative context.
     */
    fun getPlatformCaller(): PlatformCaller {
        val context =
            resolveCallerContext()
                ?: throw ForbiddenOperationException(
                    "Reserved platform organisation context is required for this route.",
                )
        if (context.organisationId != PlatformOrganisation.ID) {
            throw ForbiddenOperationException(
                "Reserved platform organisation context is required for this route.",
            )
        }
        return PlatformCaller(
            actorId = context.userId,
            platformOrganisationId = context.organisationId,
        )
    }

    /**
     * Obtains the caller with active tenant context.
     */
    fun getTenantCaller(): TenantCaller {
        val context =
            resolveCallerContext()
                ?: throw ForbiddenOperationException(
                    "Active tenant context is required for this route.",
                )
        if (context.organisationId == PlatformOrganisation.ID) {
            throw ForbiddenOperationException(
                "Branch operations are restricted to non-platform tenant context.",
            )
        }
        return TenantCaller(
            actorId = context.userId,
            activeOrganisationId = context.organisationId,
            activeBranchId = context.branchId,
        )
    }

    private inline fun <reified T> getProperty(
        target: Any,
        propertyName: String,
    ): T? {
        val getterName = "get" + propertyName.replaceFirstChar { it.uppercase() }
        val member =
            target.javaClass.methods.firstOrNull {
                it.name == getterName || it.name == propertyName
            } ?: return null
        return member.invoke(target) as? T
    }
}
