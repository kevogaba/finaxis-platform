package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.adapter.inbound.security.SessionActiveOrganisationContextResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.application.profile.UserProfileService
import com.finaxis.platform.iam.application.selection.AuthSelectionService
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
private const val TEST_CONTEXT_SECRET = "test-secret-with-enough-length-32bytes"
private val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

class WebAdapterUnitTests {
    @Test
    fun `auth controller returns selected organisation response`() {
        val selectedOrganisationId = uuidV7()
        val selectedMembershipId = uuidV7()
        val response =
            AuthController(
                selectionService(
                    StaticMembershipLookup(selectedOrganisationId, selectedMembershipId),
                ),
            ).selectOrganisation(
                jwtAuthentication(),
                SelectOrganisationRequest(selectedOrganisationId),
                MockHttpSession(),
            )

        assertEquals(selectedOrganisationId, response.organisationId)
        assertEquals(selectedMembershipId, response.membershipId)
        assertTrue(response.contextToken.isNotBlank())
        assertEquals("X-Active-Organisation-Context", response.contextHeader)
        assertEquals(false, response.requiresBranchSelection)
    }

    @Test
    fun `auth controller stores selected branch in browser session`() {
        val selectedOrganisationId = uuidV7()
        val selectedMembershipId = uuidV7()
        val branchId = uuidV7()
        val lookup =
            StaticMembershipLookup(selectedOrganisationId, selectedMembershipId, listOf(branchId))
        val session =
            MockHttpSession().apply {
                setAttribute(
                    SessionActiveOrganisationContextResolver.ATTRIBUTE,
                    ActiveOrganisationContext(
                        lookup.userId,
                        selectedOrganisationId,
                        selectedMembershipId,
                    ),
                )
            }

        val response =
            AuthController(selectionService(lookup)).selectBranch(
                jwtAuthentication(),
                SelectBranchRequest(branchId),
                session,
            )

        assertEquals(branchId, response.branchId)
        assertEquals(
            ActiveOrganisationContext(
                lookup.userId,
                selectedOrganisationId,
                selectedMembershipId,
                branchId,
            ),
            session.getAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE),
        )
    }

    @Test
    fun `user profile controller returns active tenant profile`() {
        val principal =
            principal(setOf("iam.profile.read", "iam.user.invite"), HEAD_OFFICE_BRANCH_ID)
        val response =
            UserProfileController(
                UserProfileService(StaticUserProfileLookup(principal)),
            ).me(principal)

        assertEquals(principal.userId, response.userId)
        assertEquals("FINAXIS-LOCAL", response.organisation.code)
        assertEquals(HEAD_OFFICE_BRANCH_ID, response.selectedBranch?.id)
        assertEquals(listOf("iam.profile.read", "iam.user.invite"), response.permissions)
        assertEquals(listOf("local-admin"), response.roles.map { it.code })
    }

    private fun selectionService(lookup: MembershipSelectionLookup): AuthSelectionService =
        AuthSelectionService(
            lookup,
            ActiveOrganisationContextService(
                ActiveOrganisationContextProperties(TEST_CONTEXT_SECRET),
                Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC),
            ),
        )

    private fun jwtAuthentication(): JwtAuthenticationToken =
        JwtAuthenticationToken(
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .build(),
        )

    private fun principal(
        permissions: Set<String>,
        branchId: UUID?,
    ): AppPrincipal =
        AppPrincipal(
            userId = uuidV7(),
            keycloakSubject = "subject",
            organisationId = ORGANISATION_ID,
            membershipId = MEMBERSHIP_ID,
            branchId = branchId,
            email = "admin@finaxis.local",
            fullName = "Local Admin",
            permissions = permissions,
        )
}

private class StaticMembershipLookup(
    private val organisationId: UUID,
    private val membershipId: UUID,
    private val branchIds: List<UUID> = emptyList(),
) : MembershipSelectionLookup {
    val userId = uuidV7()

    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID = userId

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? =
        MembershipSelection(membershipId, userId, organisationId, MembershipStatus.ACTIVE)
            .takeIf { this.userId == userId && this.organisationId == organisationId }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? =
        OrganisationStatus.ACTIVE.takeIf { this.organisationId == organisationId }

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = branchIds

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = branchId in branchIds
}

private class StaticUserProfileLookup(
    private val principal: AppPrincipal,
) : UserProfileLookup {
    override fun organisation(organisationId: UUID): ProfileOrganisation? =
        ProfileOrganisation(
            organisationId,
            "FINAXIS-LOCAL",
            "Finaxis Local Organisation",
            OrganisationStatus.ACTIVE,
        ).takeIf { organisationId == principal.organisationId }

    override fun membership(membershipId: UUID): ProfileMembership? =
        ProfileMembership(membershipId, MembershipStatus.ACTIVE).takeIf {
            membershipId ==
                principal.membershipId
        }

    override fun assignedBranches(membershipId: UUID): List<ProfileBranch> =
        listOf(ProfileBranch(HEAD_OFFICE_BRANCH_ID, "HQ", "Head Office", "ACTIVE"))
            .takeIf { membershipId == principal.membershipId }
            .orEmpty()

    override fun assignedRoles(membershipId: UUID): List<ProfileRole> =
        listOf(ProfileRole(uuidV7(), "local-admin", "Local Administrator", RoleStatus.ACTIVE))
            .takeIf { membershipId == principal.membershipId }
            .orEmpty()
}
