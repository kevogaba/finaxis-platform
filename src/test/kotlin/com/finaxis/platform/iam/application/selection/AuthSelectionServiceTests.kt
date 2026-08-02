package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.OrganisationSelection
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthSelectionServiceTests {
    @Test
    fun `available organisations returns only selectable active memberships`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                organisationSelections =
                    listOf(
                        OrganisationSelection(
                            membershipId = membershipId,
                            organisationId = organisationId,
                            tenantCode = "acme-corp",
                            displayName = "Acme Financial Services",
                            organisationStatus = OrganisationStatus.ACTIVE,
                            membershipStatus = MembershipStatus.ACTIVE,
                        ),
                    ),
            )

        val response = serviceWith(lookup).availableOrganisations("keycloak-subject", 0, 25)

        assertEquals(listOf(organisationId), response.items.map { it.organisationId })
        assertEquals(1, response.totalItems)
    }

    @Test
    fun `select organisation returns active context for active membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
            )
        val service = serviceWith(lookup)

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertEquals(organisationId, response.organisationId)
        assertEquals(membershipId, response.membershipId)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            response.context,
        )
        assertEquals(false, response.requiresBranchSelection)
        assertEquals(emptyList(), response.assignedBranchIds)
    }

    @Test
    fun `select organisation returns active context for browser session storage`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                ),
            )

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            response.context,
        )
    }

    @Test
    fun `select organisation auto selects the only assigned branch`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(branchId),
                ),
            )

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertEquals(branchId, response.branchId)
        assertEquals(false, response.requiresBranchSelection)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            response.context,
        )
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            response.context,
        )
    }

    @Test
    fun `select organisation rejects inactive organisation`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    organisationStatus = OrganisationStatus.SUSPENDED,
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", organisationId)
        }
    }

    @Test
    fun `select organisation asks client to select branch when multiple branches are assigned`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val firstBranchId = uuidV7()
        val secondBranchId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(firstBranchId, secondBranchId),
                ),
            )

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertNull(response.branchId)
        assertEquals(true, response.requiresBranchSelection)
        assertEquals(listOf(firstBranchId, secondBranchId), response.assignedBranchIds)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            response.context,
        )
    }

    @Test
    fun `select branch stores selected assigned branch in existing organisation context`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val currentContext = ActiveOrganisationContext(userId, organisationId, membershipId)
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(branchId),
                ),
            )

        val response = service.selectBranch("keycloak-subject", branchId, currentContext)

        assertEquals(branchId, response.branchId)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            response.context,
        )
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            response.context,
        )
    }

    @Test
    fun `select branch rejects unassigned branch`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val currentContext = ActiveOrganisationContext(userId, organisationId, membershipId)
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(uuidV7()),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), currentContext)
        }
    }

    @Test
    fun `select branch rejects missing organisation context`() {
        val service = serviceWith(FakeMembershipLookup(userId = uuidV7()))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), currentContext = null)
        }
    }

    @Test
    fun `select branch rejects context for another authenticated user`() {
        val userId = uuidV7()
        val service = serviceWith(FakeMembershipLookup(userId = userId))
        val context =
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), context)
        }
    }

    @Test
    fun `select branch rejects inactive membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.SUSPENDED,
                        ),
                    branchIds = listOf(branchId),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                branchId,
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )
        }
    }

    @Test
    fun `select branch rejects membership that does not match active context`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val contextMembershipId = uuidV7()
        val lookupMembershipId = uuidV7()
        val branchId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            lookupMembershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(branchId),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                branchId,
                ActiveOrganisationContext(userId, organisationId, contextMembershipId),
            )
        }
    }

    @Test
    fun `select branch rejects inactive organisation`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(branchId),
                    organisationStatus = OrganisationStatus.SUSPENDED,
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                branchId,
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )
        }
    }

    @Test
    fun `select branch rejects missing membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val service = serviceWith(FakeMembershipLookup(userId = userId))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                uuidV7(),
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )
        }
    }

    @Test
    fun `select branch rejects missing registered user`() {
        val context =
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())
        val service = serviceWith(FakeMembershipLookup())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), context)
        }
    }

    @Test
    fun `select organisation rejects non member`() {
        val service = serviceWith(FakeMembershipLookup())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", uuidV7())
        }
    }

    @Test
    fun `select organisation rejects registered user without membership`() {
        val service =
            serviceWith(FakeMembershipLookup(userId = uuidV7()))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", uuidV7())
        }
    }

    @Test
    fun `select organisation rejects suspended membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            uuidV7(),
                            userId,
                            organisationId,
                            MembershipStatus.SUSPENDED,
                        ),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", organisationId)
        }
    }

    @Test
    fun `select organisation denies when auth select_organisation permission is missing`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
            )
        // Resolver returns empty permissions → permission absent
        val service = serviceWith(lookup, emptyPermissions = true)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", organisationId)
        }
    }

    @Test
    fun `select branch denies when auth select_branch permission is missing`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
                branchIds = listOf(branchId),
            )
        // Resolver returns only auth.select_organisation but NOT auth.select_branch
        val service = serviceWith(lookup, grantedPermissions = setOf("auth.select_organisation"))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                branchId,
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )
        }
    }

    @Test
    fun `organisation replay rejects a changed assignment set`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val currentBranch = uuidV7()
        val context = ActiveOrganisationContext(userId, organisationId, membershipId)
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(currentBranch),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.revalidateOrganisationReplay(
                "keycloak-subject",
                context,
                expectedAssignedBranchIds = listOf(uuidV7()),
            )
        }
    }

    @Test
    fun `branch replay remains valid when membership has multiple assignments`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val selectedBranch = uuidV7()
        val context =
            ActiveOrganisationContext(userId, organisationId, membershipId, selectedBranch)
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(selectedBranch, uuidV7()),
                ),
            )

        service.revalidateBranchReplay("keycloak-subject", context)
    }

    @Test
    fun `select organisation rejects a suspended application user`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    userStatus = UserStatus.SUSPENDED,
                    membership =
                        MembershipSelection(
                            uuidV7(),
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", organisationId)
        }
    }

    @Test
    fun `organisation and branch replay reject an ineligible application user`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val context = ActiveOrganisationContext(userId, organisationId, membershipId, branchId)
        val service =
            serviceWith(
                FakeMembershipLookup(
                    userId = userId,
                    userStatus = UserStatus.DEACTIVATED,
                    membership =
                        MembershipSelection(
                            membershipId,
                            userId,
                            organisationId,
                            MembershipStatus.ACTIVE,
                        ),
                    branchIds = listOf(branchId),
                ),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.revalidateOrganisationReplay(
                "keycloak-subject",
                context,
                listOf(branchId),
            )
        }
        assertThrows<OrganisationSelectionDeniedException> {
            service.revalidateBranchReplay("keycloak-subject", context)
        }
    }
}

