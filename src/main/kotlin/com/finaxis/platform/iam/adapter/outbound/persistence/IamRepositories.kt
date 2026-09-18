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
import org.springframework.transaction.support.TransactionSynchronizationManager
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

    /**
     * The same rule as the three reads above, for one code, from rows this transaction holds.
     *
     * Three statements rather than one, and the reason is not that one is impossible. PostgreSQL
     * refuses `FOR SHARE` on a `SELECT DISTINCT`, which is why `rolePermissionCodes`' shape could
     * not simply gain it - but it *does* honour `FOR SHARE` inside a sub-`SELECT`, measured on
     * `postgres:18.4`, so a single `EXISTS`-shaped statement would have locked correctly. What
     * three buys is that they mirror `EffectivePermissionResolver.resolve`'s three reads one for
     * one, so the rule is visibly the same rule, and that the short-circuit below can leave rows
     * unlocked that the answer does not rest on.
     *
     * Short-circuiting is deliberate and matches `EffectivePermissionResolver.resolve`, where the
     * effective set is `allowed - denied`: a direct `DENY` settles the question, then a direct
     * `ALLOW`, and only an undecided code is looked for among the role grants. A code settled by a
     * direct row therefore leaves the role rows unlocked, which is correct - those rows are not
     * what the answer rests on, and locking them would abort postings for revocations that could
     * not have changed the outcome.
     *
     * **Two things it deliberately does not close.**
     *
     * An *inserted* `DENY` row is a phantom: a lock is taken on rows that exist, and above
     * `READ COMMITTED` a row inserted after the snapshot is not in it, so a `DENY` inserted
     * mid-posting is not seen. Today that is unreachable - no production code writes
     * `membership_permission` at all - and a writer of that table is what would make it reachable,
     * which means revisiting ADR 0026.
     *
     * A **catalogue** deactivation is not linearized either, and that is why every statement below
     * names its `OF` list rather than taking the bare lock. `permission` is global reference data
     * seeded by `V2`/`V5`: a bare `FOR SHARE` on these joins would lock its row too, so every
     * backdated posting in every tenant would contend on one row per code, and one catalogue edit
     * would abort backdated postings platform-wide. Deactivating a code is a platform-wide
     * reference-data change rather than a tenant's revocation, and it is read here without being
     * held. What is held is exactly what a *tenant* can revoke.
     *
     * Everything a tenant can revoke is caught: an `UPDATE` of `user_role_assignment`, `role` or
     * the membership row, or a `DELETE` from `role_permission`. Measured on `postgres:18.4`, both
     * the update and the delete arrive as `40001`.
     */
    override fun lockedBreakGlassGrant(
        membershipId: UUID,
        permissionCode: String,
    ): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A break-glass permission check takes shared row locks that must be held until the " +
                "caller's transaction ends, so it requires an active transaction."
        }
        if (lockedMembershipStatus(membershipId) != MembershipStatus.ACTIVE) return false
        val direct = lockedDirectEffects(membershipId, permissionCode)
        return when {
            PermissionEffect.DENY in direct -> false
            PermissionEffect.ALLOW in direct -> true
            else -> hasLockedRoleGrant(membershipId, permissionCode)
        }
    }

    private fun lockedMembershipStatus(membershipId: UUID): MembershipStatus? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .forShare()
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            ?.let(MembershipStatus::valueOf)

    private fun lockedDirectEffects(
        membershipId: UUID,
        permissionCode: String,
    ): Set<PermissionEffect> =
        dsl
            .select(MEMBERSHIP_PERMISSION.EFFECT)
            .from(MEMBERSHIP_PERMISSION)
            .join(PERMISSION)
            .on(PERMISSION.ID.eq(MEMBERSHIP_PERMISSION.PERMISSION_ID))
            .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
            .and(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .forShare()
            .of(MEMBERSHIP_PERMISSION)
            .fetch(MEMBERSHIP_PERMISSION.EFFECT)
            .filterNotNull()
            .map(PermissionEffect::valueOf)
            .toSet()

    /**
     * Tenant-scoped role grants only, matching the `branchId = null` resolution a break-glass check
     * performs: a branch-scoped grant applies while that branch is selected, and a ledger control
     * is not decided by which branch a request happened to pick.
     *
     * Every matching row is locked, not merely the first. Two grants of one code through two roles
     * are two independent reasons the answer is `true`, and stopping at one would leave the other
     * free to be revoked underneath this posting. Locking both costs a spurious abort when only one
     * is revoked - the retry then re-reads and grants - which is the safe direction to be wrong in.
     *
     * The `OF` list is the three tenant-scoped tables and nothing else. `permission` is global and
     * deliberately excluded (see the caller's KDoc); `user_organisation_membership` is already held
     * by [lockedMembershipStatus], so naming it again here would only widen this statement's lock
     * footprint for a row this transaction holds anyway.
     */
    private fun hasLockedRoleGrant(
        membershipId: UUID,
        permissionCode: String,
    ): Boolean =
        dsl
            .select(ROLE_PERMISSION.ROLE_ID)
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
            .and(PERMISSION.PERMISSION_CODE.eq(permissionCode))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .and(ROLE.STATUS.eq(ACTIVE))
            .and(PERMISSION.STATUS.eq(ACTIVE))
            .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq(TENANT_SCOPE))
            .forShare()
            .of(USER_ROLE_ASSIGNMENT, ROLE, ROLE_PERMISSION)
            .fetch()
            .isNotEmpty
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
