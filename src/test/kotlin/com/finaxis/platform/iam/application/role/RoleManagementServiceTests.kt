package com.finaxis.platform.iam.application.role

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import com.finaxis.platform.iam.application.port.outbound.IamAdministrationPersistence
import com.finaxis.platform.iam.application.port.outbound.MembershipSnapshot
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.port.outbound.RoleSnapshot
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.lifecycle.PermissionGuard
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises organisation-scoped role administration rules without infrastructure dependencies. */
class RoleManagementServiceTests {
    private val fixture = Fixture()

    /** Rejects a role code that already exists in the selected organisation. */
    @Test
    fun `create rejects duplicate role code in organisation`() {
        fixture.persistence.roleCodes += fixture.organisationId to "OPS"

        val exception =
            assertFailsWith<ConflictException> {
                fixture.service.createTenantRole(fixture.createRole(roleCode = "OPS"))
            }

        assertEquals("conflict", exception.code)
    }

    /** Creates a tenant role, returns its active status, and records the audit event. */
    @Test
    fun `create tenant role persists active role and audits creation`() {
        val result = fixture.service.createTenantRole(fixture.createRole(requestId = "req-create"))
        val audit = fixture.audits.events.single()

        assertEquals(RoleStatus.ACTIVE, result.status)
        assertTrue(fixture.organisationId to "OPS" in fixture.persistence.roleCodes)
        assertEquals("role.create", audit.action)
        assertEquals("req-create", audit.requestId)
    }

    /** Rejects incomplete role creation commands before persistence is touched. */
    @Test
    fun `create tenant role requires code and name`() {
        val blankCode =
            assertFailsWith<InvalidOperationException> {
                fixture.service.createTenantRole(fixture.createRole(roleCode = " "))
            }
        val blankName =
            assertFailsWith<InvalidOperationException> {
                fixture.service.createTenantRole(fixture.createRole(roleName = " "))
            }

        assertEquals("invalid_operation", blankCode.code)
        assertEquals("invalid_operation", blankName.code)
        assertTrue(fixture.persistence.roleCodes.isEmpty())
    }

    /** Updates mutable role metadata using the current row version and audits the change. */
    @Test
    fun `update tenant role changes mutable fields and returns current status`() {
        fixture.persistence.roles[fixture.roleId] =
            fixture.tenantRole().copy(status = RoleStatus.DISABLED, rowVersion = 7)

        val result =
            fixture.service.updateTenantRole(
                fixture.updateRole(roleName = "Updated Ops", requestId = "req-update"),
            )
        val audit = fixture.audits.events.single()

        assertEquals(RoleResult(fixture.roleId, RoleStatus.DISABLED), result)
        assertEquals(
            fixture.roleId to "Updated Ops",
            fixture.persistence.updatedRoles.single(),
        )
        assertEquals(7, fixture.persistence.updatedRowVersions.single())
        assertEquals("role.update", audit.action)
        assertEquals("req-update", audit.requestId)
    }

    /** Rejects a missing role on the update path before audit or persistence writes. */
    @Test
    fun `update tenant role rejects unknown role`() {
        val exception =
            assertFailsWith<ResourceNotFoundException> {
                fixture.service.updateTenantRole(fixture.updateRole())
            }

        assertEquals("resource_not_found", exception.code)
        assertTrue(fixture.persistence.updatedRoles.isEmpty())
        assertTrue(fixture.audits.events.isEmpty())
    }

    /** Prevents system role mutations while allowing their runtime use. */
    @Test
    fun `system role cannot be updated activated deactivated or gain or lose a permission`() {
        fixture.persistence.roles[fixture.roleId] = fixture.systemRole()
        fixture.persistence.permissions["tenant.approve"] = fixture.permissionId

        listOf<() -> Unit>(
            { fixture.service.updateTenantRole(fixture.updateRole()) },
            { fixture.service.activateRole(fixture.activateRole()) },
            { fixture.service.deactivateRole(fixture.deactivateRole()) },
            { fixture.service.assignPermissionToRole(fixture.assignPermission()) },
            { fixture.service.removePermissionFromRole(fixture.removePermission()) },
        ).forEach { operation ->
            val exception = assertFailsWith<ConflictException> { operation() }
            assertEquals("conflict", exception.code)
        }
        assertTrue(fixture.persistence.grantedPermissions.isEmpty())
    }

