package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.application.port.outbound.BranchSelection
import com.finaxis.platform.iam.application.port.outbound.BranchSelectionPage
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.OrganisationSelection
import com.finaxis.platform.iam.application.port.outbound.OrganisationSelectionPage
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.port.outbound.PrincipalMembership
import com.finaxis.platform.iam.application.port.outbound.PrincipalUser
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import com.finaxis.platform.iam.domain.UserStatus
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL.exists
import org.jooq.impl.DSL.notExists
import org.springframework.stereotype.Component
import java.util.UUID

/** jOOQ adapter for membership selection and branch-assignment reads. */
@Component
class JooqMembershipSelectionLookup(
    private val dsl: DSLContext,
) : MembershipSelectionLookup {
    override fun findBranchSelections(
        membershipId: UUID,
        page: Int,
        size: Int,
    ): BranchSelectionPage {
        val condition =
            USER_ORGANISATION_MEMBERSHIP.ID
                .eq(membershipId)
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(BRANCH.STATUS.eq(ACTIVE))
        val joinedTables =
            USER_ORGANISATION_MEMBERSHIP
                .join(USER_BRANCH_ASSIGNMENT)
                .on(
                    USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(
                        USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    ),
                ).and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(USER_ORGANISATION_MEMBERSHIP.USER_ID))
                .join(BRANCH)
                .on(BRANCH.ORGANISATION_ID.eq(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID))
                .and(BRANCH.ID.eq(USER_BRANCH_ASSIGNMENT.BRANCH_ID))
        val total = dsl.fetchCount(joinedTables, condition).toLong()
        val offset = page.toLong() * size.toLong()
        if (offset >= total) return BranchSelectionPage(emptyList(), total)
        val items =
            dsl
                .select(BRANCH.ID, BRANCH.BRANCH_CODE, BRANCH.BRANCH_NAME, BRANCH.STATUS)
                .from(joinedTables)
                .where(condition)
                .orderBy(BRANCH.BRANCH_CODE.asc(), BRANCH.ID.asc())
                .limit(size)
                .offset(offset.toInt())
                .fetch { record ->
                    BranchSelection(
                        branchId = requireNotNull(record[BRANCH.ID]),
                        branchCode = requireNotNull(record[BRANCH.BRANCH_CODE]),
                        branchName = requireNotNull(record[BRANCH.BRANCH_NAME]),
                        branchStatus = requireNotNull(record[BRANCH.STATUS]),
                    )
                }
        return BranchSelectionPage(items, total)
    }

    override fun findOrganisationSelections(
        userId: UUID,
        page: Int,
        size: Int,
    ): OrganisationSelectionPage {
        val condition = organisationSelectionCondition(userId)
        val joinedTables =
            USER_ORGANISATION_MEMBERSHIP
                .join(ORGANISATION)
                .on(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(ORGANISATION.ID))
        val total = dsl.fetchCount(joinedTables, condition).toLong()
        val offset = page.toLong() * size.toLong()
        if (offset >= total) {
            return OrganisationSelectionPage(emptyList(), total)
        }
        val items =
            dsl
                .select(
                    USER_ORGANISATION_MEMBERSHIP.ID,
                    USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                    ORGANISATION.TENANT_CODE,
                    ORGANISATION.DISPLAY_NAME,
                    ORGANISATION.STATUS,
                ).from(joinedTables)
                .where(condition)
                .orderBy(ORGANISATION.TENANT_CODE.asc(), ORGANISATION.ID.asc())
                .limit(size)
                .offset(offset.toInt())
                .fetch { record ->
                    OrganisationSelection(
                        membershipId = requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                        organisationId =
                            requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID]),
                        tenantCode = requireNotNull(record[ORGANISATION.TENANT_CODE]),
                        displayName = requireNotNull(record[ORGANISATION.DISPLAY_NAME]),
                        organisationStatus =
                            OrganisationStatus.valueOf(requireNotNull(record[ORGANISATION.STATUS])),
                        membershipStatus =
                            MembershipStatus.valueOf(
                                requireNotNull(
                                    record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS],
                                ),
                            ),
                    )
                }
        return OrganisationSelectionPage(items, total)
    }

    private fun organisationSelectionCondition(userId: UUID): Condition =
        USER_ORGANISATION_MEMBERSHIP.USER_ID
            .eq(userId)
            .and(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS.eq(ACTIVE))
            .and(ORGANISATION.STATUS.eq(ACTIVE))
            .and(canSelectOrganisationCondition())
            .and(notExists(organisationSelectionDenyQuery()))

    private fun canSelectOrganisationCondition(): Condition =
        exists(
            dsl
                .selectOne()
                .from(USER_ROLE_ASSIGNMENT)
                .join(ROLE)
                .on(ROLE.ID.eq(USER_ROLE_ASSIGNMENT.ROLE_ID))
                .and(ROLE.ORGANISATION_ID.eq(USER_ROLE_ASSIGNMENT.ORGANISATION_ID))
                .join(ROLE_PERMISSION)
                .on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
                .and(ROLE_PERMISSION.ORGANISATION_ID.eq(ROLE.ORGANISATION_ID))
                .join(PERMISSION)
                .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                .where(
                    USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(
                        USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    ),
                ).and(
                    USER_ROLE_ASSIGNMENT.USER_ID.eq(
                        USER_ORGANISATION_MEMBERSHIP.USER_ID,
                    ),
                ).and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(TENANT_SCOPE))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(ROLE.STATUS.eq(ACTIVE))
                .and(PERMISSION.STATUS.eq(ACTIVE))
                .and(PERMISSION.PERMISSION_CODE.eq(PERM_SELECT_ORG)),
        ).or(
            exists(
                dsl
                    .selectOne()
                    .from(MEMBERSHIP_PERMISSION)
                    .join(PERMISSION)
                    .on(PERMISSION.ID.eq(MEMBERSHIP_PERMISSION.PERMISSION_ID))
                    .where(
                        MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(
                            USER_ORGANISATION_MEMBERSHIP.ID,
                        ),
                    ).and(MEMBERSHIP_PERMISSION.EFFECT.eq(ALLOW))
                    .and(PERMISSION.STATUS.eq(ACTIVE))
                    .and(PERMISSION.PERMISSION_CODE.eq(PERM_SELECT_ORG)),
            ),
        )

    private fun organisationSelectionDenyQuery() =
        dsl
            .selectOne()
            .from(MEMBERSHIP_PERMISSION)
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(MEMBERSHIP_PERMISSION.PERMISSION_ID))
            .where(
                MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(
                    USER_ORGANISATION_MEMBERSHIP.ID,
                ),
            ).and(MEMBERSHIP_PERMISSION.EFFECT.eq(DENY))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .and(PERMISSION.PERMISSION_CODE.eq(PERM_SELECT_ORG))

    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? =
        dsl
            .select(KEYCLOAK_IDENTITY_LINK.USER_ID)
            .from(KEYCLOAK_IDENTITY_LINK)
            .where(KEYCLOAK_IDENTITY_LINK.PROVIDER.eq(KEYCLOAK))
            .and(KEYCLOAK_IDENTITY_LINK.SUBJECT.eq(keycloakSubject))
            .and(KEYCLOAK_IDENTITY_LINK.UNLINKED_AT.isNull)
            .fetchOne(KEYCLOAK_IDENTITY_LINK.USER_ID)

    override fun userStatus(userId: UUID): UserStatus? =
        dsl
            .select(USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne(USER_ACCOUNT.STATUS)
            ?.let(UserStatus::valueOf)

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.USER_ID,
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
            .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .fetchOne { record ->
                MembershipSelection(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.USER_ID]),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID]),
                    MembershipStatus.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                )
            }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationStatus::valueOf)

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> =
        dsl
            .select(USER_BRANCH_ASSIGNMENT.BRANCH_ID)
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
            .and(BRANCH.STATUS.eq(ACTIVE))
            .orderBy(USER_BRANCH_ASSIGNMENT.BRANCH_ID)
            .fetch(USER_BRANCH_ASSIGNMENT.BRANCH_ID)
            .filterNotNull()

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
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
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(BRANCH.STATUS.eq(ACTIVE)),
        )
}

