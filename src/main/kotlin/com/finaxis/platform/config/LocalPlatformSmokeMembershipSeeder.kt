package com.finaxis.platform.config

import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

private const val LOCAL_PROFILE_EXPRESSION = "local & !production"

/**
 * Seeds the platform privileges required by the local smoke path only when the explicit local
 * profile is active.
 */
@Component
@Profile(LOCAL_PROFILE_EXPRESSION)
class LocalPlatformSmokeMembershipSeeder(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ApplicationRunner {
    /** Creates the fixed local platform membership and role assignment when either is absent. */
    @Transactional
    override fun run(args: ApplicationArguments) {
        val now = OffsetDateTime.now(clock)
        seedMembership(now)
        seedPlatformSuperAdministratorRole(now)
    }

    private fun seedMembership(now: OffsetDateTime) {
        if (membershipExists()) {
            return
        }

        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, LOCAL_MEMBERSHIP_ID)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, PLATFORM_ORGANISATION_ID)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, LOCAL_ADMIN_USER_ID)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, ACTIVE)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, ADMIN)
            .set(USER_ORGANISATION_MEMBERSHIP.JOINED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun seedPlatformSuperAdministratorRole(now: OffsetDateTime) {
        if (platformSuperAdministratorRoleExists()) {
            return
        }

        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, LOCAL_PLATFORM_ROLE_ASSIGNMENT_ID)
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, PLATFORM_ORGANISATION_ID)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, LOCAL_ADMIN_USER_ID)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, PLATFORM_SUPER_ADMIN_ROLE_ID)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, TENANT)
            .set(USER_ROLE_ASSIGNMENT.STATUS, ACTIVE)
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun membershipExists(): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(PLATFORM_ORGANISATION_ID))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(LOCAL_ADMIN_USER_ID)),
        )

    private fun platformSuperAdministratorRoleExists(): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(PLATFORM_ORGANISATION_ID))
                .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(LOCAL_ADMIN_USER_ID))
                .and(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(PLATFORM_SUPER_ADMIN_ROLE_ID))
                .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(TENANT))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val ADMIN = "ADMIN"
        const val TENANT = "TENANT"

        val PLATFORM_ORGANISATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val LOCAL_ADMIN_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_MEMBERSHIP_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val PLATFORM_SUPER_ADMIN_ROLE_ID: UUID =
            UUID.fromString("50000000-0000-0000-0000-000000000001")
        val LOCAL_PLATFORM_ROLE_ASSIGNMENT_ID: UUID =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    }
}
