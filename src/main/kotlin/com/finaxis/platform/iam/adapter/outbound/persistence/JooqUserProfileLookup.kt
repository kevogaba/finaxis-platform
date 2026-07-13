package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/** jOOQ adapter for authenticated profile read models. */
@Component
class JooqUserProfileLookup(
    private val dsl: DSLContext,
) : UserProfileLookup {
    override fun organisation(organisationId: UUID): ProfileOrganisation? =
        dsl
            .select(
                ORGANISATION.ID,
                ORGANISATION.TENANT_CODE,
                ORGANISATION.DISPLAY_NAME,
                ORGANISATION.STATUS,
            ).from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne { record ->
                ProfileOrganisation(
                    requireNotNull(record[ORGANISATION.ID]),
                    requireNotNull(record[ORGANISATION.TENANT_CODE]),
                    requireNotNull(record[ORGANISATION.DISPLAY_NAME]),
                    OrganisationStatus.valueOf(requireNotNull(record[ORGANISATION.STATUS])),
                )
            }

    override fun membership(membershipId: UUID): ProfileMembership? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.ID, USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne { record ->
                ProfileMembership(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    MembershipStatus.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                )
            }

    override fun assignedBranches(membershipId: UUID): List<ProfileBranch> =
        dsl
            .select(BRANCH.ID, BRANCH.BRANCH_CODE, BRANCH.BRANCH_NAME, BRANCH.STATUS)
            .from(USER_BRANCH_ASSIGNMENT)
            .join(USER_ORGANISATION_MEMBERSHIP)
            .on(
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(
                    USER_BRANCH_ASSIGNMENT.ORGANISATION_ID,
                ),
            ).and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_BRANCH_ASSIGNMENT.USER_ID))
            .join(BRANCH)
            .on(BRANCH.ORGANISATION_ID.eq(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID))
            .and(BRANCH.ID.eq(USER_BRANCH_ASSIGNMENT.BRANCH_ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
            .orderBy(BRANCH.BRANCH_CODE)
            .fetch()
            .map { record ->
                ProfileBranch(
                    requireNotNull(record[BRANCH.ID]),
                    requireNotNull(record[BRANCH.BRANCH_CODE]),
                    requireNotNull(record[BRANCH.BRANCH_NAME]),
                    requireNotNull(record[BRANCH.STATUS]),
                )
            }

    override fun assignedRoles(membershipId: UUID): List<ProfileRole> =
        dsl
            .select(ROLE.ID, ROLE.ROLE_CODE, ROLE.ROLE_NAME, ROLE.STATUS)
            .from(USER_ROLE_ASSIGNMENT)
            .join(USER_ORGANISATION_MEMBERSHIP)
            .on(
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(
                    USER_ROLE_ASSIGNMENT.ORGANISATION_ID,
                ),
            ).and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ROLE_ASSIGNMENT.USER_ID))
            .join(ROLE)
            .on(ROLE.ORGANISATION_ID.eq(USER_ROLE_ASSIGNMENT.ORGANISATION_ID))
            .and(ROLE.ID.eq(USER_ROLE_ASSIGNMENT.ROLE_ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .orderBy(ROLE.ROLE_CODE)
            .fetch()
            .map { record ->
                ProfileRole(
                    requireNotNull(record[ROLE.ID]),
                    requireNotNull(record[ROLE.ROLE_CODE]),
                    requireNotNull(record[ROLE.ROLE_NAME]),
                    RoleStatus.valueOf(requireNotNull(record[ROLE.STATUS])),
                )
            }
}

private const val ACTIVE = "ACTIVE"
