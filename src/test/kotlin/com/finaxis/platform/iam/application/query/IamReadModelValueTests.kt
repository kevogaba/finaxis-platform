package com.finaxis.platform.iam.application.query

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Value semantics of the IAM read models: copy, equality and hashing hold field by field. */
class IamReadModelValueTests {
    @Test
    fun `read models are value objects`() {
        val id = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = Instant.now()

        val u1 = UserInTenantSummary(id, "u", "e", "d", "a", "a", id, "t", now, now)
        assertNotNull(u1.toString())
        assertEquals(u1, u1.copy())
        assertEquals(u1.hashCode(), u1.copy().hashCode())
        assertTrue(u1 == u1)

        val u2 = UserInTenantDetail(id, "u", "e", "d", "a", "a", id, "t", now, now)
        assertNotNull(u2.toString())
        assertEquals(u2, u2.copy())
        assertEquals(u2.hashCode(), u2.copy().hashCode())

        val m1 = MembershipDetail(id, orgId, userId, "u", "e", "d", "a", "a", "t", null, now, now)
        assertNotNull(m1.toString())
        assertEquals(m1, m1.copy())
        assertEquals(m1.hashCode(), m1.copy().hashCode())

        val ms1 = MembershipSummary(id, userId, "a", "t", null, "u", "e", "d", null, now)
        assertNotNull(ms1.toString())
        assertEquals(ms1, ms1.copy())
        assertEquals(ms1.hashCode(), ms1.copy().hashCode())

        val b1 = BranchAssignmentSummary(id, userId, id, "t", "a", "u", "d", "b", now)
        assertNotNull(b1.toString())
        assertEquals(b1, b1.copy())
        assertEquals(b1.hashCode(), b1.copy().hashCode())

        val b2 =
            BranchAssignmentDetail(
                id,
                orgId,
                userId,
                id,
                "t",
                "a",
                now,
                null,
                null,
                null,
                now,
                now,
                "u",
                "d",
                "b",
            )
        assertNotNull(b2.toString())
        assertEquals(b2, b2.copy())
        assertEquals(b2.hashCode(), b2.copy().hashCode())

        val r1 = RoleSummary(id, "c", "n", true, "a", listOf("branch.view"), 1, 2)
        assertNotNull(r1.toString())
        assertEquals(r1, r1.copy())
        assertEquals(r1.hashCode(), r1.copy().hashCode())

        val r2 = RoleDetail(id, orgId, "c", "n", "d", true, "a", listOf("branch.view"), now, now)
        assertNotNull(r2.toString())
        assertEquals(r2, r2.copy())
        assertEquals(r2.hashCode(), r2.copy().hashCode())
    }
}