private const val PERM_SELECT_ORG = "auth.select_organisation"
private const val ALLOW = "ALLOW"
private const val DENY = "DENY"

/** jOOQ adapter for active-membership permission resolution. */
@Component
class JooqPermissionResolutionQueries(
    private val dsl: DSLContext,
) : PermissionResolutionQueries {
    override fun membershipStatus(membershipId: UUID): MembershipStatus? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            ?.let(MembershipStatus::valueOf)

    override fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> {
        val scopeCondition =
            if (branchId != null) {
                USER_ROLE_ASSIGNMENT.SCOPE_TYPE
                    .eq(TENANT_SCOPE)
                    .or(
                        USER_ROLE_ASSIGNMENT.SCOPE_TYPE
                            .eq(BRANCH_SCOPE)
                            .and(USER_ROLE_ASSIGNMENT.BRANCH_ID.eq(branchId)),
                    )
            } else {
                USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(TENANT_SCOPE)
            }
        return dsl
            .selectDistinct(PERMISSION.PERMISSION_CODE)
            .from(USER_ROLE_ASSIGNMENT)
            .join(USER_ORGANISATION_MEMBERSHIP)
            .on(
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(
                    USER_ROLE_ASSIGNMENT.ORGANISATION_ID,
                ),
            ).and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ROLE_ASSIGNMENT.USER_ID))
            .join(ROLE)
            .on(ROLE.ID.eq(USER_ROLE_ASSIGNMENT.ROLE_ID))
            .and(ROLE.ORGANISATION_ID.eq(USER_ROLE_ASSIGNMENT.ORGANISATION_ID))
            .join(ROLE_PERMISSION)
            .on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
            .and(ROLE_PERMISSION.ORGANISATION_ID.eq(ROLE.ORGANISATION_ID))
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .and(ROLE.STATUS.eq(ACTIVE))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .and(scopeCondition)
            .fetchSet(PERMISSION.PERMISSION_CODE)
            .filterNotNull()
            .toSet()
    }

    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
        dsl
            .select(PERMISSION.PERMISSION_CODE, MEMBERSHIP_PERMISSION.EFFECT)
            .from(MEMBERSHIP_PERMISSION)
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(MEMBERSHIP_PERMISSION.PERMISSION_ID))
            .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .fetch { record ->
                PermissionEffectAssignment(
                    requireNotNull(record[PERMISSION.PERMISSION_CODE]),
                    PermissionEffect.valueOf(requireNotNull(record[MEMBERSHIP_PERMISSION.EFFECT])),
                )
            }
}

