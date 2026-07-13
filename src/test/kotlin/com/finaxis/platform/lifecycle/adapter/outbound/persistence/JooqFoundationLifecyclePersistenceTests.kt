package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqFoundationLifecyclePersistenceTests(
    private val dsl: DSLContext,
    private val persistence: JooqFoundationLifecyclePersistence,
) {
    @Test
    fun `saveOrganisation rejects a stale row version instead of silently overwriting it`() {
        val organisationId = insertOrganisation()
        val staleRead = requireNotNull(persistence.findOrganisation(organisationId))

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = staleRead.rowVersion,
            ),
        )

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveOrganisation(
                LifecycleAggregate(
                    organisationId,
                    OrganisationLifecycleState.ACTIVE,
                    "ORGANISATION",
                    organisationId = organisationId,
                    rowVersion = staleRead.rowVersion,
                ),
            )
        }

        assertEquals(
            OrganisationLifecycleState.PROVISIONING,
            persistence.findOrganisation(organisationId)?.state,
        )
    }

    @Test
    fun `saveOrganisation succeeds when the row version still matches`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = read.rowVersion,
            ),
        )

        assertEquals(
            OrganisationLifecycleState.PROVISIONING,
            persistence.findOrganisation(organisationId)?.state,
        )
    }

    @Test
    fun `saveBranch does not mutate a branch belonging to a different organisation`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val branchId = insertBranch(organisationId)
        val read = requireNotNull(persistence.findBranch(organisationId, branchId))

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveBranch(
                LifecycleAggregate(
                    branchId,
                    BranchLifecycleState.ACTIVE,
                    "BRANCH",
                    organisationId = otherOrganisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        assertEquals(
            BranchLifecycleState.PENDING_APPROVAL,
            persistence.findBranch(organisationId, branchId)?.state,
        )
    }

    @Test
    fun `saveMembership does not mutate a membership belonging to a different organisation`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val read = requireNotNull(persistence.findMembership(organisationId, membershipId))

        assertThrows<OptimisticLockingFailureException> {
            persistence.saveMembership(
                LifecycleAggregate(
                    membershipId,
                    MembershipLifecycleState.ACTIVE,
                    "MEMBERSHIP",
                    organisationId = otherOrganisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        assertEquals(
            MembershipLifecycleState.PENDING_APPROVAL,
            persistence.findMembership(organisationId, membershipId)?.state,
        )
    }

    @Test
    fun `saveOrganisation attributes the system actor when no request context is installed`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))
        RequestContexts.clear()

        persistence.saveOrganisation(
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.PROVISIONING,
                "ORGANISATION",
                organisationId = organisationId,
                rowVersion = read.rowVersion,
            ),
        )

        val updatedBy =
            dsl
                .select(ORGANISATION.UPDATED_BY)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.UPDATED_BY)
        assertEquals(SystemActor.ID, updatedBy)
    }

    @Test
    fun `saveOrganisation attributes the actor when a request context is installed`() {
        val organisationId = insertOrganisation()
        val read = requireNotNull(persistence.findOrganisation(organisationId))
        val actorId = UUID.randomUUID()

        RequestContexts.withActor(
            ActorContext(actorId, "subject", "actor", "actor@example.test"),
        ) {
            persistence.saveOrganisation(
                LifecycleAggregate(
                    organisationId,
                    OrganisationLifecycleState.PROVISIONING,
                    "ORGANISATION",
                    organisationId = organisationId,
                    rowVersion = read.rowVersion,
                ),
            )
        }

        val updatedBy =
            dsl
                .select(ORGANISATION.UPDATED_BY)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.UPDATED_BY)
        assertEquals(actorId, updatedBy)
        assertNotEquals(SystemActor.ID, updatedBy)
    }

    private fun insertOrganisation(): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.PENDING_APPROVAL.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(organisationId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, BranchLifecycleState.PENDING_APPROVAL.name)
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(
                USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS,
                MembershipLifecycleState.PENDING_APPROVAL.name,
            ).set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }
}
