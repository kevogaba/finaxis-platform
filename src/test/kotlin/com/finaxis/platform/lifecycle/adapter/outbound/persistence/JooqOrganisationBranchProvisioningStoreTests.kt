package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationListFilter
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqOrganisationBranchProvisioningStoreTests(
    private val dsl: DSLContext,
    private val store: JooqOrganisationBranchProvisioningStore,
    private val accessStore: JooqOrganisationAccessStore,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `saves settings containing quotes and backslashes without corrupting the JSON`() {
        val organisationId =
            store.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = "tenant-quote-${uuidV7()}",
                    displayName = "Quote Org",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                ),
            )
        val actorId = uuidV7()
        val trickyValue = """He said "hello\world" and left"""

        store.saveSettings(organisationId, mapOf("greeting" to trickyValue), actorId)

        val rawJson =
            dsl
                .select(ORGANISATION_SETTING.SETTING_VALUE)
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                .and(ORGANISATION_SETTING.SETTING_KEY.eq("greeting"))
                .fetchOne(ORGANISATION_SETTING.SETTING_VALUE)
        val decoded = objectMapper.readValue(requireNotNull(rawJson).data(), String::class.java)

        assertEquals(trickyValue, decoded)
    }

    // ── bootstrap projection ──────────────────────────────────────────────────

    @Test
    fun `findByCode returns null bootstrap fields when no bootstrap record exists`() {
        val tenantCode = "no-bootstrap-${uuidV7()}"
        store.createDraft(
            CreateOrganisationDraftCommand(
                tenantCode = tenantCode,
                displayName = "No Bootstrap Org",
                legalName = null,
                registrationNumber = null,
                countryCode = "KE",
                baseCurrencyCode = "KES",
                timezone = "Africa/Nairobi",
                requestedBy = uuidV7(),
                // Deliberately do NOT pass an admin to test absence of the bootstrap record
                // Note: the default admin is injected but createDraft always persists it,
                // so we test via the store directly without going through the service.
            ),
        )
        // Insert a raw ORGANISATION row instead so there is no bootstrap record at all
        val rawId = insertOrganisationDraftOnly()

        val summary = store.findByCode("raw-org-$rawId")
        assertNotNull(summary)
        assertNull(summary.bootstrapStatus)
        assertNull(summary.bootstrapAttempts)
        assertNull(summary.bootstrapUserId)
        assertNull(summary.bootstrapMembershipId)
        assertNull(summary.lastBootstrapFailureCode)
    }

    @Test
    fun `findByCode returns bootstrap projection when a bootstrap record exists`() {
        val tenantCode = "with-bootstrap-${uuidV7()}"
        val organisationId =
            store.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = tenantCode,
                    displayName = "With Bootstrap Org",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "proj.admin@bootstrap.test",
                            username = "projadmin",
                            displayName = "Proj Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        // createDraft does NOT persist the bootstrap row; the service does.
        // Seed it directly via the jOOQ bootstrap store.
        val bootstrapStore =
            JooqInitialAdministratorBootstrapStore(
                dsl,
                java.time.Clock.systemUTC(),
            )
        bootstrapStore.createDraft(
            organisationId,
            InitialAdministratorDraft(
                email = "proj.admin@bootstrap.test",
                username = "projadmin",
                displayName = "Proj Admin",
                phoneE164 = null,
                sendApplicationInvite = false,
            ),
            uuidV7(),
        )

        val summary = store.findByCode(tenantCode)
        assertNotNull(summary)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, summary.bootstrapStatus)
        assertEquals(0, summary.bootstrapAttempts)
        assertNull(summary.bootstrapUserId)
        assertNull(summary.bootstrapMembershipId)
        assertNull(summary.lastBootstrapFailureCode)
    }

    @Test
    fun `list includes bootstrap projection fields from left join`() {
        val tenantCode = "list-bootstrap-${uuidV7()}"
        val organisationId =
            store.createDraft(
                CreateOrganisationDraftCommand(
                    tenantCode = tenantCode,
                    displayName = "List Bootstrap Org",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    requestedBy = uuidV7(),
                ),
            )
        val bootstrapStore =
            JooqInitialAdministratorBootstrapStore(
                dsl,
                java.time.Clock.systemUTC(),
            )
        bootstrapStore.createDraft(
            organisationId,
            InitialAdministratorDraft(
                email = "list.admin@bootstrap.test",
                username = "listadmin",
                displayName = "List Admin",
                phoneE164 = null,
                sendApplicationInvite = false,
            ),
            uuidV7(),
        )

        val page = store.list(OrganisationListFilter(size = 100))
        val summary = page.items.find { it.tenantCode == tenantCode }
        assertNotNull(summary)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, summary.bootstrapStatus)
        assertEquals(0, summary.bootstrapAttempts)
    }

    // ── end bootstrap projection ──────────────────────────────────────────────

    @Test
    fun `revokes active branch and role assignments across users in bulk`() {
        val organisationId = insertOrganisation()
        val roleId = insertRole(organisationId)
        val branchId = insertBranch(organisationId)
        val firstUserId = insertUserAccount()
        val secondUserId = insertUserAccount()
        insertMembership(organisationId, firstUserId)
        insertMembership(organisationId, secondUserId)
        val firstBranchAssignmentId =
            insertActiveBranchAssignment(organisationId, firstUserId, branchId)
        val secondBranchAssignmentId =
            insertActiveBranchAssignment(organisationId, secondUserId, branchId)
        val roleAssignmentId = insertActiveRoleAssignment(organisationId, firstUserId, roleId)

        val revoked = accessStore.revokeActiveAssignments(organisationId)

        assertEquals(3, revoked.size)
        assertEquals(
            setOf(firstBranchAssignmentId, secondBranchAssignmentId, roleAssignmentId),
            revoked.map { it.assignmentId }.toSet(),
        )
        assertEquals(0, activeBranchAssignmentCount(organisationId))
        assertEquals(0, activeRoleAssignmentCount(organisationId))
        assertTrue(
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(USER_BRANCH_ASSIGNMENT)
                    .where(USER_BRANCH_ASSIGNMENT.ID.eq(firstBranchAssignmentId))
                    .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("REVOKED"))
                    .and(USER_BRANCH_ASSIGNMENT.REVOKED_AT.isNotNull),
            ),
        )
    }

    private fun activeBranchAssignmentCount(organisationId: UUID): Int =
        dsl.fetchCount(
            USER_BRANCH_ASSIGNMENT,
            USER_BRANCH_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    private fun activeRoleAssignmentCount(organisationId: UUID): Int =
        dsl.fetchCount(
            USER_ROLE_ASSIGNMENT,
            USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    private fun insertOrganisationDraftOnly(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "raw-org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Raw Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.DRAFT.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.ACTIVE.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
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
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
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
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
    }

    private fun insertActiveBranchAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, id)
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, BranchAssignmentType.VIEW.name)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertActiveRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, id)
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "TENANT")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        return id
    }
}
