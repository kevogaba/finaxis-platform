package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `V38`: the CHECK that every `ACTIVE` or `SUSPENDED` membership has a member number, and the
 * backfill of what `V35` could not number (#332), proved on PostgreSQL.
 *
 * Flyway applied `V38` before these tests ran, so a straggler (a membership an instance older than
 * #287 activated without a number) can no longer be written. The backfill tests therefore work in
 * one transaction that drops the constraint, seeds the stragglers, runs the migration file itself
 * (read from the classpath, so a change to the file is a change to the test), asserts, and rolls
 * everything back, the constraint included. The constraint tests run against the migrated schema
 * and remove their organisations afterwards.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MemberNumberRequiredMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
            jdbcTemplate.update(
                "DELETE FROM user_organisation_membership_transition_log " +
                    "WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update(
                "DELETE FROM user_organisation_membership WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update(
                "DELETE FROM reference_sequence WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", organisationId)
        }
        organisations.clear()
        jdbcTemplate.update("DELETE FROM user_account WHERE username LIKE 'v38-%'")
    }

    @Test
    fun `stragglers are numbered in the order they first became active, after what is taken`() {
        rolledBack {
            val organisationId = seedOrganisation()
            seedMembership(organisationId, "ACTIVE", memberNumber = 1)
            seedMembership(organisationId, "ACTIVE", memberNumber = 2)
            setCounter(organisationId, nextValue = 3)
            // Activated second, first and third: by log row, by log row, and by joined_at alone.
            val active = seedMembership(organisationId, "ACTIVE")
            logActivation(organisationId, active, at = T0.plusHours(2))
            val suspended = seedMembership(organisationId, "SUSPENDED")
            logActivation(organisationId, suspended, at = T0.plusHours(1))
            val revokedAfterJoining =
                seedMembership(organisationId, "REVOKED", joinedAt = T0.plusHours(3))
            val pending = seedMembership(organisationId, "PENDING_APPROVAL")
            val revokedBeforeJoining = seedMembership(organisationId, "REVOKED")

            runMigration()

            assertEquals(3L, memberNumber(suspended))
            assertEquals(4L, memberNumber(active))
            assertEquals(5L, memberNumber(revokedAfterJoining))
            assertNull(memberNumber(pending), "never active: no number is burned")
            assertNull(memberNumber(revokedBeforeJoining), "never active: no number is burned")
            assertEquals(6L, counter(organisationId))
        }
    }

    @Test
    fun `numbering starts after the counter when the counter is ahead of the highest number`() {
        rolledBack {
            val organisationId = seedOrganisation()
            seedMembership(organisationId, "ACTIVE", memberNumber = 1)
            // A number was taken and rolled forward past (a gapless counter never does this, but
            // the backfill must not hand out a value the counter has already passed).
            setCounter(organisationId, nextValue = COUNTER_AHEAD)
            val straggler = seedMembership(organisationId, "ACTIVE")

            runMigration()

            assertEquals(COUNTER_AHEAD.toLong(), memberNumber(straggler))
            assertEquals(COUNTER_AHEAD + 1L, counter(organisationId))
        }
    }

    @Test
    fun `running it again changes nothing`() {
        rolledBack {
            val organisationId = seedOrganisation()
            val straggler = seedMembership(organisationId, "ACTIVE")

            runMigration()
            val first = memberNumber(straggler) to counter(organisationId)
            runMigration()

            assertEquals(first, memberNumber(straggler) to counter(organisationId))
            assertEquals(1, constraintCount())
        }
    }

    @Test
    fun `a numberless ACTIVE or SUSPENDED membership is refused, naming the constraint`() {
        val organisationId = seedOrganisation()
        listOf("ACTIVE", "SUSPENDED").forEach { status ->
            val failure =
                assertThrows<DataIntegrityViolationException>(status) {
                    seedMembership(organisationId, status)
                }
            assertTrue(
                failure.mostSpecificCause.message
                    .orEmpty()
                    .contains(CONSTRAINT),
                "the refusal names the constraint: ${failure.mostSpecificCause.message}",
            )
        }
        val pending = seedMembership(organisationId, "PENDING_APPROVAL")
        seedMembership(organisationId, "REVOKED")

        val failure =
            assertThrows<DataIntegrityViolationException> {
                jdbcTemplate.update(
                    "UPDATE user_organisation_membership SET membership_status = 'ACTIVE' " +
                        "WHERE id = ?",
                    pending,
                )
            }
        assertTrue(
            failure.mostSpecificCause.message
                .orEmpty()
                .contains(CONSTRAINT),
        )
        assertEquals("PENDING_APPROVAL", status(pending))
    }

    @Test
    fun `the constraint is in place and validated, and the column comment says so`() {
        assertEquals(1, constraintCount())
        assertEquals(
            true,
            jdbcTemplate.queryForObject(
                "SELECT convalidated FROM pg_constraint WHERE conname = ?",
                Boolean::class.java,
                CONSTRAINT,
            ),
        )
        val comment =
            jdbcTemplate.queryForObject(
                "SELECT col_description('user_organisation_membership'::regclass, " +
                    "(SELECT attnum FROM pg_attribute WHERE attrelid = " +
                    "'user_organisation_membership'::regclass AND attname = 'member_number'))",
                String::class.java,
            )
        assertTrue(comment.orEmpty().contains("required while ACTIVE or SUSPENDED"), comment)
    }

    /**
     * Runs [body] in one transaction with the CHECK dropped, so a straggler can be seeded, and
     * rolls it all back. [organisations] is cleared too: nothing it seeded survives.
     */
    private fun rolledBack(body: () -> Unit) {
        transactions.executeWithoutResult { status ->
            jdbcTemplate.execute(
                "ALTER TABLE user_organisation_membership DROP CONSTRAINT $CONSTRAINT",
            )
            body()
            status.setRollbackOnly()
        }
        organisations.clear()
    }

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private fun seedOrganisation(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'Member Number Test', 'KE', 'KES', 'Africa/Nairobi', 'ACTIVE', NOW(),
                NOW(), NOW())
            """.trimIndent(),
            id,
            "MN-" + id.toString().take(CODE_SUFFIX),
        )
        organisations += id
        return id
    }

    private fun seedMembership(
        organisationId: UUID,
        status: String,
        memberNumber: Long? = null,
        joinedAt: OffsetDateTime? = null,
    ): UUID {
        val userId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO user_account (
                id, username, email, display_name, status, created_at, updated_at
            ) VALUES (?, ?, ?, 'Member', 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            userId,
            "v38-$userId",
            "v38-$userId@member-number.test",
        )
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                id, organisation_id, user_id, membership_status, membership_type, member_number,
                joined_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'STAFF', ?, ?, ?, ?)
            """.trimIndent(),
            id,
            organisationId,
            userId,
            status,
            memberNumber,
            joinedAt,
            T0,
            T0,
        )
        return id
    }

    private fun logActivation(
        organisationId: UUID,
        membershipId: UUID,
        at: OffsetDateTime,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership_transition_log (
                organisation_id, entity_id, transition_name, status_from, status_to,
                created_at, updated_at
            ) VALUES (?, ?, 'ACTIVATE', 'PENDING_APPROVAL', 'ACTIVE', ?, ?)
            """.trimIndent(),
            organisationId,
            membershipId,
            at,
            at,
        )
    }

    private fun setCounter(
        organisationId: UUID,
        nextValue: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO reference_sequence (
                organisation_id, sequence_code, next_value, created_at, updated_at
            ) VALUES (?, 'MEMBER', ?, NOW(), NOW())
            ON CONFLICT (organisation_id, sequence_code) DO UPDATE SET next_value = ?
            """.trimIndent(),
            organisationId,
            nextValue,
            nextValue,
        )
    }

    private fun memberNumber(membershipId: UUID): Long? =
        jdbcTemplate.queryForObject(
            "SELECT member_number FROM user_organisation_membership WHERE id = ?",
            Long::class.javaObjectType,
            membershipId,
        )

    private fun status(membershipId: UUID): String? =
        jdbcTemplate.queryForObject(
            "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
            String::class.java,
            membershipId,
        )

    private fun counter(organisationId: UUID): Long? =
        jdbcTemplate.queryForObject(
            "SELECT next_value FROM reference_sequence " +
                "WHERE organisation_id = ? AND sequence_code = 'MEMBER'",
            Long::class.javaObjectType,
            organisationId,
        )

    private fun constraintCount(): Int? =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?",
            Int::class.java,
            CONSTRAINT,
        )

    private companion object {
        const val MIGRATION = "db/migration/V38__membership_active_requires_member_number.sql"
        const val CONSTRAINT = "chk_membership_active_has_member_number"
        const val CODE_SUFFIX = 8
        const val COUNTER_AHEAD = 7
        val T0: OffsetDateTime = OffsetDateTime.of(2026, 1, 5, 8, 0, 0, 0, ZoneOffset.UTC)
    }
}
