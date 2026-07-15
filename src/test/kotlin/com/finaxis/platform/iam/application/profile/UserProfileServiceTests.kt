package com.finaxis.platform.iam.application.profile

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class UserProfileServiceTests {
    @Test
    fun `profile assembles selected branch roles and sorted permissions`() {
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val selectedBranchId = uuidV7()
        val otherBranchId = uuidV7()
        val principal =
            principal(
                organisationId = organisationId,
                membershipId = membershipId,
                branchId = selectedBranchId,
                permissions = setOf("logistics.shipment.approve", "iam.profile.read"),
            )
        val lookup =
            FakeUserProfileLookup(
                organisation =
                    ProfileOrganisation(
                        organisationId,
                        "FINAXIS",
                        "Finaxis",
                        OrganisationStatus.ACTIVE,
                    ),
                membership = ProfileMembership(membershipId, MembershipStatus.ACTIVE),
                branches =
                    listOf(
                        ProfileBranch(selectedBranchId, "HQ", "Head Office", "ACTIVE"),
                        ProfileBranch(otherBranchId, "OPS", "Operations", "ACTIVE"),
                    ),
                roles =
                    listOf(
                        ProfileRole(
                            uuidV7(),
                            "local-admin",
                            "Local Administrator",
                            RoleStatus.ACTIVE,
                        ),
                    ),
            )

        val profile = UserProfileService(lookup).profile(principal)

        assertEquals(organisationId, profile.organisation.id)
        assertEquals(membershipId, profile.membership.id)
        assertEquals(selectedBranchId, profile.selectedBranch?.id)
        assertEquals(listOf("iam.profile.read", "logistics.shipment.approve"), profile.permissions)
        assertEquals(listOf("HQ", "OPS"), profile.branches.map { it.code })
        assertEquals(listOf("local-admin"), profile.roles.map { it.code })
    }

    @Test
    fun `profile rejects missing selected organisation`() {
        val principal = principal()
        val lookup = FakeUserProfileLookup(membership = ProfileMembership(principal.membershipId))

        assertThrows<AccessDeniedException> {
            UserProfileService(lookup).profile(principal)
        }
    }

    @Test
    fun `profile rejects missing selected membership`() {
        val principal = principal()
        val lookup =
            FakeUserProfileLookup(
                organisation = ProfileOrganisation(principal.organisationId),
            )

        assertThrows<AccessDeniedException> {
            UserProfileService(lookup).profile(principal)
        }
    }

    @Test
    fun `profile rejects branch outside assigned branches`() {
        val principal = principal(branchId = uuidV7())
        val lookup =
            FakeUserProfileLookup(
                organisation = ProfileOrganisation(principal.organisationId),
                membership = ProfileMembership(principal.membershipId),
                branches = listOf(ProfileBranch(uuidV7(), "OPS", "Operations", "ACTIVE")),
            )

        assertThrows<AccessDeniedException> {
            UserProfileService(lookup).profile(principal)
        }
    }

    private fun principal(
        organisationId: UUID = uuidV7(),
        membershipId: UUID = uuidV7(),
        branchId: UUID? = null,
        permissions: Set<String> = emptySet(),
    ): AppPrincipal =
        AppPrincipal(
            userId = uuidV7(),
            keycloakSubject = "subject",
            organisationId = organisationId,
            membershipId = membershipId,
            branchId = branchId,
            email = "user@example.com",
            fullName = "Example User",
            permissions = permissions,
        )
}

private class FakeUserProfileLookup(
    private val organisation: ProfileOrganisation? = null,
    private val membership: ProfileMembership? = null,
    private val branches: List<ProfileBranch> = emptyList(),
    private val roles: List<ProfileRole> = emptyList(),
) : UserProfileLookup {
    override fun organisation(organisationId: UUID): ProfileOrganisation? = organisation

    override fun membership(membershipId: UUID): ProfileMembership? = membership

    override fun assignedBranches(membershipId: UUID): List<ProfileBranch> = branches

    override fun assignedRoles(membershipId: UUID): List<ProfileRole> = roles
}

private fun ProfileOrganisation(id: UUID): ProfileOrganisation =
    ProfileOrganisation(id, "FINAXIS", "Finaxis", OrganisationStatus.ACTIVE)

private fun ProfileMembership(id: UUID): ProfileMembership =
    ProfileMembership(id, MembershipStatus.ACTIVE)
