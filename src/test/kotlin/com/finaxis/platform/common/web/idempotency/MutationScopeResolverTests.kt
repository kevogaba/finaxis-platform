package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiJsonCodec
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutationScopeResolverTests {
    private val resolver = MutationScopeResolver(ApiJsonCodec())

    @Test
    fun `tenant scope does not become platform scope from its route`() {
        val tenantId = UUID.randomUUID()
        val request = request("/api/v1/platform/tenants", tenantId)

        val scope =
            RequestContexts.with(RequestContext(tenant = TenantContext(tenantId))) {
                resolver.resolve(request, IdempotencyScopeKind.TENANT)
            }

        assertEquals(tenantId, scope.organisationId)
    }

    @Test
    fun `platform scope requires reserved platform context even on a platform route`() {
        val tenantId = UUID.randomUUID()
        val request = request("/api/v1/platform/tenants", tenantId)

        assertFailsWith<ForbiddenOperationException> {
            RequestContexts.with(RequestContext(tenant = TenantContext(tenantId))) {
                resolver.resolve(request, IdempotencyScopeKind.PLATFORM)
            }
        }
        val scope =
            RequestContexts.with(
                RequestContext(tenant = TenantContext(PlatformOrganisation.ID)),
            ) {
                resolver.resolve(request, IdempotencyScopeKind.PLATFORM)
            }
        assertEquals(PlatformOrganisation.ID, scope.organisationId)
    }

    @Test
    fun `nested tenant target route stays explicitly platform scoped`() {
        val targetTenant = UUID.randomUUID()
        val request = request("/api/v1/$targetTenant/branches", targetTenant)

        val scope =
            RequestContexts.with(
                RequestContext(tenant = TenantContext(PlatformOrganisation.ID)),
            ) {
                resolver.resolve(request, IdempotencyScopeKind.PLATFORM)
            }

        assertEquals(PlatformOrganisation.ID, scope.organisationId)
    }

    private fun request(
        path: String,
        organisationId: UUID,
    ): BoundedContentCachingRequestWrapper {
        val request = MockHttpServletRequest("POST", path)
        request.setContent("""{"organisation_id":"$organisationId"}""".toByteArray())
        return BoundedContentCachingRequestWrapper(request, 1_024)
    }
}
