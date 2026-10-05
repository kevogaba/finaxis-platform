package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.RolePermissionPersistence
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION_VIEW_REQUIREMENT
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ adapter for the permission catalogue lookups and role-permission grants of role
 * administration, including the catalogue's view requirements the composition rule reads
 * (ADR 0030). [JooqIamAdministrationPersistence] delegates [RolePermissionPersistence] here.
 * Deliberately **not** a Spring bean: a second bean of the port would make injecting the
 * narrow port ambiguous.
 */
class JooqRolePermissionPersistence(
    private val dsl: DSLContext,
    private val clock: Clock,
) : RolePermissionPersistence {
    override fun permissionIdByCode(permissionCode: String): UUID? =
        dsl
            .select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .fetchOne(PERMISSION.ID)

    override fun permissionRiskLevel(permissionCode: String): String? =
        dsl
            .select(PERMISSION.RISK_LEVEL)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .fetchOne(PERMISSION.RISK_LEVEL)

    override fun isActivePermission(permissionCode: String): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .and(PERMISSION.STATUS.eq(ACTIVE)),
        )

    override fun activePermissionCodes(
        organisationId: UUID,
        roleId: UUID,
    ): Set<String> =
        dsl
            .select(PERMISSION.PERMISSION_CODE)
            .from(ROLE_PERMISSION)
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
            .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
            .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .fetch(PERMISSION.PERMISSION_CODE)
            .filterNotNull()
            .toSet()

    override fun requiredViewCodes(permissionCodes: Collection<String>): Map<String, Set<String>> {
        if (permissionCodes.isEmpty()) return emptyMap()
        val mutation = PERMISSION.`as`("mutation")
        val view = PERMISSION.`as`("required_view")
        return dsl
            .select(mutation.PERMISSION_CODE, view.PERMISSION_CODE)
            .from(PERMISSION_VIEW_REQUIREMENT)
            .join(mutation)
            .on(mutation.ID.eq(PERMISSION_VIEW_REQUIREMENT.PERMISSION_ID))
            .join(view)
            .on(view.ID.eq(PERMISSION_VIEW_REQUIREMENT.REQUIRED_VIEW_PERMISSION_ID))
            .where(mutation.PERMISSION_CODE.`in`(permissionCodes))
            .fetch()
            .groupBy(
                { requireNotNull(it.get(mutation.PERMISSION_CODE)) },
                { requireNotNull(it.get(view.PERMISSION_CODE)) },
            ).mapValues { it.value.toSet() }
    }

    override fun grantPermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean {
        val now = now()
        return dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.GRANTED_BY, actorId)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.CREATED_BY, actorId)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_BY, actorId)
            .onConflict(
                ROLE_PERMISSION.ORGANISATION_ID,
                ROLE_PERMISSION.ROLE_ID,
                ROLE_PERMISSION.PERMISSION_ID,
            ).doNothing()
            .execute() > 0
    }

    override fun grantedPermissionCode(
        organisationId: UUID,
        roleId: UUID,
        rolePermissionId: UUID,
    ): String? =
        dsl
            .select(PERMISSION.PERMISSION_CODE)
            .from(ROLE_PERMISSION)
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
            .where(ROLE_PERMISSION.ID.eq(rolePermissionId))
            .and(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
            .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
            .fetchOne(PERMISSION.PERMISSION_CODE)

    override fun removePermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
        actorId: UUID,
    ): Boolean =
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ORGANISATION_ID.eq(organisationId))
            .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
            .and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId))
            .execute() > 0

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    private companion object {
        const val ACTIVE = "ACTIVE"
    }
}