private fun serviceWith(
    lookup: FakeMembershipLookup,
    emptyPermissions: Boolean = false,
    grantedPermissions: Set<String> = setOf("auth.select_organisation", "auth.select_branch"),
): AuthSelectionService {
    val perms: PermissionResolutionQueries =
        if (emptyPermissions) {
            object : PermissionResolutionQueries {
                override fun membershipStatus(membershipId: UUID) = MembershipStatus.ACTIVE

                override fun rolePermissionCodes(
                    membershipId: UUID,
                    branchId: UUID?,
                ) = emptySet<String>()

                override fun directPermissionEffects(membershipId: UUID) =
                    emptyList<PermissionEffectAssignment>()
            }
        } else {
            object : PermissionResolutionQueries {
                override fun membershipStatus(membershipId: UUID) = MembershipStatus.ACTIVE

                override fun rolePermissionCodes(
                    membershipId: UUID,
                    branchId: UUID?,
                ) = grantedPermissions

                override fun directPermissionEffects(membershipId: UUID) =
                    emptyList<PermissionEffectAssignment>()
            }
        }
    val resolver = EffectivePermissionResolver(perms, ConcurrentMapCacheManager())
    val cache = RequestPermissionCache(resolver)
    val authorizationService = AuthorizationService(lookup, cache)
    return AuthSelectionService(lookup, authorizationService)
}

class AuthSelectionReplayPermissionTests {
    @Test
    fun `organisation replay rejects when permission is revoked after original selection`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
                branchIds = listOf(branchId),
            )
        val original = serviceWith(lookup).selectOrganisation("keycloak-subject", organisationId)
        val replayService =
            serviceWith(
                lookup,
                grantedPermissions = setOf("auth.select_branch"),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            replayService.revalidateOrganisationReplay(
                "keycloak-subject",
                original.context,
                original.assignedBranchIds,
            )
        }
    }

    @Test
    fun `organisation replay remains valid when permission is still held`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
            )
        val original = serviceWith(lookup).selectOrganisation("keycloak-subject", organisationId)

        serviceWith(lookup).revalidateOrganisationReplay(
            "keycloak-subject",
            original.context,
            original.assignedBranchIds,
        )
    }

    @Test
    fun `branch replay rejects when permission is revoked after original selection`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val context = ActiveOrganisationContext(userId, organisationId, membershipId)
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
                branchIds = listOf(branchId),
            )
        val original = serviceWith(lookup).selectBranch("keycloak-subject", branchId, context)
        val replayService =
            serviceWith(
                lookup,
                grantedPermissions = setOf("auth.select_organisation"),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            replayService.revalidateBranchReplay("keycloak-subject", original.context)
        }
    }

    @Test
    fun `branch replay remains valid when permission is still held`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val context = ActiveOrganisationContext(userId, organisationId, membershipId)
        val lookup =
            FakeMembershipLookup(
                userId = userId,
                membership =
                    MembershipSelection(
                        membershipId,
                        userId,
                        organisationId,
                        MembershipStatus.ACTIVE,
                    ),
                branchIds = listOf(branchId),
            )
        val original = serviceWith(lookup).selectBranch("keycloak-subject", branchId, context)

        serviceWith(lookup).revalidateBranchReplay("keycloak-subject", original.context)
    }
}

private class FakeMembershipLookup(
    private val userId: UUID? = null,
    private val userStatus: UserStatus? = UserStatus.ACTIVE,
    private val membership: MembershipSelection? = null,
    private val branchIds: List<UUID> = emptyList(),
    private val organisationStatus: OrganisationStatus? = OrganisationStatus.ACTIVE,
    private val organisationSelections: List<OrganisationSelection> = emptyList(),
) : MembershipSelectionLookup {
    override fun findOrganisationSelections(
        userId: UUID,
        page: Int,
        size: Int,
    ) = com.finaxis.platform.iam.application.port.outbound.OrganisationSelectionPage(
        organisationSelections,
        organisationSelections.size.toLong(),
    )

    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = userId

    override fun userStatus(userId: UUID): UserStatus? = userStatus

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? =
        membership
            ?: organisationSelections
                .firstOrNull { it.organisationId == organisationId }
                ?.let {
                    MembershipSelection(
                        membershipId = it.membershipId,
                        userId = userId,
                        organisationId = it.organisationId,
                        status = it.membershipStatus,
                    )
                }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? = organisationStatus

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = branchIds

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = branchId in branchIds
}