    /** Rejects a role identifier that is not visible in the command organisation. */
    @Test
    fun `role not found in organisation rejects cross organisation changes`() {
        val exception =
            assertFailsWith<ResourceNotFoundException> {
                fixture.service.activateRole(
                    ActivateRole(fixture.organisationId, fixture.roleId, fixture.actorId),
                )
            }

        assertEquals("resource_not_found", exception.code)
    }

    /** Activates a mutable role, evicts affected memberships, and audits the lifecycle change. */
    @Test
    fun `activate role enables mutable role and evicts affected memberships`() {
        fixture.persistence.roles[fixture.roleId] =
            fixture.tenantRole().copy(status = RoleStatus.DISABLED, rowVersion = 3)
        fixture.persistence.membershipsWithRole[fixture.roleId] = listOf(fixture.membershipId)
        fixture.primePermissionCache(fixture.membershipId)

        val result = fixture.service.activateRole(fixture.activateRole(requestId = "req-active"))
        val audit = fixture.audits.events.single()

        assertEquals(RoleResult(fixture.roleId, RoleStatus.ACTIVE), result)
        assertEquals(
            fixture.roleId to RoleStatus.ACTIVE,
            fixture.persistence.statusChanges.single(),
        )
        assertEquals(3, fixture.persistence.statusRowVersions.single())
        assertNull(fixture.permissionCache.get(fixture.membershipId.toString() + ":none"))
        assertEquals("role.activate", audit.action)
        assertEquals("req-active", audit.requestId)
    }

    /** Deactivates a mutable role and evicts every membership that may have cached permissions. */
    @Test
    fun `deactivate role disables mutable role and evicts affected memberships`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole().copy(rowVersion = 5)
        fixture.persistence.membershipsWithRole[fixture.roleId] = listOf(fixture.membershipId)
        fixture.primePermissionCache(fixture.membershipId)

        val result =
            fixture.service.deactivateRole(fixture.deactivateRole(requestId = "req-disabled"))
        val audit = fixture.audits.events.single()

