package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The member number (#287, ADR 0040) is taken from the organisation's `MEMBER` counter in the
 * transaction that first makes a membership `ACTIVE`, through the one write every activation
 * route uses. Each test commits its own transactions, as the activation routes do, so the
 * counter's row lock and a rollback are real.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MemberNumberAllocationIntegrationTests(
    private val dsl: DSLContext,
    private val persistence: JooqFoundationLifecyclePersistence,
    private val transactions: TransactionTemplate,
) {
    @Test
    fun `first activations are numbered in order and each organisation counts from one`() {
        val first = insertOrganisation()
        val second = insertOrganisation()
        val members = List(3) { insertMembership(first) }
        val other = insertMembership(second)

        members.forEach { activate(first, it) }
        activate(second, other)

        assertEquals(listOf(1L, 2L, 3L), members.map { memberNumber(it) })
        assertEquals(1L, memberNumber(other))
        assertEquals(4L, counter(first))
        assertEquals(2L, counter(second))
        members.forEach { assertNotNull(joinedAt(it)) }
    }

    @Test
    fun `reactivation and a re-invited member's next activation keep the number`() {
        val organisationId = insertOrganisation()
        val membershipId = insertMembership(organisationId)
        activate(organisationId, membershipId)
        val firstJoin = joinedAt(membershipId)

        save(organisationId, membershipId, MembershipLifecycleState.SUSPENDED)
        activate(organisationId, membershipId)
        assertEquals(1L, memberNumber(membershipId))
        assertEquals(firstJoin, joinedAt(membershipId), "a reactivation is not a new join")

        save(organisationId, membershipId, MembershipLifecycleState.REVOKED)
        // A re-invite resets the row, joined_at included, and keeps the number (ADR 0037).
        dsl
            .update(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "PENDING_APPROVAL")
            .set(USER_ORGANISATION_MEMBERSHIP.JOINED_AT, null as OffsetDateTime?)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .execute()
        activate(organisationId, membershipId)

        assertEquals(1L, memberNumber(membershipId))
        assertNotNull(joinedAt(membershipId))
        assertEquals(2L, counter(organisationId), "no second number was taken")
    }

    @Test
    fun `a membership revoked before it is ever active takes no number`() {
        val organisationId = insertOrganisation()
        val membershipId = insertMembership(organisationId)

        save(organisationId, membershipId, MembershipLifecycleState.REVOKED)

        assertNull(memberNumber(membershipId))
        assertNull(counter(organisationId))
    }

    @Test
    fun `an activation the version check refuses hands its number back`() {
        val organisationId = insertOrganisation()
        val refused = insertMembership(organisationId)
        val next = insertMembership(organisationId)

        assertThrows<OptimisticLockingFailureException> {
            transactions.executeWithoutResult {
                persistence.saveMembership(
                    LifecycleAggregate(
                        refused,
                        MembershipLifecycleState.ACTIVE,
                        "MEMBERSHIP",
                        organisationId = organisationId,
                        rowVersion = STALE_VERSION,
                    ),
                )
            }
        }
        activate(organisationId, next)

        assertNull(memberNumber(refused))
        assertEquals(1L, memberNumber(next), "the refused activation's number was not burned")
    }

    @Test
    fun `concurrent activations in one organisation take distinct gapless numbers`() {
        val organisationId = insertOrganisation()
        val members = List(CONCURRENT_ACTIVATIONS) { insertMembership(organisationId) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT_ACTIVATIONS)
        try {
            val results =
                members.map { membershipId ->
                    pool.submit {
                        start.await()
                        activate(organisationId, membershipId)
                    }
                }
            start.countDown()
            results.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val numbers = members.map { requireNotNull(memberNumber(it)) }.sorted()
        assertEquals((1L..CONCURRENT_ACTIVATIONS.toLong()).toList(), numbers)
        assertEquals(CONCURRENT_ACTIVATIONS + 1L, counter(organisationId))
    }

    @Test
    fun `an organisation's member counter is created when it is missing`() {
        val organisationId = insertOrganisation()
        val membershipId = insertMembership(organisationId)
        assertNull(counter(organisationId))

        activate(organisationId, membershipId)

        assertEquals(1L, memberNumber(membershipId))
        assertEquals(2L, counter(organisationId))
    }

    private fun activate(
        organisationId: UUID,
        membershipId: UUID,
    ) = save(organisationId, membershipId, MembershipLifecycleState.ACTIVE)

    private fun save(
        organisationId: UUID,
        membershipId: UUID,
        state: MembershipLifecycleState,
    ) {
        transactions.executeWithoutResult {
            persistence.saveMembership(aggregate(organisationId, membershipId, state))
        }
    }

    private fun aggregate(
        organisationId: UUID,
        membershipId: UUID,
        state: MembershipLifecycleState,
    ): LifecycleAggregate<MembershipLifecycleState> {
        val current = requireNotNull(persistence.findMembership(organisationId, membershipId))
        return LifecycleAggregate(
            membershipId,
            state,
            "MEMBERSHIP",
            organisationId = organisationId,
            rowVersion = current.rowVersion,
        )
    }

    private fun memberNumber(membershipId: UUID): Long? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.MEMBER_NUMBER)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBER_NUMBER)

    private fun joinedAt(membershipId: UUID): OffsetDateTime? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.JOINED_AT)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.JOINED_AT)

    private fun counter(organisationId: UUID): Long? =
        dsl
            .select(REFERENCE_SEQUENCE.NEXT_VALUE)
            .from(REFERENCE_SEQUENCE)
            .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(organisationId))
            .and(REFERENCE_SEQUENCE.SEQUENCE_CODE.eq("MEMBER"))
            .fetchOne(REFERENCE_SEQUENCE.NEXT_VALUE)

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "member-number-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Member Number Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(organisationId: UUID): UUID {
        val userId = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, "member-$userId")
            .set(USER_ACCOUNT.EMAIL, "member-$userId@member-number.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Member")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        val id = uuidV7()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "PENDING_APPROVAL")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private companion object {
        const val CONCURRENT_ACTIVATIONS = 8
        const val WAIT_SECONDS = 30L
        const val STALE_VERSION = 99L
    }
}
