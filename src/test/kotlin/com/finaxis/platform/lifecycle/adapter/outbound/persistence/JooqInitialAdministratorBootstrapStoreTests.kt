package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for [JooqInitialAdministratorBootstrapStore] covering the full
 * DRAFT → PENDING_ACTIVATION → QUEUED state machine and amend/reject transitions.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqInitialAdministratorBootstrapStoreTests(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    private val store = JooqInitialAdministratorBootstrapStore(dsl, clock)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-bootstrap-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Bootstrap Store Test Org")
            .set(ORGANISATION.COUNTRY_CODE, "ZZ")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "ZZZ")
            .set(ORGANISATION.TIMEZONE, "UTC")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.DRAFT.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUserAccount(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@bootstrap.test")
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
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "BR-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "HEAD_OFFICE")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "UTC")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertRole(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, id)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, "ROLE_$id")
            .set(ROLE.ROLE_NAME, "Role $id")
            .set(ROLE.SYSTEM_ROLE, false)
            .set(ROLE.STATUS, "ACTIVE")
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun defaultAdmin(suffix: String = "") =
        InitialAdministratorDraft(
            email = "admin$suffix@bootstrap.test",
            username = "bootstrapadmin$suffix",
            displayName = "Bootstrap Admin $suffix",
            phoneE164 = "+254700000099",
            sendApplicationInvite = false,
        )

    // ── createDraft ───────────────────────────────────────────────────────────

    @Test
    fun `createDraft persists all fields and starts in DRAFT`() {
        val organisationId = insertOrganisation()
        val maker = uuidV7()
        val admin = defaultAdmin()

        store.createDraft(organisationId, admin, maker)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(organisationId, record.organisationId)
        assertEquals(admin.email, record.adminEmail)
        assertEquals(admin.username, record.adminUsername)
        assertEquals(admin.displayName, record.adminDisplayName)
        assertEquals(admin.phoneE164, record.adminPhoneE164)
        assertEquals(admin.sendApplicationInvite, record.sendApplicationInvite)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, record.status)
        assertEquals(0, record.attempts)
        assertEquals(maker, record.requestedBy)
        assertNull(record.submittedBy)
        assertNull(record.approvedBy)
        assertNull(record.submittedAt)
        assertNull(record.approvedAt)
    }

    // ── amendDraft ────────────────────────────────────────────────────────────

    @Test
    fun `amendDraft updates admin details and increments row version`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        val before = assertNotNull(store.find(organisationId))

        val updated =
            InitialAdministratorDraft(
                email = "new.admin@bootstrap.test",
                username = "newadmin",
                displayName = "New Admin",
                phoneE164 = "+254711111111",
                sendApplicationInvite = true,
            )
        store.amendDraft(organisationId, updated)

        val after = assertNotNull(store.find(organisationId))
        assertEquals(updated.email, after.adminEmail)
        assertEquals(updated.username, after.adminUsername)
        assertEquals(updated.displayName, after.adminDisplayName)
        assertEquals(updated.phoneE164, after.adminPhoneE164)
        assertEquals(true, after.sendApplicationInvite)
        assertEquals(before.rowVersion + 1, after.rowVersion)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, after.status) // unchanged
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    fun `submit transitions status to PENDING_ACTIVATION and records submitter`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        val submitter = uuidV7()

        store.submit(organisationId, submitter)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.PENDING_ACTIVATION, record.status)
        assertEquals(submitter, record.submittedBy)
        assertNotNull(record.submittedAt)
        assertNull(record.approvedBy)
    }

    // ── approve ───────────────────────────────────────────────────────────────

    @Test
    fun `approve transitions status to QUEUED and records approver`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        store.submit(organisationId, uuidV7())
        val checker = uuidV7()

        store.approve(organisationId, checker)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.QUEUED, record.status)
        assertEquals(checker, record.approvedBy)
        assertNotNull(record.approvedAt)
    }

    // ── reject ────────────────────────────────────────────────────────────────

    @Test
    fun `reject returns status to DRAFT and clears submission fields`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        store.submit(organisationId, uuidV7())

        store.reject(organisationId)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, record.status)
        assertNull(record.submittedBy)
        assertNull(record.submittedAt)
        assertNull(record.approvedBy)
        assertNull(record.approvedAt)
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    fun `updateStatus changes status and captures failure code`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        store.submit(organisationId, uuidV7())
        store.approve(organisationId, uuidV7())
        store.updateStatus(
            organisationId,
            InitialAdministratorBootstrapStatus.FAILED,
            "KEYCLOAK_TIMEOUT",
        )

        val record = assertNotNull(store.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.FAILED, record.status)
        assertEquals("KEYCLOAK_TIMEOUT", record.lastFailureCode)
    }

    @Test
    fun `updateStatus clears failure code when null is passed`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        store.submit(organisationId, uuidV7())
        store.approve(organisationId, uuidV7())
        store.updateStatus(organisationId, InitialAdministratorBootstrapStatus.FAILED, "ERR")

        store.updateStatus(organisationId, InitialAdministratorBootstrapStatus.COMPLETED, null)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.COMPLETED, record.status)
        assertNull(record.lastFailureCode)
    }

    // ── incrementAttempts via updateStatus ─────────────────────────────────────

    @Test
    fun `incrementAttempts adds one per call`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())

        store.updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = "ERR1",
            incrementAttempts = true,
        )
        store.updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = "ERR2",
            incrementAttempts = true,
        )

        val record = assertNotNull(store.find(organisationId))
        assertEquals(2, record.attempts)
        assertEquals("ERR2", record.lastFailureCode)
    }

    // ── linkResolvedEntities ──────────────────────────────────────────────────

    @Test
    fun `linkResolvedEntities stores all resolved entity IDs`() {
        val organisationId = insertOrganisation()
        store.createDraft(organisationId, defaultAdmin(), uuidV7())
        // Each resolved-entity column has a FK – seed the referenced parent rows first
        val userId = insertUserAccount()
        val membershipId = insertMembership(organisationId, userId)
        val headOfficeId = insertBranch(organisationId)
        val roleId = insertRole(organisationId)

        store.linkResolvedEntities(organisationId, userId, membershipId, headOfficeId, roleId)

        val record = assertNotNull(store.find(organisationId))
        assertEquals(userId, record.userId)
        assertEquals(membershipId, record.membershipId)
        assertEquals(headOfficeId, record.headOfficeId)
        assertEquals(roleId, record.roleId)
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    fun `find returns null when no record exists for the organisation`() {
        assertNull(store.find(UUID.randomUUID()))
    }
}
