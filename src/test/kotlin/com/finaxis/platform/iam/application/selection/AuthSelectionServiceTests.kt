package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthSelectionServiceTests {
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
        val service = AuthSelectionService(lookup)

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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = uuidV7()))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), currentContext = null)
        }
    }

    @Test
    fun `select branch rejects context for another authenticated user`() {
        val userId = uuidV7()
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId))
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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId))

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
        val service = AuthSelectionService(FakeMembershipLookup())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), context)
        }
    }

    @Test
    fun `select organisation rejects non member`() {
        val service = AuthSelectionService(FakeMembershipLookup())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", uuidV7())
        }
    }

    @Test
    fun `select organisation rejects registered user without membership`() {
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = uuidV7()))

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", uuidV7())
        }
    }

    @Test
    fun `select organisation rejects suspended membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val service =
            AuthSelectionService(
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
    fun `organisation replay rejects a changed assignment set`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val currentBranch = uuidV7()
        val context = ActiveOrganisationContext(userId, organisationId, membershipId)
        val service =
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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
            AuthSelectionService(
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

private class FakeMembershipLookup(
    private val userId: UUID? = null,
    private val userStatus: UserStatus? = UserStatus.ACTIVE,
    private val membership: MembershipSelection? = null,
    private val branchIds: List<UUID> = emptyList(),
    private val organisationStatus: OrganisationStatus? = OrganisationStatus.ACTIVE,
) : MembershipSelectionLookup {
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = userId

    override fun userStatus(userId: UUID): UserStatus? = userStatus

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? = membership

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? = organisationStatus

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = branchIds

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = branchId in branchIds
}
