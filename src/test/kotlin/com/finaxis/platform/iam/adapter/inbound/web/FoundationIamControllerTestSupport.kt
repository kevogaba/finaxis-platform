package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.UpdateRoleRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.PermissionDetail
import com.finaxis.platform.iam.application.query.PermissionSummary
import com.finaxis.platform.iam.application.query.RoleAssignmentDetail
import com.finaxis.platform.iam.application.query.RoleAssignmentSummary
import com.finaxis.platform.iam.application.query.RoleDetail
import com.finaxis.platform.iam.application.query.RolePermissionDetail
import com.finaxis.platform.iam.application.query.RolePermissionSummary
import com.finaxis.platform.iam.application.query.RoleSummary
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.util.UUID

private const val MODULITH_RUNTIME_AUTO_CONFIGURATION =
    "org.springframework.modulith.runtime.autoconfigure." +
        "SpringModulithRuntimeAutoConfiguration"
internal const val FOUNDATION_IAM_EXCLUDE_MODULITH_RUNTIME =
    "spring.autoconfigure.exclude=$MODULITH_RUNTIME_AUTO_CONFIGURATION"

/** Minimal security and idempotency configuration for IAM MVC controller tests. */
@TestConfiguration
@EnableMethodSecurity
internal class FoundationIamTestSecurityConfiguration {
    /** Configures an authenticated-only test filter chain. */
    @Bean
    fun testSecurityFilterChain(http: HttpSecurity) =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint(
                    org.springframework.security.web.authentication.HttpStatusEntryPoint(
                        HttpStatus.UNAUTHORIZED,
                    ),
                )
            }.build()

    /** Registers the production idempotency-key validation filter. */
    @Bean
    fun idempotencyKeyFilter(
        problemFactory: ApiProblemFactory,
        jsonCodec: ApiJsonCodec,
    ) = org.springframework.boot.web.servlet.FilterRegistrationBean(
        IdempotencyKeyFilter(
            IdempotencyProperties(),
            ApiProblemWriter(problemFactory, jsonCodec),
        ),
    )
}

abstract class FoundationIamControllerTestSupport(
    protected val mockMvc: MockMvc,
    protected val apiJsonCodec: ApiJsonCodec,
) {
    @MockitoBean
    protected lateinit var roleManagementService: RoleManagementService

    @MockitoBean
    protected lateinit var iamQueryService: IamQueryService

    @MockitoBean
    protected lateinit var permissionGuard: PermissionGuard

    protected fun roleMutationPayload(
        method: HttpMethod,
        path: String,
    ): String =
        when {
            method == HttpMethod.PATCH -> {
                apiJsonCodec.mapper.writeValueAsString(UpdateRoleRequest("Operations", null))
            }

            method == HttpMethod.POST && path.endsWith("/permissions") -> {
                apiJsonCodec.mapper.writeValueAsString(
                    AssignPermissionRequest("permission.view"),
                )
            }

            method == HttpMethod.POST && path == ApiPaths.ROLE_ASSIGNMENTS -> {
                apiJsonCodec.mapper.writeValueAsString(
                    AssignRoleRequest(uuidV7(), uuidV7(), RoleAssignmentScopeType.TENANT, null),
                )
            }

            method == HttpMethod.POST && path == ApiPaths.ROLES -> {
                apiJsonCodec.mapper.writeValueAsString(CreateRoleRequest("OPS", "Operations"))
            }

            else -> {
                ""
            }
        }

    protected fun permissionFor(
        method: HttpMethod,
        path: String,
    ): String =
        when {
            method == HttpMethod.PATCH -> "role.update"
            method == HttpMethod.DELETE -> "role.remove_permission"
            path.endsWith("/deactivate") -> "role.deactivate"
            else -> "role.assign_permission"
        }

    protected fun roleSummary(roleId: UUID) =
        RoleSummary(roleId, "OPS", "Operations", false, "ACTIVE")

    protected fun roleDetail(
        tenantId: UUID,
        roleId: UUID,
    ) = RoleDetail(
        roleId,
        tenantId,
        "OPS",
        "Operations",
        null,
        false,
        "ACTIVE",
        NOW,
        NOW,
    )

    protected fun roleAssignmentSummary(assignmentId: UUID) =
        RoleAssignmentSummary(assignmentId, uuidV7(), uuidV7(), null, "TENANT", "ACTIVE")

    protected fun roleAssignmentDetail(
        tenantId: UUID,
        assignmentId: UUID,
        userId: UUID = uuidV7(),
        roleId: UUID = uuidV7(),
        branchId: UUID? = null,
        scopeType: String = "TENANT",
        status: String = "ACTIVE",
    ) = RoleAssignmentDetail(
        assignmentId,
        tenantId,
        userId,
        roleId,
        branchId,
        scopeType,
        status,
        NOW,
        uuidV7(),
        null,
        null,
        NOW,
        NOW,
    )

    protected fun permissionSummary(permissionId: UUID) =
        PermissionSummary(
            permissionId,
            "permission.view",
            "View permission",
            "IAM",
            "LOW",
            "ACTIVE",
        )

    protected fun permissionDetail(permissionId: UUID) =
        PermissionDetail(
            permissionId,
            "permission.view",
            "View permission",
            "IAM",
            null,
            "LOW",
            "ACTIVE",
            NOW,
            NOW,
        )

    protected fun rolePermissionSummary(
        grantId: UUID,
        roleId: UUID,
        permissionCode: String,
    ) = RolePermissionSummary(grantId, roleId, uuidV7(), permissionCode, NOW)

    protected fun rolePermissionDetail(
        tenantId: UUID,
        grantId: UUID = uuidV7(),
        roleId: UUID = uuidV7(),
        permissionCode: String = "permission.view",
    ) = RolePermissionDetail(
        grantId,
        tenantId,
        roleId,
        uuidV7(),
        permissionCode,
        NOW,
        uuidV7(),
        NOW,
        NOW,
    )

    protected fun tenantToken(
        permissions: Set<String>,
        tenantId: UUID = uuidV7(),
        branchId: UUID? = null,
    ) = AppPrincipalAuthenticationToken(
        AppPrincipal(
            userId = uuidV7(),
            keycloakSubject = "tenant-user",
            organisationId = tenantId,
            membershipId = uuidV7(),
            branchId = branchId,
            email = "admin@tenant.test",
            fullName = "Tenant Admin",
            permissions = permissions,
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-24T10:00:00Z")
    }
}
