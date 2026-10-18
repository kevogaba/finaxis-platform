package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.PermissionGuard
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Profile and membership edits (#161): permission first, then validation, then one audit row. */
class ProfileUpdateServiceTests {
    private val subject = uuidV7()
    private val clock = Clock.fixed(Instant.parse("2026-07-17T10:15:30Z"), ZoneOffset.UTC)
    private val store = RecordingStore()
    private val guard = mock<PermissionGuard>()
    private val audits = mutableListOf<AuditEvent>()
    private val service =
        ProfileUpdateService(
            store,
            guard,
            AuditService(AuditEventRepository { audits += it }, clock),
            clock,
        )
    private val actor = uuidV7()
    private val tenant = uuidV7()
    private val membership = uuidV7()

    @Test
    fun `a user edit applies the given fields and audits their names only`() {
        service.updateUser(
            UpdateUserProfileCommand(subject, " Jane W ", "+254700000001", actorId = actor),
        )

        assertEquals(Triple("Jane W", "+254700000001", subject), store.userEdit)
        val row = audits.single()
        assertEquals("user.update", row.action)
        assertEquals(PlatformOrganisation.ID.toString(), row.tenantId)
        assertEquals(subject.toString(), row.subjectUserId)
        assertEquals("display_name,phone_e164", row.metadata["changedFields"])
        assertTrue(
            row.metadata.values.none { it.toString().contains("+254") },
            "no value is audited",
        )
    }

    @Test
    fun `the platform permission is refused before anything is judged or read`() {
        whenever(guard.requirePlatformPermission(actor, "user.update"))
            .doThrow(MissingPermissionException("user.update"))

        assertFailsWith<MissingPermissionException> {
            service.updateUser(UpdateUserProfileCommand(subject, actorId = actor))
        }
        assertEquals(null, store.userEdit)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `an empty edit, a blank name or a bad phone is invalid and writes nothing`() {
        listOf(
            UpdateUserProfileCommand(subject, actorId = actor),
            UpdateUserProfileCommand(subject, displayName = " ", actorId = actor),
            UpdateUserProfileCommand(subject, displayName = " A ", actorId = actor),
            UpdateUserProfileCommand(subject, displayName = "X".repeat(101), actorId = actor),
            UpdateUserProfileCommand(subject, phoneE164 = "0700", actorId = actor),
        ).forEach { command ->
            assertFailsWith<InvalidOperationException> { service.updateUser(command) }
        }
        assertEquals(null, store.userEdit)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a deactivated or archived account is not edited and audits nothing`() {
        listOf("DEACTIVATED", "ARCHIVED").forEach { terminal ->
            store.userStatus = terminal

            assertFailsWith<InvalidOperationException> {
                service.updateUser(
                    UpdateUserProfileCommand(subject, displayName = "Jane", actorId = actor),
                )
            }
        }
        assertEquals(null, store.userEdit)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `an unknown account is not found and audits nothing`() {
        store.userExists = false

        assertFailsWith<ResourceNotFoundException> {
            service.updateUser(
                UpdateUserProfileCommand(subject, displayName = "Jane", actorId = actor),
            )
        }
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a membership edit applies the type and the branch and audits the subject user`() {
        val branch = uuidV7()
        store.activeBranches += branch

        service.updateMembership(
            UpdateMembershipCommand(
                tenant,
                membership,
                "AUDITOR",
                changesPrimaryBranch = true,
                primaryBranchId = branch,
                actorId = actor,
            ),
        )

        assertEquals("AUDITOR", store.membershipEdit?.membershipType)
        assertEquals(branch, store.membershipEdit?.primaryBranchId)
        val row = audits.single()
        assertEquals("membership.update", row.action)
        assertEquals(tenant.toString(), row.tenantId)
        assertEquals(subject.toString(), row.subjectUserId)
        assertEquals("membership_type,primary_branch_id", row.metadata["changedFields"])
    }

    @Test
    fun `a membership edit may clear the primary branch without checking one`() {
        service.updateMembership(
            UpdateMembershipCommand(
                tenant,
                membership,
                changesPrimaryBranch = true,
                primaryBranchId = null,
                actorId = actor,
            ),
        )

        assertEquals(true, store.membershipEdit?.changesPrimaryBranch)
        assertEquals(null, store.membershipEdit?.primaryBranchId)
    }

    @Test
    fun `a revoked or system membership, SYSTEM type or foreign branch is invalid`() {
        val foreign = uuidV7()
        store.target = MembershipEditTarget(subject, "REVOKED", "STAFF")
        assertFailsWith<InvalidOperationException> {
            service.updateMembership(adminEdit())
        }
        store.target = MembershipEditTarget(subject, "ACTIVE", "SYSTEM")
        assertFailsWith<InvalidOperationException> {
            service.updateMembership(adminEdit())
        }
        store.target = MembershipEditTarget(subject, "ACTIVE", "STAFF")
        assertFailsWith<InvalidOperationException> {
            service.updateMembership(adminEdit("SYSTEM"))
        }
        assertFailsWith<InvalidOperationException> {
            service.updateMembership(
                UpdateMembershipCommand(
                    tenant,
                    membership,
                    changesPrimaryBranch = true,
                    primaryBranchId = foreign,
                    actorId = actor,
                ),
            )
        }
        assertEquals(null, store.membershipEdit)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `an unknown membership is not found and the tenant permission is checked first`() {
        store.target = null
        assertFailsWith<ResourceNotFoundException> {
            service.updateMembership(adminEdit())
        }
        whenever(guard.requireTenantPermission(actor, tenant, "membership.update"))
            .doThrow(MissingPermissionException("membership.update"))
        val denied = mock<ProfileUpdateStore>()
        val strict =
            ProfileUpdateService(
                denied,
                guard,
                AuditService(AuditEventRepository { }, clock),
                clock,
            )
        assertFailsWith<MissingPermissionException> {
            strict.updateMembership(adminEdit())
        }
        verifyNoInteractions(denied)
    }

    @Test
    fun `an active membership without a branch assignment cannot become branch-bound`() {
        store.target = MembershipEditTarget(subject, "ACTIVE", "AUDITOR")
        store.hasBranchAssignment = false

        assertFailsWith<ConflictException> { service.updateMembership(adminEdit()) }
        assertFailsWith<ConflictException> { service.updateMembership(adminEdit("STAFF")) }
        assertEquals(null, store.membershipEdit)
        assertTrue(audits.isEmpty())

        service.updateMembership(adminEdit("AUDITOR"))
        assertEquals("AUDITOR", store.membershipEdit?.membershipType)
    }

    private fun adminEdit(type: String = "ADMIN") =
        UpdateMembershipCommand(tenant, membership, type, actorId = actor)

    private inner class RecordingStore : ProfileUpdateStore {
        var userExists = true
        var userStatus = "ACTIVE"
        var userEdit: Triple<String?, String?, UUID>? = null
        var target: MembershipEditTarget? = MembershipEditTarget(subject, "ACTIVE", "STAFF")
        val activeBranches = mutableSetOf<UUID>()
        var hasBranchAssignment = true
        var membershipEdit: UpdateMembershipCommand? = null

        override fun lockUserStatus(userId: UUID) = userStatus.takeIf { userExists }

        override fun updateUserProfile(
            userId: UUID,
            displayName: String?,
            phoneE164: String?,
            actorId: UUID,
        ) {
            userEdit = Triple(displayName, phoneE164, userId)
        }

        override fun lockMembership(
            organisationId: UUID,
            membershipId: UUID,
        ) = target

        override fun hasActiveBranchAssignment(
            organisationId: UUID,
            userId: UUID,
        ) = hasBranchAssignment

        override fun activeBranchExists(
            organisationId: UUID,
            branchId: UUID,
        ) = branchId in activeBranches

        override fun updateMembership(
            command: UpdateMembershipCommand,
            now: OffsetDateTime,
        ) {
            membershipEdit = command
        }
    }
}
