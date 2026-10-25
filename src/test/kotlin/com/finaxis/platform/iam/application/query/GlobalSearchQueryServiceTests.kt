package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The fan-out, omission, refusal order and `q` rules of ADR 0039 (points 3, 5, 6, 7). */
class GlobalSearchQueryServiceTests {
    private val iam = mock<IamQueryService>()
    private val foundation = mock<FoundationQueryService>()
    private val service = GlobalSearchQueryService(iam, foundation)

    private val organisationId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val caller = TenantCaller(actorId, organisationId)

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   ", "a", " a ", "\t\n"])
    fun `a missing blank or out of range q is a 400 naming q before any delegate runs`(q: String) {
        val failure = assertFailsWith<InvalidPageRequestException> { service.search(caller, q) }

        assertEquals("q", failure.parameter)
        verifyNoInteractions(iam, foundation)
    }

    @Test
    fun `a q of sixty five characters is a 400 naming q before any delegate runs`() {
        val failure =
            assertFailsWith<InvalidPageRequestException> {
                service.search(caller, "x".repeat(MAX_Q + 1))
            }

        assertEquals("q", failure.parameter)
        verifyNoInteractions(iam, foundation)
    }

    @Test
    fun `a control character in q is a 400 naming q that does not echo it`() {
        listOf("ab\u0000cd", "ab\u0001cd", "ab\ncd", "\u007fab", "ab\u0085").forEach { q ->
            val failure = assertFailsWith<InvalidPageRequestException> { service.search(caller, q) }

            assertEquals("q", failure.parameter)
            assertFalse(failure.message.orEmpty().contains("ab"))
        }
        verifyNoInteractions(iam, foundation)
    }

    @Test
    fun `a null q is a 400 naming q`() {
        val failure = assertFailsWith<InvalidPageRequestException> { service.search(caller, null) }

        assertEquals("q", failure.parameter)
        verifyNoInteractions(iam, foundation)
    }

    @Test
    fun `the bounds are two to sixty four characters after trimming`() {
        allowEverything()

        service.search(caller, "  ab  ")
        service.search(caller, "x".repeat(MAX_Q))
        // Padding does not count towards the bound.
        service.search(caller, " ".repeat(10) + "x".repeat(MAX_Q) + " ".repeat(10))

        assertFailsWith<InvalidPageRequestException> {
            service.search(caller, "x".repeat(MAX_Q + 1))
        }
    }

    @Test
    fun `a bad q is a 400 even for a caller who may view no type`() {
        denyEverything()

        assertFailsWith<InvalidPageRequestException> { service.search(caller, "a") }
        verifyNoInteractions(iam, foundation)
    }

    @Test
    fun `each type is asked once for page zero of ten with the trimmed escaped text`() {
        allowEverything()

        service.search(caller, "  50%_off\\  ")

        val users = argumentCaptor<UserInTenantFilter>()
        verify(iam).searchUsers(eq(organisationId), users.capture(), eq(caller))
        assertEquals("50\\%\\_off\\\\", users.firstValue.q)
        assertEquals(0, users.firstValue.page)
        assertEquals(10, users.firstValue.size)
        assertNull(users.firstValue.sortBy)

        val memberships = argumentCaptor<MembershipFilter>()
        verify(iam).searchMemberships(eq(organisationId), memberships.capture(), eq(caller))
        assertEquals("50\\%\\_off\\\\", memberships.firstValue.q)
        assertEquals(0, memberships.firstValue.page)
        assertEquals(10, memberships.firstValue.size)

        val roles = argumentCaptor<RoleFilter>()
        verify(iam).searchRoles(eq(organisationId), roles.capture(), eq(caller))
        assertEquals("50\\%\\_off\\\\", roles.firstValue.q)
        assertEquals(10, roles.firstValue.size)
        assertNull(roles.firstValue.status, "the default list rule hides ARCHIVED roles")

        val branches = argumentCaptor<BranchFilter>()
        verify(foundation).searchBranches(eq(organisationId), branches.capture(), eq(caller))
        assertEquals("50\\%\\_off\\\\", branches.firstValue.q)
        assertEquals(10, branches.firstValue.size)
    }

    @Test
    fun `escaping doubles the backslash first and prefixes percent and underscore`() {
        assertEquals("plain", escapeLikePattern("plain"))
        assertEquals("100\\%", escapeLikePattern("100%"))
        assertEquals("a\\_b", escapeLikePattern("a_b"))
        assertEquals("\\\\", escapeLikePattern("\\"))
        assertEquals("\\\\\\%", escapeLikePattern("\\%"))
        assertEquals("\\%\\%\\_\\_", escapeLikePattern("%%__"))
    }

    @Test
    fun `a viewable type with no match is an empty list and not an omitted one`() {
        allowEverything()

        val result = service.search(caller, "nothing")

        assertEquals(emptyList(), result.users)
        assertEquals(emptyList(), result.branches)
        assertEquals(emptyList(), result.roles)
        assertEquals(emptyList(), result.memberships)
    }

