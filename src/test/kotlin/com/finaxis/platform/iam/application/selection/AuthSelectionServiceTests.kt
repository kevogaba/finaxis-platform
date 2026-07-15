package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthSelectionServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC)
    private val contextService =
        ActiveOrganisationContextService(
            ActiveOrganisationContextProperties(secret = "test-secret-with-enough-length-32bytes"),
            clock,
        )

    @Test
    fun `select organisation returns signed context for active membership`() {
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
        val service = AuthSelectionService(lookup, contextService)

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertEquals(organisationId, response.organisationId)
        assertEquals(membershipId, response.membershipId)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            contextService.verify(response.contextToken),
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
                contextService,
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
                contextService,
            )

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertEquals(branchId, response.branchId)
        assertEquals(false, response.requiresBranchSelection)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            contextService.verify(response.contextToken),
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
                contextService,
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
                contextService,
            )

        val response = service.selectOrganisation("keycloak-subject", organisationId)

        assertNull(response.branchId)
        assertEquals(true, response.requiresBranchSelection)
        assertEquals(listOf(firstBranchId, secondBranchId), response.assignedBranchIds)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            contextService.verify(response.contextToken),
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
                contextService,
            )

        val response = service.selectBranch("keycloak-subject", branchId, currentContext)

        assertEquals(branchId, response.branchId)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            contextService.verify(response.contextToken),
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
                contextService,
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), currentContext)
        }
    }

    @Test
    fun `select branch rejects missing organisation context`() {
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = uuidV7()), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), currentContext = null)
        }
    }

    @Test
    fun `select branch rejects context for another authenticated user`() {
        val userId = uuidV7()
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId), contextService)
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
                contextService,
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
                contextService,
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
                contextService,
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
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId), contextService)

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
        val service = AuthSelectionService(FakeMembershipLookup(), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", uuidV7(), context)
        }
    }

    @Test
    fun `select organisation rejects non member`() {
        val service = AuthSelectionService(FakeMembershipLookup(), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", uuidV7())
        }
    }

    @Test
    fun `select organisation rejects registered user without membership`() {
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = uuidV7()), contextService)

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
                contextService,
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", organisationId)
        }
    }
}

private class FakeMembershipLookup(
    private val userId: UUID? = null,
    private val membership: MembershipSelection? = null,
    private val branchIds: List<UUID> = emptyList(),
    private val organisationStatus: OrganisationStatus? = OrganisationStatus.ACTIVE,
) : MembershipSelectionLookup {
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = userId

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
