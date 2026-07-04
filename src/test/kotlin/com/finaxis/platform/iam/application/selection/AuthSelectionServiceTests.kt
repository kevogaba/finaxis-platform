package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.domain.MembershipStatus
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
            ActiveOrganisationContextProperties(secret = "test-secret-with-enough-length"),
            clock,
        )

    @Test
    fun `select organisation returns signed context for active membership`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
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
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
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
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
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
    fun `select organisation asks client to select branch when multiple branches are assigned`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val firstBranchId = UUID.randomUUID()
        val secondBranchId = UUID.randomUUID()
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
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
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
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
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
                    branchIds = listOf(UUID.randomUUID()),
                ),
                contextService,
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", UUID.randomUUID(), currentContext)
        }
    }

    @Test
    fun `select branch rejects missing organisation context`() {
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = UUID.randomUUID()), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", UUID.randomUUID(), currentContext = null)
        }
    }

    @Test
    fun `select branch rejects context for another authenticated user`() {
        val userId = UUID.randomUUID()
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId), contextService)
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", UUID.randomUUID(), context)
        }
    }

    @Test
    fun `select branch rejects inactive membership`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
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
    fun `select branch rejects missing membership`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val service = AuthSelectionService(FakeMembershipLookup(userId = userId), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                UUID.randomUUID(),
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )
        }
    }

    @Test
    fun `select branch rejects missing registered user`() {
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val service = AuthSelectionService(FakeMembershipLookup(), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch("keycloak-subject", UUID.randomUUID(), context)
        }
    }

    @Test
    fun `select organisation rejects non member`() {
        val service = AuthSelectionService(FakeMembershipLookup(), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", UUID.randomUUID())
        }
    }

    @Test
    fun `select organisation rejects registered user without membership`() {
        val service =
            AuthSelectionService(FakeMembershipLookup(userId = UUID.randomUUID()), contextService)

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectOrganisation("keycloak-subject", UUID.randomUUID())
        }
    }

    @Test
    fun `select organisation rejects suspended membership`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val service =
            AuthSelectionService(
                FakeMembershipLookup(
                    userId = userId,
                    membership =
                        MembershipSelection(
                            UUID.randomUUID(),
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
) : MembershipSelectionLookup {
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = userId

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? = membership

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = branchIds

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = branchId in branchIds
}
