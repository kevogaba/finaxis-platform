package com.finaxis.platform

import org.jooq.DSLContext
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * The member number a test fixture writes on a membership it inserts directly. `V38` (#332)
 * requires one on every `ACTIVE` or `SUSPENDED` membership, so a fixture takes it as the
 * application does: the next value of the organisation's `MEMBER` counter, created holding 1 when
 * it is missing (`ReferenceSequences.next`). A later activation in the same organisation therefore
 * never takes the same number. Unlike `ReferenceSequences.next` it needs no transaction: a fixture
 * commits each statement, and the upsert is one statement.
 */
object TestMemberNumbers {
    private val NUMBERED_STATUSES = setOf("ACTIVE", "SUSPENDED")

    private val NEXT_MEMBER_NUMBER =
        """
        INSERT INTO reference_sequence (
            organisation_id, sequence_code, next_value, created_at, updated_at
        ) VALUES (?, 'MEMBER', 2, NOW(), NOW())
        ON CONFLICT (organisation_id, sequence_code) DO UPDATE
        SET next_value = reference_sequence.next_value + 1,
            updated_at = NOW(),
            row_version = reference_sequence.row_version + 1
        RETURNING next_value - 1
        """.trimIndent()

    /** A new number when a membership inserted in [status] needs one, else `null`. */
    fun forStatus(
        dsl: DSLContext,
        organisationId: UUID,
        status: String,
    ): Long? = if (status in NUMBERED_STATUSES) next(dsl, organisationId) else null

    /** The next number of [organisationId]'s `MEMBER` counter, taken through jOOQ. */
    fun next(
        dsl: DSLContext,
        organisationId: UUID,
    ): Long =
        requireNotNull(
            dsl.resultQuery(NEXT_MEMBER_NUMBER, organisationId).fetchOne(0, Long::class.java),
        )

    /** The next number of [organisationId]'s `MEMBER` counter, taken through JDBC. */
    fun next(
        jdbcTemplate: JdbcTemplate,
        organisationId: UUID,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(NEXT_MEMBER_NUMBER, Long::class.java, organisationId),
        )
}
