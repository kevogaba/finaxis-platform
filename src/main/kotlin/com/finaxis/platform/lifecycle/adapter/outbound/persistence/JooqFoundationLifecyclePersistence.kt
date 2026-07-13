package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionLog
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.FoundationLifecycleReader
import com.finaxis.platform.lifecycle.application.FoundationLifecycleWriter
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.LifecyclePrerequisites
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL/jOOQ lifecycle persistence adapter. Tenant-owned records are always read with their
 * organisation id, and the same transaction writes state and transition-log rows.
 */
@Component
class JooqFoundationLifecyclePersistence(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : FoundationLifecycleReader,
    FoundationLifecycleWriter,
    LifecyclePrerequisites,
    TransitionLogRepository {
    override fun findOrganisation(id: UUID): LifecycleAggregate<OrganisationLifecycleState>? =
        dsl
            .select(ORGANISATION.STATUS, ORGANISATION.ROW_VERSION)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(id))
            .fetchOne()
            ?.let { row ->
                LifecycleAggregate(
                    id,
                    OrganisationLifecycleState.valueOf(row.value1()!!),
                    ORGANISATION_TYPE,
                    organisationId = id,
                    rowVersion = row.value2(),
                )
            }

    override fun findBranch(
        organisationId: UUID,
        branchId: UUID,
    ): LifecycleAggregate<BranchLifecycleState>? =
        dsl
            .select(BRANCH.STATUS, BRANCH.ROW_VERSION)
            .from(BRANCH)
            .where(BRANCH.ORGANISATION_ID.eq(organisationId))
            .and(BRANCH.ID.eq(branchId))
            .fetchOne()
            ?.let { row ->
                LifecycleAggregate(
                    branchId,
                    BranchLifecycleState.valueOf(row.value1()!!),
                    BRANCH_TYPE,
                    organisationId = organisationId,
                    rowVersion = row.value2(),
                )
            }

    override fun findUser(userId: UUID): LifecycleAggregate<UserLifecycleState>? =
        dsl
            .select(USER_ACCOUNT.STATUS, USER_ACCOUNT.ROW_VERSION)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne()
            ?.let { row ->
                LifecycleAggregate(
                    userId,
                    UserLifecycleState.valueOf(row.value1()!!),
                    USER_TYPE,
                    rowVersion = row.value2(),
                )
            }

    override fun findMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleAggregate<MembershipLifecycleState>? =
        dsl
            .select(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                USER_ORGANISATION_MEMBERSHIP.ROW_VERSION,
            ).from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne()
            ?.let { row ->
                LifecycleAggregate(
                    membershipId,
                    MembershipLifecycleState.valueOf(row.value1()!!),
                    MEMBERSHIP_TYPE,
                    organisationId = organisationId,
                    rowVersion = row.value2(),
                )
            }

    override fun membershipUserId(
        organisationId: UUID,
        membershipId: UUID,
    ): UUID? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.USER_ID)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
            .and(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.USER_ID)

    override fun saveOrganisation(
        aggregate: LifecycleAggregate<OrganisationLifecycleState>,
    ): LifecycleAggregate<OrganisationLifecycleState> {
        val expectedVersion = requireExpectedVersion(aggregate)
        val updated =
            dsl
                .update(ORGANISATION)
                .set(ORGANISATION.STATUS, aggregate.state.name)
                .set(ORGANISATION.UPDATED_AT, now())
                .set(ORGANISATION.UPDATED_BY, actorId())
                .set(ORGANISATION.ROW_VERSION, ORGANISATION.ROW_VERSION.plus(1))
                .where(ORGANISATION.ID.eq(UUID.fromString(aggregate.aggregateId)))
                .and(ORGANISATION.ROW_VERSION.eq(expectedVersion))
                .execute()
        requireUpdated(updated, ORGANISATION_TYPE, aggregate.aggregateId)
        return aggregate
    }

    override fun saveBranch(
        aggregate: LifecycleAggregate<BranchLifecycleState>,
    ): LifecycleAggregate<BranchLifecycleState> {
        val expectedVersion = requireExpectedVersion(aggregate)
        val organisationId = requireExpectedOrganisationId(aggregate)
        val updated =
            dsl
                .update(BRANCH)
                .set(BRANCH.STATUS, aggregate.state.name)
                .set(BRANCH.UPDATED_AT, now())
                .set(BRANCH.UPDATED_BY, actorId())
                .set(BRANCH.ROW_VERSION, BRANCH.ROW_VERSION.plus(1))
                .where(BRANCH.ID.eq(UUID.fromString(aggregate.aggregateId)))
                .and(BRANCH.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH.ROW_VERSION.eq(expectedVersion))
                .execute()
        requireUpdated(updated, BRANCH_TYPE, aggregate.aggregateId)
        return aggregate
    }

    override fun saveUser(
        aggregate: LifecycleAggregate<UserLifecycleState>,
    ): LifecycleAggregate<UserLifecycleState> {
        val expectedVersion = requireExpectedVersion(aggregate)
        val updated =
            dsl
                .update(USER_ACCOUNT)
                .set(USER_ACCOUNT.STATUS, aggregate.state.name)
                .set(USER_ACCOUNT.UPDATED_AT, now())
                .set(USER_ACCOUNT.UPDATED_BY, actorId())
                .set(USER_ACCOUNT.ROW_VERSION, USER_ACCOUNT.ROW_VERSION.plus(1))
                .where(USER_ACCOUNT.ID.eq(UUID.fromString(aggregate.aggregateId)))
                .and(USER_ACCOUNT.ROW_VERSION.eq(expectedVersion))
                .execute()
        requireUpdated(updated, USER_TYPE, aggregate.aggregateId)
        return aggregate
    }

    override fun saveMembership(
        aggregate: LifecycleAggregate<MembershipLifecycleState>,
    ): LifecycleAggregate<MembershipLifecycleState> {
        val expectedVersion = requireExpectedVersion(aggregate)
        val organisationId = requireExpectedOrganisationId(aggregate)
        val updated =
            dsl
                .update(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, aggregate.state.name)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now())
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_BY, actorId())
                .set(
                    USER_ORGANISATION_MEMBERSHIP.ROW_VERSION,
                    USER_ORGANISATION_MEMBERSHIP.ROW_VERSION.plus(1),
                ).where(USER_ORGANISATION_MEMBERSHIP.ID.eq(UUID.fromString(aggregate.aggregateId)))
                .and(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.ROW_VERSION.eq(expectedVersion))
                .execute()
        requireUpdated(updated, MEMBERSHIP_TYPE, aggregate.aggregateId)
        return aggregate
    }

    override fun revokeActiveAssignments(
        organisationId: UUID,
        userId: UUID,
    ) {
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, REVOKED)
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_AT, now())
            .set(USER_BRANCH_ASSIGNMENT.REVOKED_BY, actorId())
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now())
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_BY, actorId())
            .set(USER_BRANCH_ASSIGNMENT.ROW_VERSION, USER_BRANCH_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE))
            .execute()
        dsl
            .update(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.STATUS, REVOKED)
            .set(USER_ROLE_ASSIGNMENT.REVOKED_AT, now())
            .set(USER_ROLE_ASSIGNMENT.REVOKED_BY, actorId())
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now())
            .set(USER_ROLE_ASSIGNMENT.UPDATED_BY, actorId())
            .set(USER_ROLE_ASSIGNMENT.ROW_VERSION, USER_ROLE_ASSIGNMENT.ROW_VERSION.plus(1))
            .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
            .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
            .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE))
            .execute()
    }

    override fun organisationState(organisationId: UUID): OrganisationLifecycleState? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(organisationId))
            .fetchOne(ORGANISATION.STATUS)
            ?.let(OrganisationLifecycleState::valueOf)

    override fun branchHasActiveAssignments(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    override fun userHasKeycloakIdentity(userId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(KEYCLOAK_IDENTITY_LINK)
                .where(KEYCLOAK_IDENTITY_LINK.USER_ID.eq(userId))
                .and(KEYCLOAK_IDENTITY_LINK.UNLINKED_AT.isNull),
        )

    override fun userState(userId: UUID): UserLifecycleState? =
        dsl
            .select(USER_ACCOUNT.STATUS)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(userId))
            .fetchOne(USER_ACCOUNT.STATUS)
            ?.let(UserLifecycleState::valueOf)

    override fun membershipHasActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    override fun membershipHasActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(organisationId))
                .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq(ACTIVE)),
        )

    override fun save(log: TransitionLog) {
        val organisationId =
            UUID.fromString(
                requireNotNull(log.metadata[ORGANISATION_ID]) as String,
            )
        val branchId = (log.metadata[BRANCH_ID] as String?)?.let(UUID::fromString)
        when (log.aggregateType) {
            ORGANISATION_TYPE -> {
                saveOrganisationLog(dsl, clock, objectMapper, log, organisationId)
            }

            BRANCH_TYPE -> {
                saveBranchLog(
                    dsl,
                    clock,
                    objectMapper,
                    log,
                    organisationId,
                    requireNotNull(branchId),
                )
            }

            USER_TYPE -> {
                saveUserLog(dsl, clock, objectMapper, log, organisationId, branchId)
            }

            MEMBERSHIP_TYPE -> {
                saveMembershipLog(dsl, clock, objectMapper, log, organisationId, branchId)
            }

            else -> {
                error("Unsupported lifecycle aggregate type: ${log.aggregateType}")
            }
        }
    }

    private fun now(): java.time.OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun actorId(): UUID = RequestContexts.actor()?.userId ?: SystemActor.ID

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
        const val ORGANISATION_ID = "organisationId"
        const val BRANCH_ID = "branchId"
        const val ORGANISATION_TYPE = "ORGANISATION"
        const val BRANCH_TYPE = "BRANCH"
        const val USER_TYPE = "USER_ACCOUNT"
        const val MEMBERSHIP_TYPE = "MEMBERSHIP"
    }
}