    @Test
    fun `a type refused with a named permission is omitted and the others are kept`() {
        allowEverything()
        whenever(iam.searchRoles(any(), any(), any())) doThrow
            MissingPermissionException("role.view")

        val result = service.search(caller, "nai")

        assertNull(result.roles)
        assertNotNull(result.users)
        assertNotNull(result.branches)
        assertNotNull(result.memberships)
    }

    @Test
    fun `a bare refusal from the branch restriction omits branches as well`() {
        allowEverything()
        // requireListRestriction throws the unnamed base class for a caller with no grant.
        whenever(foundation.searchBranches(any(), any(), any())) doThrow
            ForbiddenOperationException()

        val result = service.search(caller, "nai")

        assertNull(result.branches)
        assertNotNull(result.users)
        assertNotNull(result.roles)
        assertNotNull(result.memberships)
    }

    @Test
    fun `every type refused rethrows the users refusal, which names user view`() {
        val users = MissingPermissionException("user.view")
        whenever(iam.searchUsers(any(), any(), any())) doThrow users
        whenever(foundation.searchBranches(any(), any(), any())) doThrow
            ForbiddenOperationException()
        whenever(iam.searchRoles(any(), any(), any())) doThrow
            MissingPermissionException("role.view")
        whenever(iam.searchMemberships(any(), any(), any())) doThrow
            MissingPermissionException("membership.view")

        val thrown = assertFailsWith<ForbiddenOperationException> { service.search(caller, "nai") }

        assertSame(users, thrown)
        assertEquals("Missing permission: user.view.", thrown.message)
    }

    @Test
    fun `the refusal order is users, branches, roles, memberships whichever is thrown last`() {
        val users = MissingPermissionException("user.view")
        whenever(iam.searchUsers(any(), any(), any())) doThrow users
        whenever(foundation.searchBranches(any(), any(), any())) doThrow
            MissingPermissionException("branch.view")
        whenever(iam.searchRoles(any(), any(), any())) doThrow
            MissingPermissionException("role.view")
        whenever(iam.searchMemberships(any(), any(), any())) doThrow
            MissingPermissionException("membership.view")

        val thrown = assertFailsWith<MissingPermissionException> { service.search(caller, "nai") }

        assertEquals("user.view", thrown.permissionCode)
    }

    @Test
    fun `an exception that is not a refusal propagates and is not swallowed`() {
        allowEverything()
        whenever(iam.searchRoles(any(), any(), any())) doThrow IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { service.search(caller, "nai") }
    }

    @Test
    fun `a missing page is not mistaken for a refusal`() {
        allowEverything()
        whenever(iam.searchUsers(any(), any(), any())) doThrow InvalidPageRequestException("size")

        assertFailsWith<InvalidPageRequestException> { service.search(caller, "nai") }
        verify(iam, never()).searchRoles(any(), any(), any())
    }

    @Test
    fun `hits are the delegates items in their order and nothing else is returned`() {
        allowEverything()
        val branch = branch("HQ")
        whenever(foundation.searchBranches(any(), any(), any())) doReturn
            apiPageOf(listOf(branch), 0, 10, 57)

        val result = service.search(caller, "HQ")

        assertEquals(listOf(branch), result.branches)
    }

    private fun allowEverything() {
        whenever(iam.searchUsers(any(), any(), any())) doReturn emptyPage()
        whenever(iam.searchMemberships(any(), any(), any())) doReturn emptyPage()
        whenever(iam.searchRoles(any(), any(), any())) doReturn emptyPage()
        whenever(foundation.searchBranches(any(), any(), any())) doReturn emptyPage()
    }

    private fun denyEverything() {
        whenever(iam.searchUsers(any(), any(), any())) doThrow
            MissingPermissionException("user.view")
        whenever(iam.searchMemberships(any(), any(), any())) doThrow
            MissingPermissionException("membership.view")
        whenever(iam.searchRoles(any(), any(), any())) doThrow
            MissingPermissionException("role.view")
        whenever(foundation.searchBranches(any(), any(), any())) doThrow
            MissingPermissionException("branch.view")
    }

    private fun <T> emptyPage(): ApiPage<T> = apiPageOf(emptyList(), 0, 10, 0)

    private fun branch(code: String) =
        BranchSummary(
            id = UUID.randomUUID(),
            organisationId = organisationId,
            branchCode = code,
            branchName = "Branch $code",
            branchType = "MAIN",
            status = "ACTIVE",
            createdAt = java.time.Instant.EPOCH,
            parentBranchId = null,
            timezone = "Africa/Nairobi",
            openedOn = null,
            closedOn = null,
            activeUserCount = null,
        )

    private companion object {
        const val MAX_Q = 64
    }
}
