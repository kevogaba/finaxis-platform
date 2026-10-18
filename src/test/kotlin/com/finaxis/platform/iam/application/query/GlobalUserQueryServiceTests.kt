package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.PlatformCaller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The platform user directory (#160): the permission comes first, then the request is judged. */
class GlobalUserQueryServiceTests {
    private val platformId = UUID.fromString("00000000-0000-0000-0000-000000000000")
    private val queries = RecordingQueries()
    private val guard = FakePermissionGuard()
    private val service = GlobalUserQueryService(queries, guard)
    private val caller = PlatformCaller(UUID.randomUUID(), platformId)

    @Test
    fun `a search reaches the store with its filter once the caller holds user view`() {
        val filter = GlobalUserFilter(q = "ann", userStatus = "ACTIVE", sortBy = "email")

        service.searchUsers(filter, caller)

        assertEquals(filter, queries.lastFilter)
    }

    @Test
    fun `the permission is refused before the page or the sort is judged`() {
        guard.deny(platformId, "user.view")

        assertFailsWith<SecurityException> {
            service.searchUsers(GlobalUserFilter(page = -1, sortBy = "bogus"), caller)
        }
        assertFailsWith<SecurityException> { service.getUser(UUID.randomUUID(), caller) }
        assertNull(queries.lastFilter, "no row is read for a caller who may not see any")
    }

    @Test
    fun `a bad page or sort is a 400 for a holder`() {
        assertFailsWith<InvalidPageRequestException> {
            service.searchUsers(GlobalUserFilter(page = -1), caller)
        }
        assertFailsWith<InvalidPageRequestException> {
            service.searchUsers(GlobalUserFilter(sortBy = "membershipCount"), caller)
        }
    }

    @Test
    fun `an unknown account is not found`() {
        assertFailsWith<ResourceNotFoundException> { service.getUser(UUID.randomUUID(), caller) }
    }

    private class RecordingQueries : GlobalUserQueries {
        var lastFilter: GlobalUserFilter? = null

        override fun searchUsers(filter: GlobalUserFilter): ApiPage<GlobalUserSummary> {
            lastFilter = filter
            return apiPageOf(emptyList(), filter.page, filter.size, 0)
        }

        override fun findUser(userId: UUID): GlobalUserProfile? = null
    }
}
