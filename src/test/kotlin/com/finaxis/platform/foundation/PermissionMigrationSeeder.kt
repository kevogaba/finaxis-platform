package com.finaxis.platform.foundation

import com.finaxis.platform.common.id.uuidV7
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** A seeded user and the membership that carries its permissions in one organisation. */
internal data class SeededPrincipal(
    val userId: UUID,
    val membershipId: UUID,
)

/**
 * Seeds the minimum a permission-migration test needs - an organisation, roles with grants,
 * members with direct overrides and role assignments - through plain SQL, so a test that runs
 * inside a transaction it rolls back leaves nothing behind.
 */
internal class PermissionMigrationSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val label: String,
) {
    fun organisation(): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'Migration Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "$label-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        return id
    }

    fun role(
        organisationId: UUID,
        roleCode: String,
        vararg permissionCodes: String,
    ): UUID {
        val roleId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO role (
                id, organisation_id, role_code, role_name, system_role, status, created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            roleId,
            organisationId,
            roleCode,
            roleCode,
        )
        permissionCodes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO role_permission (
                    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
                )
                SELECT ?, ?, id, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
                """.trimIndent(),
                organisationId,
                roleId,
                code,
            )
        }
        return roleId
    }

    fun member(
        organisationId: UUID,
        username: String,
    ): SeededPrincipal {
        val userId = uuidV7()
        val membershipId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO user_account (id, username, email, display_name, status, created_at,
                                      updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            userId,
            "$label-$username",
            "$label-$username@example.test",
            username,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                id, organisation_id, user_id, membership_status, membership_type, joined_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', 'STAFF', NOW(), NOW(), NOW())
            """.trimIndent(),
            membershipId,
            organisationId,
            userId,
        )
        return SeededPrincipal(userId, membershipId)
    }

    fun override(
        organisationId: UUID,
        membershipId: UUID,
        permissionCode: String,
        effect: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO membership_permission (
                organisation_id, membership_id, permission_id, effect, granted_at, created_at,
                updated_at
            )
            SELECT ?, ?, id, ?, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
            """.trimIndent(),
            organisationId,
            membershipId,
            effect,
            permissionCode,
        )
    }

    /** Assigns [roleId] to the principal at tenant scope. */
    fun assign(
        organisationId: UUID,
        principal: SeededPrincipal,
        roleId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_role_assignment (
                id, organisation_id, user_id, role_id, scope_type, status, assigned_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'TENANT', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            uuidV7(),
            organisationId,
            principal.userId,
            roleId,
        )
    }

    private companion object {
        const val TENANT_CODE_SUFFIX = 12
    }
}
