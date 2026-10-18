package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.TenantFilter
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Store-level proof of the richer tenant and branch list items (#189) and their counts (#191):
 * the fields the summaries embed, what each count includes and excludes, and that a list costs the
 * same number of statements whatever its page size.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqFoundationQuerySummaryTests(
    private val dsl: DSLContext,
    private val store: JooqFoundationQueryStore,
) {
    @Test
    fun `tenant items carry currency timezone bootstrap status and active counts only`() {
        val org = insertOrganisation("summary-a", "Africa/Nairobi", "KES")
        insertBranch(org, "Head Office", "ACTIVE")
        insertBranch(org, "Draft Branch", "DRAFT")
        insertBranch(org, "Closed Branch", "CLOSED")
        val alice = insertUser("alice")
        val bob = insertUser("bob")
        val carol = insertUser("carol")
        insertMembership(org, alice, "ACTIVE")
        insertMembership(org, bob, "ACTIVE")
        insertMembership(org, carol, "SUSPENDED")
        insertBootstrap(org, "COMPLETED")
        // Another organisation's rows must not leak into this one's counts.
        val other = insertOrganisation("summary-b", "Africa/Lagos", "NGN")
        insertBranch(other, "Elsewhere", "ACTIVE")
        insertMembership(other, insertUser("dave"), "ACTIVE")

        val item = store.searchTenants(TenantFilter(), org).items.single()

        assertEquals("KES", item.baseCurrencyCode)
        assertEquals("Africa/Nairobi", item.timezone)
        assertEquals("COMPLETED", item.bootstrapStatus)
        assertEquals(1, item.activeBranchCount, "only ACTIVE branches count")
        assertEquals(2, item.activeUserCount, "only ACTIVE memberships count")
    }

    @Test
    fun `a tenant with no bootstrap record has a null status and still lists`() {
        val org = insertOrganisation("summary-c", "Africa/Nairobi", "KES")

        val item = store.searchTenants(TenantFilter(), org).items.single()

        assertNull(item.bootstrapStatus)
        assertEquals(0, item.activeBranchCount)
        assertEquals(0, item.activeUserCount)
    }

    @Test
    fun `branch items carry hierarchy timezone dates and distinct active assignees`() {
        val org = insertOrganisation("summary-d", "Africa/Nairobi", "KES")
        val parent = insertBranch(org, "Head Office", "ACTIVE")
        val child =
            insertBranch(
                org,
                "Westlands",
                "ACTIVE",
                parent = parent,
                timezone = "Africa/Kampala",
                openedOn = LocalDate.of(2026, 3, 1),
                closedOn = LocalDate.of(2026, 9, 30),
            )
        val alice = insertUser("alice")
        val bob = insertUser("bob")
        val carol = insertUser("carol")
        listOf(alice, bob, carol).forEach { insertMembership(org, it, "ACTIVE") }
        // alice holds two ACTIVE types and counts once; bob counts; carol was revoked.
        insertAssignment(org, alice, child, "VIEW", "ACTIVE")
        insertAssignment(org, alice, child, "APPROVE", "ACTIVE")
        insertAssignment(org, bob, child, "VIEW", "ACTIVE")
        insertAssignment(org, carol, child, "VIEW", "REVOKED")

        val items = store.searchBranches(org, BranchFilter(), null).items.associateBy { it.id }

        val westlands = items.getValue(child)
        assertEquals(parent, westlands.parentBranchId)
        assertEquals("Africa/Kampala", westlands.timezone)
        assertEquals(LocalDate.of(2026, 3, 1), westlands.openedOn)
        assertEquals(LocalDate.of(2026, 9, 30), westlands.closedOn)
        assertEquals(2, westlands.activeUserCount)

        val head = items.getValue(parent)
        assertNull(head.parentBranchId)
        assertNull(head.openedOn)
        assertEquals(0, head.activeUserCount)
    }

    @Test
    fun `branch items name the creator and the latest submitter, not the system`() {
        val org = insertOrganisation("summary-f", "Africa/Nairobi", "KES")
        val maker = insertUser("maker")
        val resubmitter = insertUser("resubmitter")
        val outsider = insertUser("outsider")
        insertMembership(org, maker, "ACTIVE")
        insertMembership(org, resubmitter, "ACTIVE")
        val made = insertBranch(org, "Made", "PENDING_APPROVAL", createdBy = maker)
        insertSubmit(org, made, maker, NOW.plusDays(1))
        insertSubmit(org, made, resubmitter, NOW.plusDays(2))
        val bootstrapped = insertBranch(org, "Head Office", "ACTIVE", createdBy = SystemActor.ID)
        val platformMade = insertBranch(org, "Platform", "DRAFT", createdBy = outsider)
        val neverSubmitted = insertBranch(org, "Draft", "DRAFT", createdBy = maker)

        val items = store.searchBranches(org, BranchFilter(), null).items.associateBy { it.id }

        assertEquals(ActorRef(maker, "maker", true), items.getValue(made).createdBy)
        assertEquals(
            ActorRef(resubmitter, "resubmitter", true),
            items.getValue(made).submittedBy,
            "the latest SUBMIT wins",
        )
        assertNull(items.getValue(bootstrapped).createdBy, "the system actor has no account")
        assertEquals(false, items.getValue(platformMade).createdBy?.member)
        assertNull(items.getValue(neverSubmitted).submittedBy)

        val detail = requireNotNull(store.findBranchById(org, made))
        assertEquals(maker, detail.createdBy?.id)
        assertEquals(resubmitter, detail.submittedBy?.id)
    }

    @Test
    fun `tenant items name the requester and the submitter from the bootstrap record`() {
        val org = insertOrganisation("summary-g", "Africa/Nairobi", "KES")
        val requester = insertUser("requester")
        val submitter = insertUser("submitter")
        insertBootstrap(org, "DRAFT", requestedBy = requester, submittedBy = submitter)
        val bare = insertOrganisation("summary-h", "Africa/Nairobi", "KES")

        val item = store.searchTenants(TenantFilter(), org).items.single()
        assertEquals(requester, item.requestedBy?.id)
        assertEquals("submitter", item.submittedBy?.displayName)
        assertEquals(false, item.requestedBy?.member, "a platform user is no member of the tenant")

        val detail = requireNotNull(store.findTenantById(org))
        assertEquals(requester, detail.requestedBy?.id)
        assertEquals(submitter, detail.submittedBy?.id)

        val none = store.searchTenants(TenantFilter(), bare).items.single()
        assertNull(none.requestedBy)
        assertNull(none.submittedBy)
    }

    @Test
    fun `tenant and branch lists cost the same statements whatever the page size`() {
        val org = insertOrganisation("summary-e", "Africa/Nairobi", "KES")
        repeat(6) { insertBranch(org, "Branch $it", "ACTIVE") }
        val counter = AtomicInteger()
        val counting =
            JooqFoundationQueryStore(
                DSL.using(
                    dsl.configuration().derive(
                        DefaultExecuteListenerProvider(
                            object : DefaultExecuteListener() {
                                override fun executeStart(ctx: ExecuteContext) {
                                    counter.incrementAndGet()
                                }
                            },
                        ),
                    ),
                ),
            )

        val lists: Map<String, (Int) -> Unit> =
            mapOf(
                "tenants" to { size -> counting.searchTenants(TenantFilter(size = size), org) },
                "branches" to { size ->
                    counting.searchBranches(org, BranchFilter(size = size), null)
                },
            )
        lists.forEach { (name, run) ->
            counter.set(0)
            run(1)
            val small = counter.get()
            counter.set(0)
            run(50)
            assertEquals(small, counter.get(), "$name list statements, page size 1 vs 50")
        }
    }

    private fun insertOrganisation(
        code: String,
        timezone: String,
        currency: String,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "$code-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Summary $code")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, currency)
            .set(ORGANISATION.TIMEZONE, timezone)
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, NOW)
            .set(ORGANISATION.UPDATED_AT, NOW)
            .execute()
        return id
    }

    private fun insertBranch(
        organisationId: UUID,
        name: String,
        status: String,
        parent: UUID? = null,
        createdBy: UUID? = null,
        timezone: String = "Africa/Nairobi",
        openedOn: LocalDate? = null,
        closedOn: LocalDate? = null,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "b-$id")
            .set(BRANCH.BRANCH_NAME, name)
            .set(BRANCH.BRANCH_TYPE, if (parent == null) "HEAD_OFFICE" else "BRANCH")
            .set(BRANCH.PARENT_BRANCH_ID, parent)
            .set(BRANCH.STATUS, status)
            .set(BRANCH.TIMEZONE, timezone)
            .set(BRANCH.OPENED_ON, openedOn)
            .set(BRANCH.CLOSED_ON, closedOn)
            .set(BRANCH.CREATED_BY, createdBy)
            .set(BRANCH.CREATED_AT, NOW)
            .set(BRANCH.UPDATED_AT, NOW)
            .execute()
        return id
    }

    private fun insertUser(username: String): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "$username-$id")
            .set(USER_ACCOUNT.EMAIL, "$username-$id@t.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, NOW)
            .set(USER_ACCOUNT.UPDATED_AT, NOW)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        status: String,
    ) {
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, NOW)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, NOW)
            .execute()
    }

    private fun insertAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
        type: String,
        status: String,
    ) {
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, uuidV7())
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, type)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, status)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, NOW)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, NOW)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, NOW)
            .execute()
    }

    private fun insertSubmit(
        organisationId: UUID,
        branchId: UUID,
        actor: UUID,
        at: OffsetDateTime,
    ) {
        dsl
            .insertInto(BRANCH_TRANSITION_LOG)
            .set(BRANCH_TRANSITION_LOG.ID, uuidV7())
            .set(BRANCH_TRANSITION_LOG.ORGANISATION_ID, organisationId)
            .set(BRANCH_TRANSITION_LOG.BRANCH_ID, branchId)
            .set(BRANCH_TRANSITION_LOG.ENTITY_ID, branchId)
            .set(BRANCH_TRANSITION_LOG.TRANSITION_NAME, "SUBMIT")
            .set(BRANCH_TRANSITION_LOG.STATUS_FROM, "DRAFT")
            .set(BRANCH_TRANSITION_LOG.STATUS_TO, "PENDING_APPROVAL")
            .set(BRANCH_TRANSITION_LOG.CREATED_AT, at)
            .set(BRANCH_TRANSITION_LOG.CREATED_BY, actor)
            .set(BRANCH_TRANSITION_LOG.UPDATED_AT, at)
            .execute()
    }

    private fun insertBootstrap(
        organisationId: UUID,
        status: String,
        requestedBy: UUID = uuidV7(),
        submittedBy: UUID? = null,
    ) {
        val bootstrap = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        dsl
            .insertInto(bootstrap)
            .set(bootstrap.ORGANISATION_ID, organisationId)
            .set(bootstrap.ADMIN_EMAIL, "admin@t.test")
            .set(bootstrap.ADMIN_USERNAME, "admin-$organisationId")
            .set(bootstrap.ADMIN_DISPLAY_NAME, "Admin")
            .set(bootstrap.STATUS, status)
            .set(bootstrap.REQUESTED_BY, requestedBy)
            .set(bootstrap.SUBMITTED_BY, submittedBy)
            .set(bootstrap.CREATED_AT, NOW)
            .set(bootstrap.UPDATED_AT, NOW)
            .execute()
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-01-01T09:00:00Z")
    }
}
