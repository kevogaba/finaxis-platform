package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqUserProvisioningStoreTests(
    private val dsl: DSLContext,
    private val store: JooqUserProvisioningStore,
) {
    @Test
    fun `creates draft user and finds it by lower-case email`() {
        val actorId = UUID.randomUUID()

        val userId =
            store.createUserAccount(
                "Member@Example.Test",
                "member",
                "Member One",
                "+254700000000",
                actorId,
            )

        assertEquals(userId, store.findUserIdByEmail("member@example.test"))
        assertEquals(UserLifecycleState.DRAFT, store.userStatus(userId))
    }

    @Test
    fun `creates pending membership and loads invitation snapshot`() {
        val organisationId = insertOrganisation()
        val userId =
            store.createUserAccount(
                "join@example.test",
                "join",
                "Join User",
                null,
                userId(),
            )
        val branchId = insertBranch(organisationId)

        val membershipId =
            store.createMembership(
                organisationId,
                userId,
                MembershipType.STAFF,
                branchId,
                userId(),
            )

        val defaultSnapshot = assertNotNull(store.membershipSnapshot(organisationId, membershipId))
        assertFalse(defaultSnapshot.sendKeycloakInvite)
        assertFalse(defaultSnapshot.sendApplicationInvite)

        store.saveInvitationPreferences(organisationId, membershipId, false, true, userId())

        val snapshot = assertNotNull(store.membershipSnapshot(organisationId, membershipId))
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, snapshot.status)
        assertEquals(MembershipType.STAFF, snapshot.type)
        assertFalse(snapshot.sendKeycloakInvite)
        assertTrue(snapshot.sendApplicationInvite)
        assertNull(
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.STATUS_REASON)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.STATUS_REASON),
        )
    }

    @Test
    fun `assigns role idempotently and reports active role assignment`() {
        val organisationId = insertOrganisation()
        val roleId = insertRole(organisationId)
        val userId =
            store.createUserAccount(
                "role@example.test",
                "role",
                "Role User",
                null,
                userId(),
            )
        store.createMembership(organisationId, userId, MembershipType.STAFF, null, userId())

        val first =
            store.assignRole(
                organisationId,
                userId,
                roleId,
                RoleAssignmentScopeType.TENANT,
                null,
                userId(),
            )
        val second =
            store.assignRole(
                organisationId,
                userId,
                roleId,
                RoleAssignmentScopeType.TENANT,
                null,
                userId(),
            )

        assertEquals(first, second)
        assertTrue(store.hasActiveRoleAssignment(organisationId, userId))
        assertEquals(
            1,
            dsl.fetchCount(
                USER_ROLE_ASSIGNMENT,
                USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId)),
            ),
        )
    }

    @Test
    fun `records dispatch rows idempotently by dispatch key`() {
        val organisationId = insertOrganisation()
        val userId =
            store.createUserAccount(
                "dispatch@example.test",
                "dispatch",
                "Dispatch",
                null,
                userId(),
            )
        val key = "$userId:KEYCLOAK_PROVISIONING"

        store.recordDispatch(
            organisationId,
            userId,
            IdentityDispatchType.KEYCLOAK_PROVISIONING,
            key,
            userId(),
        )
        store.recordDispatch(
            organisationId,
            userId,
            IdentityDispatchType.KEYCLOAK_PROVISIONING,
            key,
            userId(),
        )

        assertEquals(
            1,
            dsl.fetchCount(
                IDENTITY_DISPATCH_LOG,
                IDENTITY_DISPATCH_LOG.DISPATCH_KEY.eq(key),
            ),
        )
    }

    @Test
    fun `keeps organisation boundaries on branch role and membership reads`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val branchId = insertBranch(otherOrganisationId)
        val roleId = insertRole(otherOrganisationId)
        val userId = store.createUserAccount("bound@example.test", "bound", "Bound", null, userId())
        val membershipId =
            store.createMembership(
                otherOrganisationId,
                userId,
                MembershipType.STAFF,
                branchId,
                userId(),
            )

        assertNull(store.branchState(organisationId, branchId))
        assertFalse(store.roleExists(organisationId, roleId))
        assertNull(store.membershipSnapshot(organisationId, membershipId))
    }

    private fun insertOrganisation(
        status: OrganisationLifecycleState = OrganisationLifecycleState.ACTIVE,
    ): UUID {
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
            .set(ORGANISATION.STATUS, status.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(
        organisationId: UUID,
        status: BranchLifecycleState = BranchLifecycleState.ACTIVE,
    ): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, status.name)
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertRole(organisationId: UUID): UUID {
        val id = UUID.randomUUID()
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

    private fun userId(): UUID = UUID.randomUUID()
}