/** jOOQ adapter for secure principal construction from Keycloak identity links. */
@Component
class JooqAppPrincipalLookup(
    private val dsl: DSLContext,
) : AppPrincipalLookup {
    override fun findPrincipalUserByKeycloakSubject(keycloakSubject: String): PrincipalUser? =
        dsl
            .select(
                USER_ACCOUNT.ID,
                KEYCLOAK_IDENTITY_LINK.SUBJECT,
                USER_ACCOUNT.EMAIL,
                USER_ACCOUNT.DISPLAY_NAME,
                USER_ACCOUNT.STATUS,
            ).from(USER_ACCOUNT)
            .join(KEYCLOAK_IDENTITY_LINK)
            .on(KEYCLOAK_IDENTITY_LINK.USER_ID.eq(USER_ACCOUNT.ID))
            .where(KEYCLOAK_IDENTITY_LINK.PROVIDER.eq(KEYCLOAK))
            .and(KEYCLOAK_IDENTITY_LINK.SUBJECT.eq(keycloakSubject))
            .and(KEYCLOAK_IDENTITY_LINK.UNLINKED_AT.isNull)
            .fetchOne { record ->
                PrincipalUser(
                    requireNotNull(record[USER_ACCOUNT.ID]),
                    requireNotNull(record[KEYCLOAK_IDENTITY_LINK.SUBJECT]),
                    record[USER_ACCOUNT.EMAIL],
                    requireNotNull(record[USER_ACCOUNT.DISPLAY_NAME]),
                    UserStatus.valueOf(requireNotNull(record[USER_ACCOUNT.STATUS])),
                )
            }

    override fun findPrincipalMembershipById(membershipId: UUID): PrincipalMembership? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.ID,
                USER_ORGANISATION_MEMBERSHIP.USER_ID,
                USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne { record ->
                PrincipalMembership(
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ID]),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.USER_ID]),
                    requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID]),
                    MembershipStatus.valueOf(
                        requireNotNull(record[USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS]),
                    ),
                )
            }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationStatus::valueOf)

    override fun hasActiveAssignedBranch(
        membershipId: UUID,
        organisationId: UUID,
        branchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
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
                .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
                .and(BRANCH.STATUS.eq(ACTIVE)),
        )
}

private const val ACTIVE = "ACTIVE"
private const val KEYCLOAK = "KEYCLOAK"
private const val TENANT_SCOPE = "TENANT"
private const val BRANCH_SCOPE = "BRANCH"
