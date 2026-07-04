package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC outbound adapter for authenticated user profile read models.
 */
@Component
class JdbcUserProfileLookup(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserProfileLookup {
    /**
     * Loads the selected organisation details.
     */
    override fun organisation(organisationId: UUID): ProfileOrganisation? =
        jdbc
            .query(
                """
                SELECT id, code, name, status
                FROM organisation
                WHERE id = :organisationId
                """,
                params("organisationId", organisationId),
            ) { row, _ -> profileOrganisation(row) }
            .firstOrNull()

    /**
     * Loads the selected membership details.
     */
    override fun membership(membershipId: UUID): ProfileMembership? =
        jdbc
            .query(
                """
                SELECT id, status
                FROM organisation_membership
                WHERE id = :membershipId
                """,
                params("membershipId", membershipId),
            ) { row, _ -> profileMembership(row) }
            .firstOrNull()

    /**
     * Lists branches assigned to the selected membership.
     */
    override fun assignedBranches(membershipId: UUID): List<ProfileBranch> =
        jdbc.query(
            """
            SELECT b.id, b.code, b.name, b.status
            FROM membership_branch_scope scope
            JOIN branch b ON b.id = scope.branch_id
            WHERE scope.membership_id = :membershipId
            ORDER BY b.code
            """,
            params("membershipId", membershipId),
        ) { row, _ -> profileBranch(row) }

    /**
     * Lists roles assigned to the selected membership.
     */
    override fun assignedRoles(membershipId: UUID): List<ProfileRole> =
        jdbc.query(
            """
            SELECT r.id, r.code, r.name, r.status
            FROM membership_role membership_role
            JOIN role r ON r.id = membership_role.role_id
            WHERE membership_role.membership_id = :membershipId
            ORDER BY r.code
            """,
            params("membershipId", membershipId),
        ) { row, _ -> profileRole(row) }

    private fun params(
        name: String,
        value: UUID,
    ): MapSqlParameterSource = MapSqlParameterSource(name, value)

    private fun profileOrganisation(row: ResultSet): ProfileOrganisation =
        ProfileOrganisation(
            id = row.getObject("id", UUID::class.java),
            code = row.getString("code"),
            name = row.getString("name"),
            status = OrganisationStatus.valueOf(row.getString("status")),
        )

    private fun profileMembership(row: ResultSet): ProfileMembership =
        ProfileMembership(
            id = row.getObject("id", UUID::class.java),
            status = MembershipStatus.valueOf(row.getString("status")),
        )

    private fun profileBranch(row: ResultSet): ProfileBranch =
        ProfileBranch(
            id = row.getObject("id", UUID::class.java),
            code = row.getString("code"),
            name = row.getString("name"),
            status = row.getString("status"),
        )

    private fun profileRole(row: ResultSet): ProfileRole =
        ProfileRole(
            id = row.getObject("id", UUID::class.java),
            code = row.getString("code"),
            name = row.getString("name"),
            status = RoleStatus.valueOf(row.getString("status")),
        )
}
