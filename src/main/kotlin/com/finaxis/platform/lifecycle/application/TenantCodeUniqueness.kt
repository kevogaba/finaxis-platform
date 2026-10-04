package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import org.springframework.dao.DuplicateKeyException
import java.util.UUID

internal const val TENANT_CODE_TAKEN_DETAIL = "A tenant with that tenant code already exists."

/**
 * Refuses a tenant code another organisation holds. A courtesy, not the check: two concurrent
 * requests can both see the code free, so the write is also wrapped in
 * [translatingDuplicateTenantCode] and `uq_organisation_tenant_code` stays the authority.
 */
internal fun OrganisationQueryStore.requireTenantCodeFree(
    tenantCode: String,
    except: UUID?,
) {
    val holder = findByCode(tenantCode)
    if (holder != null && holder.organisationId != except) {
        throw ConflictException(safeDetail = TENANT_CODE_TAKEN_DETAIL)
    }
}

/** Runs an organisation write, turning a lost race on the tenant code into the 409 above. */
internal fun <T> translatingDuplicateTenantCode(write: () -> T): T =
    try {
        write()
    } catch (ex: DuplicateKeyException) {
        throw ConflictException(safeDetail = TENANT_CODE_TAKEN_DETAIL, cause = ex)
    }