        assertEquals(RoleResult(fixture.roleId, RoleStatus.DISABLED), result)
        assertEquals(
            fixture.roleId to RoleStatus.DISABLED,
            fixture.persistence.statusChanges.single(),
        )
        assertEquals(5, fixture.persistence.statusRowVersions.single())
        assertNull(fixture.permissionCache.get(fixture.membershipId.toString() + ":none"))
        assertEquals("role.deactivate", audit.action)
    }

    /** Records permission grants and invalidates every membership that receives the role. */
    @Test
    fun `assigning permission audits and evicts memberships with the role`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.permissions["tenant.approve"] = fixture.permissionId
        fixture.persistence.riskLevels["tenant.approve"] = "CRITICAL"
        fixture.persistence.membershipsWithRole[fixture.roleId] = listOf(fixture.membershipId)
        fixture.primePermissionCache(fixture.membershipId)

        fixture.service.assignPermissionToRole(
            AssignPermissionToRole(
                fixture.organisationId,
                fixture.roleId,
                "tenant.approve",
                fixture.actorId,
            ),
        )
        val audit = fixture.audits.events.single()

        assertEquals(
            "role.assign_permission",
            audit.action,
        )
        assertEquals(
            mapOf("permissionCode" to "tenant.approve", "riskLevel" to "CRITICAL"),
            audit.metadata,
        )
        assertNull(fixture.permissionCache.get(fixture.membershipId.toString() + ":none"))
    }

    /** Uses UNKNOWN risk metadata when the permission catalogue lacks an explicit risk level. */
    @Test
    fun `assigning permission records unknown risk when metadata is absent`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.permissions["tenant.read"] = fixture.permissionId

        fixture.service.assignPermissionToRole(
            AssignPermissionToRole(
                fixture.organisationId,
                fixture.roleId,
                "tenant.read",
                fixture.actorId,
            ),
        )
        val audit = fixture.audits.events.single()

        assertEquals(fixture.permissionId, fixture.persistence.grantedPermissions.single())
        assertEquals(
            mapOf("permissionCode" to "tenant.read", "riskLevel" to "UNKNOWN"),
            audit.metadata,
        )
    }

    /** Rejects permission grants when the permission code is outside the catalogue. */
    @Test
    fun `assigning permission rejects unknown permission code`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()

        val exception =
            assertFailsWith<ResourceNotFoundException> {
                fixture.service.assignPermissionToRole(
                    AssignPermissionToRole(
                        fixture.organisationId,
                        fixture.roleId,
                        "missing.permission",
                        fixture.actorId,
                    ),
                )
            }

        assertEquals("resource_not_found", exception.code)
        assertTrue(fixture.persistence.grantedPermissions.isEmpty())
        assertTrue(fixture.audits.events.isEmpty())
    }

    /** Removes a permission from a mutable role and clears permission caches for assigned users. */
    @Test
    fun `remove permission from role revokes catalogue permission and audits removal`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.permissions["tenant.approve"] = fixture.permissionId
        fixture.persistence.membershipsWithRole[fixture.roleId] = listOf(fixture.membershipId)
        fixture.primePermissionCache(fixture.membershipId)

        fixture.service.removePermissionFromRole(
            fixture.removePermission(requestId = "req-remove"),
        )
        val audit = fixture.audits.events.single()

        assertEquals(fixture.permissionId, fixture.persistence.removedPermissions.single())
        assertNull(fixture.permissionCache.get(fixture.membershipId.toString() + ":none"))
        assertEquals("role.remove_permission", audit.action)
        assertEquals(
            mapOf("permissionCode" to "tenant.approve"),
            audit.metadata,
        )
        assertEquals("req-remove", audit.requestId)
    }

    /** Rejects permission removal when the permission code is outside the catalogue. */
    @Test
    fun `remove permission from role rejects unknown permission code`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()

        val exception =
            assertFailsWith<ResourceNotFoundException> {
                fixture.service.removePermissionFromRole(fixture.removePermission())
            }

        assertEquals("resource_not_found", exception.code)
        assertTrue(fixture.persistence.removedPermissions.isEmpty())
        assertTrue(fixture.audits.events.isEmpty())
    }

    /** Requires an active organisation membership before a user can receive a role. */
    @Test
    fun `assigning role requires a non revoked membership`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()

        val missing =
            assertFailsWith<ResourceNotFoundException> {
                fixture.service.assignRoleToUser(fixture.assignRole())
            }
        assertEquals("resource_not_found", missing.code)

        fixture.persistence.memberships[fixture.userId] =
            MembershipSnapshot(fixture.membershipId, MembershipStatus.REVOKED, "STAFF")
        val revoked =
            assertFailsWith<ConflictException> {
                fixture.service.assignRoleToUser(fixture.assignRole())
            }
        assertEquals("conflict", revoked.code)
    }

    /** Requires an active organisation before a user can receive a role. */
    @Test
    fun `assigning role requires an active organisation`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()
        fixture.persistence.currentOrganisationStatus = OrganisationStatus.SUSPENDED

        val exception =
            assertFailsWith<ConflictException> {
                fixture.service.assignRoleToUser(fixture.assignRole())
            }

        assertEquals("conflict", exception.code)
    }

    /** Assigns tenant-scoped roles without a branch and clears the membership permission cache. */
    @Test
    fun `assigning tenant scoped role persists assignment and emits audit metadata`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()
        fixture.primePermissionCache(fixture.membershipId)

        val result = fixture.service.assignRoleToUser(fixture.assignRole(requestId = "req-assign"))
        val audit = fixture.audits.events.single()

        assertEquals(RoleAssignmentResult(fixture.assignmentId, "ACTIVE"), result)
        assertEquals(RoleScopeType.TENANT to null, fixture.persistence.assignedScopes.single())
        assertNull(fixture.permissionCache.get(fixture.membershipId.toString() + ":none"))
        assertEquals("user.assign_role", audit.action)
        assertEquals(
            mapOf(
                "organisationId" to fixture.organisationId.toString(),
                "roleId" to fixture.roleId.toString(),
                "scopeType" to "TENANT",
            ),
            audit.metadata,
        )
        assertEquals("req-assign", audit.requestId)
    }

    /** Assigns branch-scoped roles only when the user already has active branch access. */
    @Test
    fun `assigning branch scoped role requires and records active branch assignment`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()
        fixture.persistence.activeBranchAssignments += fixture.branchId

        val result =
            fixture.service.assignRoleToUser(
                fixture.assignRole(
                    scopeType = RoleScopeType.BRANCH,
                    branchId = fixture.branchId,
                ),
            )

        assertEquals(RoleAssignmentResult(fixture.assignmentId, "ACTIVE"), result)
        assertEquals(
            RoleScopeType.BRANCH to fixture.branchId,
            fixture.persistence.assignedScopes.single(),
        )
        val event = assertIs<ExternalizedTransitionEvent>(fixture.events.events.single())
        val audit = fixture.audits.events.single()
        assertEquals(fixture.branchId.toString(), event.metadata["branchId"])
        assertEquals("BRANCH", audit.metadata["scopeType"])
        verify(fixture.permissionGuard).requireBranchPermission(
            fixture.actorId,
            fixture.organisationId,
            fixture.branchId,
            "user.assign_role",
        )
    }

    /** Requires branch access for branch-scoped roles and forbids a branch for tenant scope. */
    @Test
    fun `role assignment validates branch and tenant scopes`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()

        val missingBranch =
            assertFailsWith<ConflictException> {
                fixture.service.assignRoleToUser(
                    fixture.assignRole(scopeType = RoleScopeType.BRANCH),
                )
            }
        assertEquals("conflict", missingBranch.code)

        val tenantWithBranch =
            assertFailsWith<InvalidOperationException> {
                fixture.service.assignRoleToUser(
                    fixture.assignRole(
                        scopeType = RoleScopeType.TENANT,
                        branchId = fixture.branchId,
                    ),
                )
            }
        assertEquals("invalid_operation", tenantWithBranch.code)
    }

    /** Treats an existing active assignment as an idempotent no-op with no second event. */
    @Test
    fun `duplicate active role assignment is idempotent`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()
        fixture.persistence.activeAssignments += fixture.assignmentId

        val result = fixture.service.assignRoleToUser(fixture.assignRole())

        assertEquals(fixture.assignmentId, result.assignmentId)
        assertTrue(fixture.events.events.isEmpty())
        assertTrue(fixture.audits.events.isEmpty())
    }

    /** Audits and externalizes role assignments and revocations through the durable targets. */
    @Test
    fun `assign and revoke emit expected durable events and audit records`() {
        fixture.persistence.roles[fixture.roleId] = fixture.tenantRole()
        fixture.persistence.memberships[fixture.userId] = fixture.activeMembership()

        fixture.service.assignRoleToUser(fixture.assignRole())
        fixture.persistence.activeAssignments += fixture.assignmentId
        fixture.service.revokeRoleFromUser(fixture.revokeRole())

        val assigned = assertIs<ExternalizedTransitionEvent>(fixture.events.events.first())
        val revoked = assertIs<ExternalizedTransitionEvent>(fixture.events.events.last())
        assertEquals("finaxis.iam.user.role-assigned", assigned.target)
        assertEquals("finaxis.iam.user.role-revoked", revoked.target)
        assertEquals("USER_ROLE_ASSIGNMENT", assigned.aggregateType)
        assertEquals("${fixture.userId}:${fixture.roleId}:TENANT", assigned.aggregateId)
        assertEquals("INACTIVE", assigned.fromState)
        assertEquals("ACTIVE", assigned.toState)
        assertEquals("ACTIVE", revoked.fromState)
        assertEquals("REVOKED", revoked.toState)
        assertEquals(
            listOf("user.assign_role", "user.revoke_role"),
            fixture.audits.events.map(AuditEvent::action),
        )
    }

    /** Leaves persistence, audit, and event state unchanged when no active assignment exists. */
    @Test
    fun `revoking nonexistent assignment is a no op`() {
        fixture.service.revokeRoleFromUser(fixture.revokeRole())

        assertTrue(fixture.events.events.isEmpty())
        assertTrue(fixture.audits.events.isEmpty())
        assertEquals(0, fixture.persistence.revokeCalls)
    }

    /** Builds deterministic dependencies for role service tests. */
    private class Fixture {
        val organisationId: UUID = uuidV7()
        val roleId: UUID = uuidV7()
        val userId: UUID = uuidV7()
        val branchId: UUID = uuidV7()
        val actorId: UUID = uuidV7()
        val membershipId: UUID = uuidV7()
        val permissionId: UUID = uuidV7()
        val clock: Clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
        val persistence = AdministrationFake()
        val assignmentId: UUID = persistence.assignmentId
        val events = EventCapture()
        val audits = AuditCapture()
        val cacheManager = ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME)
        val permissionCache =
            requireNotNull(cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME))
        val resolver = EffectivePermissionResolver(PermissionQueriesFake(), cacheManager)
        val invalidator = PermissionCacheInvalidator(cacheManager, resolver)
        val permissionGuard = mock(PermissionGuard::class.java)
        val service =
            RoleManagementService(
                persistence,
                AuditService(audits, clock),
                events,
                invalidator,
                permissionGuard,
            )

        fun createRole(
            roleCode: String = "OPS",
            roleName: String = "Operations",
            requestId: String? = null,
        ) = CreateTenantRole(organisationId, roleCode, roleName, null, actorId, requestId)

        fun updateRole(
            roleName: String = "Updated",
            requestId: String? = null,
        ) = UpdateTenantRole(organisationId, roleId, roleName, null, actorId, requestId)

        fun activateRole(requestId: String? = null) =
            ActivateRole(organisationId, roleId, actorId, requestId)

        fun deactivateRole(requestId: String? = null) =
            DeactivateRole(organisationId, roleId, actorId, requestId)

        fun removePermission(requestId: String? = null) =
            RemovePermissionFromRole(organisationId, roleId, "tenant.approve", actorId, requestId)

        fun assignPermission(requestId: String? = null) =
            AssignPermissionToRole(organisationId, roleId, "tenant.approve", actorId, requestId)

        fun assignRole(
            scopeType: RoleScopeType = RoleScopeType.TENANT,
            branchId: UUID? = null,
            requestId: String? = null,
        ) = AssignRoleToUser(
            organisationId,
            userId,
            roleId,
            scopeType,
            branchId,
            actorId,
            requestId,
        )

        fun revokeRole() =
            RevokeRoleFromUser(organisationId, userId, roleId, RoleScopeType.TENANT, null, actorId)

        fun tenantRole() = RoleSnapshot(roleId, "OPS", false, RoleStatus.ACTIVE, 0)

        fun systemRole() = RoleSnapshot(roleId, "SYSTEM", true, RoleStatus.ACTIVE, 0)

        fun activeMembership() = MembershipSnapshot(membershipId, MembershipStatus.ACTIVE, "STAFF")

        fun primePermissionCache(membershipId: UUID) {
            resolver.effectivePermissions(membershipId)
        }
    }

    /** Captures published transition events. */
    private class EventCapture : TransitionEventPublisher {
        val events = mutableListOf<TransitionEvent>()

        override fun publish(event: TransitionEvent) {
            events += event
        }
    }

    /** Captures audit records without a database. */
    private class AuditCapture : AuditEventRepository {
        val events = mutableListOf<AuditEvent>()

        override fun save(event: AuditEvent) {
            events += event
        }
    }

    /** Supplies cache-resolution data used only to register a cache key. */
    private class PermissionQueriesFake : PermissionResolutionQueries {
        override fun membershipStatus(membershipId: UUID): MembershipStatus =
            MembershipStatus.ACTIVE

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> = setOf("tenant.approve")

        override fun directPermissionEffects(membershipId: UUID) =
            emptyList<PermissionEffectAssignment>()

        override fun lockedBreakGlassGrant(
            membershipId: UUID,
            permissionCode: String,
        ) = false
    }

    /** In-memory port implementation that records role-administration side effects. */
    private class AdministrationFake : IamAdministrationPersistence {
        val roleCodes = mutableSetOf<Pair<UUID, String>>()
        val roles = mutableMapOf<UUID, RoleSnapshot>()
        val permissions = mutableMapOf<String, UUID>()
        val riskLevels = mutableMapOf<String, String>()
        val memberships = mutableMapOf<UUID, MembershipSnapshot>()
        val membershipsWithRole = mutableMapOf<UUID, List<UUID>>()
        val activeBranchAssignments = mutableSetOf<UUID>()
        val activeAssignments = mutableListOf<UUID>()
        val updatedRoles = mutableListOf<Pair<UUID, String?>>()
        val updatedRowVersions = mutableListOf<Long>()
        val statusChanges = mutableListOf<Pair<UUID, RoleStatus>>()
        val statusRowVersions = mutableListOf<Long>()
        val grantedPermissions = mutableListOf<UUID>()
        val removedPermissions = mutableListOf<UUID>()
        val assignedScopes = mutableListOf<Pair<RoleScopeType, UUID?>>()
        val assignmentId: UUID = uuidV7()
        var currentOrganisationStatus: OrganisationStatus? = OrganisationStatus.ACTIVE
        var revokeCalls = 0

        override fun roleCodeExists(
            organisationId: UUID,
            roleCode: String,
        ) = (organisationId to roleCode) in roleCodes

        override fun findRole(
            organisationId: UUID,
            roleId: UUID,
        ): RoleSnapshot? = roles[roleId]

        override fun createRole(
            organisationId: UUID,
            roleCode: String,
            roleName: String,
            description: String?,
            actorId: UUID,
        ): UUID = uuidV7().also { roleCodes += organisationId to roleCode }

        override fun updateRole(
            organisationId: UUID,
            roleId: UUID,
            roleName: String?,
            description: String?,
            rowVersion: Long,
            actorId: UUID,
        ) {
            updatedRoles += roleId to roleName
            updatedRowVersions += rowVersion
        }

        override fun setRoleStatus(
            organisationId: UUID,
            roleId: UUID,
            status: RoleStatus,
            rowVersion: Long,
            actorId: UUID,
        ) {
            statusChanges += roleId to status
            statusRowVersions += rowVersion
        }

        override fun permissionIdByCode(permissionCode: String): UUID? = permissions[permissionCode]

        override fun permissionRiskLevel(permissionCode: String): String? =
            riskLevels[permissionCode]

        override fun grantPermission(
            organisationId: UUID,
            roleId: UUID,
            permissionId: UUID,
            actorId: UUID,
        ): Boolean {
            grantedPermissions += permissionId
            return true
        }

        override fun removePermission(
            organisationId: UUID,
            roleId: UUID,
            permissionId: UUID,
            actorId: UUID,
        ): Boolean {
            removedPermissions += permissionId
            return true
        }

        override fun membership(
            organisationId: UUID,
            userId: UUID,
        ): MembershipSnapshot? = memberships[userId]

        override fun organisationStatus(organisationId: UUID): OrganisationStatus? =
            currentOrganisationStatus

        override fun hasActiveBranchAssignment(
            organisationId: UUID,
            userId: UUID,
            branchId: UUID,
        ) = branchId in activeBranchAssignments

        override fun activeRoleAssignment(
            organisationId: UUID,
            userId: UUID,
            roleId: UUID,
            scopeType: RoleScopeType,
            branchId: UUID?,
        ): UUID? = activeAssignments.firstOrNull()

        override fun assignRole(
            organisationId: UUID,
            userId: UUID,
            roleId: UUID,
            scopeType: RoleScopeType,
            branchId: UUID?,
            actorId: UUID,
        ): UUID {
            assignedScopes += scopeType to branchId
            return assignmentId
        }

        override fun revokeRole(
            organisationId: UUID,
            userId: UUID,
            roleId: UUID,
            scopeType: RoleScopeType,
            branchId: UUID?,
            actorId: UUID,
        ): Boolean {
            revokeCalls += 1
            return true
        }

        override fun membershipIdsWithRole(
            organisationId: UUID,
            roleId: UUID,
        ): List<UUID> = membershipsWithRole[roleId].orEmpty()
    }
}
