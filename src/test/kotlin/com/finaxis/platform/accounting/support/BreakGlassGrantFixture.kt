package com.finaxis.platform.accounting.support

import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Grants and revokes `journal.post_prior_period` the way a tenant administrator would.
 *
 * A **dedicated** role carrying the one code, rather than adding it to `TENANT_ADMIN`, and that is
 * the point rather than tidiness. `AccountingSeparationOfDutiesPolicyTests` asserts that no default
 * bundle intersects `AccountingPermissions.BREAK_GLASS`, so a break-glass grant is by construction
 * something a tenant adds on top; and revoking it must leave the actor's ordinary tenant-admin
 * rights alone, or a suite could not tell "the break-glass gate refused" from "the actor lost every
 * permission it had".
 *
 * [revoke] is the production revocation shape: `RoleManagementService.revokeRoleFromUser` sets
 * `user_role_assignment.status` to `REVOKED` under an optimistic lock and then evicts the
 * membership's cached permissions. Written here as a direct statement rather than through the
 * service because the service needs a request context, an actor holding `user.revoke_role`, and an
 * organisation-scoped permission check of its own - three things that would have to pass before the
 * one statement under test ran, each of them able to explain a failure that has nothing to do with
 * the race.
 */
internal class BreakGlassGrantFixture(
    private val dsl: DSLContext,
) {
    /**
     * Creates a tenant role holding [permissionCode], assigns it to [actorId], and answers the
     * assignment id [revoke] takes back.
     */
    fun grantBreakGlass(
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String = AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
    ): UUID {
        val now = OffsetDateTime.now()
        val roleId =
            dsl
                .insertInto(ROLE)
                .set(ROLE.ORGANISATION_ID, organisationId)
                .set(ROLE.ROLE_CODE, "BREAK_GLASS_${uuidV7()}")
                .set(ROLE.ROLE_NAME, "Break glass")
                .set(ROLE.SYSTEM_ROLE, false)
                .set(ROLE.STATUS, ACTIVE)
                .set(ROLE.CREATED_AT, now)
                .set(ROLE.CREATED_BY, SystemActor.ID)
                .set(ROLE.UPDATED_AT, now)
                .set(ROLE.UPDATED_BY, SystemActor.ID)
                .returning(ROLE.ID)
                .fetchOne()!!
                .id!!
        val permissionId =
            requireNotNull(
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                    .fetchOne(PERMISSION.ID),
            ) { "$permissionCode must exist in the seeded catalogue" }
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.GRANTED_BY, SystemActor.ID)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.CREATED_BY, SystemActor.ID)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_BY, SystemActor.ID)
            .execute()
        return dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, actorId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, TENANT_SCOPE)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, null as UUID?)
            .set(USER_ROLE_ASSIGNMENT.STATUS, ACTIVE)
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .returning(USER_ROLE_ASSIGNMENT.ID)
            .fetchOne()!!
            .id!!
    }

    /**
     * Revokes [assignmentId] at the default isolation level, as every production writer of this row
     * does.
     *
     * `READ COMMITTED` matters to the scenarios that use it: a `SERIALIZABLE` revoker would let SSI
     * abort the posting on its own, and a suite would then pass with or without the locking read
     * the posting side now performs.
     */
    fun revoke(assignmentId: UUID) {
        val updated =
            dsl
                .update(USER_ROLE_ASSIGNMENT)
                .set(USER_ROLE_ASSIGNMENT.STATUS, REVOKED)
                .set(USER_ROLE_ASSIGNMENT.REVOKED_AT, OffsetDateTime.now())
                .set(USER_ROLE_ASSIGNMENT.REVOKED_BY, SystemActor.ID)
                .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, OffsetDateTime.now())
                .set(
                    USER_ROLE_ASSIGNMENT.ROW_VERSION,
                    USER_ROLE_ASSIGNMENT.ROW_VERSION.plus(1),
                ).where(USER_ROLE_ASSIGNMENT.ID.eq(assignmentId))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
                .execute()
        check(updated == 1) { "the revocation must have hit exactly one active assignment" }
    }

    /** The membership id a cache eviction is keyed by, as `RoleManagementService` resolves it. */
    fun membershipId(
        organisationId: UUID,
        actorId: UUID,
    ): UUID =
        requireNotNull(
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
        ) { "the actor must hold a membership in the tenant" }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
        const val TENANT_SCOPE = "TENANT"
    }
}
