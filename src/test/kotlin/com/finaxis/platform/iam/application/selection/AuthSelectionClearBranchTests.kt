package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.domain.MembershipStatus
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthSelectionClearBranchTests {
    @Test
    fun `select branch without a branch id clears the pinned branch for a single branch user`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val pinnedContext =
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId)
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

        val response = service.selectBranch("keycloak-subject", null, pinnedContext)

        assertNull(response.branchId)
        assertEquals(
            ActiveOrganisationContext(userId, organisationId, membershipId, branchId = null),
            response.context,
        )
    }

    @Test
    fun `select branch without a branch id still requires the branch selection permission`() {
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
                grantedPermissions = setOf("auth.select_organisation"),
            )

        assertThrows<OrganisationSelectionDeniedException> {
            service.selectBranch(
                "keycloak-subject",
                null,
                ActiveOrganisationContext(userId, organisationId, membershipId, uuidV7()),
            )
        }
    }

    @Test
    fun `branch replay accepts a cleared branch selection`() {
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
        val original =
            serviceWith(lookup).selectBranch(
                "keycloak-subject",
                null,
                ActiveOrganisationContext(userId, organisationId, membershipId, uuidV7()),
            )

        serviceWith(lookup).revalidateBranchReplay("keycloak-subject", original.context)
    }
}
