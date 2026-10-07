package com.finaxis.platform.iam.application.selection

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.domain.MembershipStatus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #170: the branch choice is made on distinct branches, never on assignment rows. */
class AuthSelectionBranchChoiceTests {
    private val userId = uuidV7()
    private val organisationId = uuidV7()
    private val membershipId = uuidV7()

    private fun select(branchIds: List<UUID>): SelectOrganisationResult =
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
                branchIds = branchIds,
            ),
        ).selectOrganisation("keycloak-subject", organisationId)

    @Test
    fun `select organisation auto selects a branch that is listed twice`() {
        val branchId = uuidV7()
        val response = select(listOf(branchId, branchId))

        assertEquals(branchId, response.branchId)
        assertEquals(false, response.requiresBranchSelection)
        assertEquals(listOf(branchId), response.assignedBranchIds)
    }

    @Test
    fun `select organisation still asks for a branch when duplicates hide two distinct ones`() {
        val first = uuidV7()
        val second = uuidV7()
        val response = select(listOf(first, first, second, second))

        assertNull(response.branchId)
        assertEquals(true, response.requiresBranchSelection)
        assertEquals(listOf(first, second), response.assignedBranchIds)
    }
}