private fun saveOrganisationLog(
    dsl: DSLContext,
    clock: Clock,
    objectMapper: ObjectMapper,
    log: TransitionLog,
    organisationId: UUID,
) {
    val now = clock.instant().atOffset(ZoneOffset.UTC)
    dsl
        .insertInto(ORGANISATION_TRANSITION_LOG)
        .set(ORGANISATION_TRANSITION_LOG.ID, UUID.randomUUID())
        .set(ORGANISATION_TRANSITION_LOG.ORGANISATION_ID, organisationId)
        .set(ORGANISATION_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
        .set(ORGANISATION_TRANSITION_LOG.TRANSITION_NAME, log.transition)
        .set(ORGANISATION_TRANSITION_LOG.STATUS_FROM, log.fromState)
        .set(ORGANISATION_TRANSITION_LOG.STATUS_TO, log.toState)
        .set(ORGANISATION_TRANSITION_LOG.CREATED_AT, log.createdAt.atOffset(ZoneOffset.UTC))
        .set(ORGANISATION_TRANSITION_LOG.CREATED_BY, log.actorId.toUuidOrNull())
        .set(ORGANISATION_TRANSITION_LOG.UPDATED_AT, now)
        .set(
            ORGANISATION_TRANSITION_LOG.UPDATED_BY,
            RequestContexts.actor()?.userId ?: SystemActor.ID,
        ).set(
            ORGANISATION_TRANSITION_LOG.METADATA_JSONB,
            JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
        ).execute()
}

private fun saveBranchLog(
    dsl: DSLContext,
    clock: Clock,
    objectMapper: ObjectMapper,
    log: TransitionLog,
    organisationId: UUID,
    branchId: UUID,
) {
    val now = clock.instant().atOffset(ZoneOffset.UTC)
    dsl
        .insertInto(BRANCH_TRANSITION_LOG)
        .set(BRANCH_TRANSITION_LOG.ID, UUID.randomUUID())
        .set(BRANCH_TRANSITION_LOG.ORGANISATION_ID, organisationId)
        .set(BRANCH_TRANSITION_LOG.BRANCH_ID, branchId)
        .set(BRANCH_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
        .set(BRANCH_TRANSITION_LOG.TRANSITION_NAME, log.transition)
        .set(BRANCH_TRANSITION_LOG.STATUS_FROM, log.fromState)
        .set(BRANCH_TRANSITION_LOG.STATUS_TO, log.toState)
        .set(BRANCH_TRANSITION_LOG.CREATED_AT, log.createdAt.atOffset(ZoneOffset.UTC))
        .set(BRANCH_TRANSITION_LOG.CREATED_BY, log.actorId.toUuidOrNull())
        .set(BRANCH_TRANSITION_LOG.UPDATED_AT, now)
        .set(
            BRANCH_TRANSITION_LOG.UPDATED_BY,
            RequestContexts.actor()?.userId ?: SystemActor.ID,
        ).set(
            BRANCH_TRANSITION_LOG.METADATA_JSONB,
            JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
        ).execute()
}

private fun saveUserLog(
    dsl: DSLContext,
    clock: Clock,
    objectMapper: ObjectMapper,
    log: TransitionLog,
    organisationId: UUID,
    branchId: UUID?,
) {
    val now = clock.instant().atOffset(ZoneOffset.UTC)
    dsl
        .insertInto(USER_ACCOUNT_TRANSITION_LOG)
        .set(USER_ACCOUNT_TRANSITION_LOG.ID, UUID.randomUUID())
        .set(USER_ACCOUNT_TRANSITION_LOG.ORGANISATION_ID, organisationId)
        .set(USER_ACCOUNT_TRANSITION_LOG.BRANCH_ID, branchId)
        .set(USER_ACCOUNT_TRANSITION_LOG.ENTITY_ID, UUID.fromString(log.aggregateId))
        .set(USER_ACCOUNT_TRANSITION_LOG.TRANSITION_NAME, log.transition)
        .set(USER_ACCOUNT_TRANSITION_LOG.STATUS_FROM, log.fromState)
        .set(USER_ACCOUNT_TRANSITION_LOG.STATUS_TO, log.toState)
        .set(USER_ACCOUNT_TRANSITION_LOG.CREATED_AT, log.createdAt.atOffset(ZoneOffset.UTC))
        .set(USER_ACCOUNT_TRANSITION_LOG.CREATED_BY, log.actorId.toUuidOrNull())
        .set(USER_ACCOUNT_TRANSITION_LOG.UPDATED_AT, now)
        .set(
            USER_ACCOUNT_TRANSITION_LOG.UPDATED_BY,
            RequestContexts.actor()?.userId ?: SystemActor.ID,
        ).set(
            USER_ACCOUNT_TRANSITION_LOG.METADATA_JSONB,
            JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
        ).execute()
}

private fun saveMembershipLog(
    dsl: DSLContext,
    clock: Clock,
    objectMapper: ObjectMapper,
    log: TransitionLog,
    organisationId: UUID,
    branchId: UUID?,
) {
    val now = clock.instant().atOffset(ZoneOffset.UTC)
    dsl
        .insertInto(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG)
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ID, UUID.randomUUID())
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ORGANISATION_ID, organisationId)
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.BRANCH_ID, branchId)
        .set(
            USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ENTITY_ID,
            UUID.fromString(log.aggregateId),
        ).set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.TRANSITION_NAME, log.transition)
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.STATUS_FROM, log.fromState)
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.STATUS_TO, log.toState)
        .set(
            USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.CREATED_AT,
            log.createdAt.atOffset(ZoneOffset.UTC),
        ).set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.CREATED_BY, log.actorId.toUuidOrNull())
        .set(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.UPDATED_AT, now)
        .set(
            USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.UPDATED_BY,
            RequestContexts.actor()?.userId ?: SystemActor.ID,
        ).set(
            USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.METADATA_JSONB,
            JSONB.jsonb(objectMapper.writeValueAsString(log.metadata)),
        ).execute()
}

private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()

private fun <S : Enum<S>> requireExpectedVersion(aggregate: LifecycleAggregate<S>): Long =
    requireNotNull(aggregate.rowVersion) {
        "Lifecycle aggregate ${aggregate.aggregateType}:${aggregate.aggregateId} was not " +
            "read through the reader port, so no row version is available to guard the save."
    }

private fun <S : Enum<S>> requireExpectedOrganisationId(aggregate: LifecycleAggregate<S>): UUID =
    requireNotNull(aggregate.organisationId) {
        "Lifecycle aggregate ${aggregate.aggregateType}:${aggregate.aggregateId} was not " +
            "read through the reader port, so no organisation id is available to guard " +
            "the save."
    }

private fun requireUpdated(
    updatedRows: Int,
    aggregateType: String,
    aggregateId: String,
) {
    if (updatedRows == 0) {
        throw OptimisticLockingFailureException(
            "Concurrent modification detected for $aggregateType:$aggregateId; " +
                "the row version no longer matched the value read before the transition.",
        )
    }
}
