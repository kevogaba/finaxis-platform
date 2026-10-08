package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DeprovisionOrganisationCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateOrganisationCommand
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.RejectOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.RetryInitialAdministratorBootstrapCommand
import com.finaxis.platform.lifecycle.application.ReturnOrganisationForChangesCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.SuspendOrganisationCommand
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Full-stack proof of ADR 0030 rollout step 6d: the platform tenant routes authorise in
 * `OrganisationProvisioningService` and answer with the gated tenant detail (platform
 * `tenant.view`, bootstrap status included). A caller holding a mutation code without
 * `tenant.view` is refused by name before anything is written, on every route; the service
 * answers the same without the controller in front of it; and a revocation after the service's
 * check cannot refuse the read-back of the same request.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformTenantServiceAuthorisationIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val foundationQueryService: FoundationQueryService,
        private val cacheManager: CacheManager,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `every platform tenant mutation without tenant view is refused by name`() {
            val target = newTenant("s6d-refused")
            routes().forEach { route ->
                val actor = seedUser("viewless")
                fixture.grantPlatformPermissionsExactly(actor, route.permission)
                val key = uuidV7()
                val before = footprint(actor, target)

                // A real tenant, an unknown id, then the platform organisation itself: 403 every
                // time, never 404 or the platform-organisation 409, and no write.
                listOf(target, uuidV7(), PlatformOrganisation.ID).forEach { tenantId ->
                    val path = route.path(tenantId)
                    send(route.method, path, route.body, actor, route.permission, key)
                        .andExpect { forbiddenNaming("tenant.view") }
                }

                assertEquals(before, footprint(actor, target), route.permission)
                // The permission check precedes the platform-organisation guard, so even the
                // platform organisation as the target leaves no DENIED audit row for the actor.
                assertEquals(0, auditRows(actor), route.permission)
                assertEquals(0, idempotencyRows(key), route.permission)
                assertEquals("ACTIVE", tenantStatus(target), route.permission)
            }
        }

        @Test
        fun `creating a tenant draft without tenant view is refused by name and creates nothing`() {
            val actor = seedUser("create-viewless")
            fixture.grantPlatformPermissionsExactly(actor, "tenant.create")
            val key = uuidV7()

            val path = ApiPaths.PLATFORM_TENANTS
            send(HttpMethod.POST, path, tenantBody(), actor, "tenant.create", key)
                .andExpect { forbiddenNaming("tenant.view") }

            assertEquals(0, tenantsWithCode(TENANT_CODE))
            assertEquals(0, idempotencyRows(key))
        }

        @Test
        fun `the service refuses a mutation without tenant view with no controller`() {
            val target = newTenant("s6d-direct")
            val actor = seedUser("direct-viewless")
            fixture.grantPlatformPermissionsExactly(actor, "tenant.suspend")
            val before = footprint(actor, target)

            val refusal =
                assertFailsWith<MissingPermissionException> {
                    withRequestContext {
                        organisationProvisioningService.suspend(
                            SuspendOrganisationCommand(target, Reason.required("Hold"), actor),
                        )
                    }
                }

            assertEquals("tenant.view", refusal.permissionCode)
            assertEquals(before, footprint(actor, target))
            assertEquals("ACTIVE", tenantStatus(target))
        }

        @Test
        fun `every service method refuses a mutation without tenant view with no controller`() {
            val target = newTenant("s6d-direct-all")
            serviceCalls(target).forEach { call ->
                val actor = seedUser("direct-${call.name}")
                fixture.grantPlatformPermissionsExactly(actor, call.permission)
                val before = footprint(actor, target)

                val refusal =
                    assertFailsWith<MissingPermissionException>(call.name) {
                        withRequestContext { call.invoke(actor) }
                    }

                assertEquals("tenant.view", refusal.permissionCode, call.name)
                assertEquals(before, footprint(actor, target), call.name)
                assertEquals(0, auditRows(actor), call.name)
                assertEquals("ACTIVE", tenantStatus(target), call.name)
            }
            assertEquals(0, tenantsWithCode(DIRECT_CODE))
        }

        @Test
        fun `every service method refuses the platform organisation after the permission check`() {
            serviceCalls(PlatformOrganisation.ID).forEach { call ->
                val nobody = seedUser("direct-platform-${call.name}")

                // No grant at all: the permission check answers first, so no DENIED audit row.
                assertFailsWith<ForbiddenOperationException>(call.name) {
                    withRequestContext { call.invoke(nobody) }
                }
                assertEquals(0, auditRows(nobody), call.name)
            }
        }

        @Test
        fun `an actor with no platform grant is refused before the platform organisation guard`() {
            val actor = seedUser("direct-nobody")

            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    organisationProvisioningService.suspend(
                        SuspendOrganisationCommand(
                            PlatformOrganisation.ID,
                            Reason.required("Hold"),
                            actor,
                        ),
                    )
                }
            }

            // The permission check came first: no DENIED audit row for the platform organisation.
            assertEquals(0, auditRows(actor))
        }

        @Test
        fun `the read-back decides from the service's check when the view is revoked`() {
            val target = newTenant("s6d-memo")
            val actor = seedUser("memo")
            fixture.grantPlatformPermissionsWithViews(actor, "tenant.suspend")
            val caller = PlatformCaller(actor, PlatformOrganisation.ID)

            withRequestContext {
                organisationProvisioningService.suspend(
                    SuspendOrganisationCommand(target, Reason.required("Hold"), actor),
                )
                revokeView(actor)
                // The service's own check filled the per-request memo: nothing is re-resolved.
                assertEquals("SUSPENDED", foundationQueryService.getTenant(target, caller).status)
            }

            val refusal =
                assertFailsWith<MissingPermissionException> {
                    withRequestContext { foundationQueryService.getTenant(target, caller) }
                }
            assertEquals("tenant.view", refusal.permissionCode)
        }

        @Test
        fun `a decision with its view answers the gated detail with the bootstrap status`() {
            val tenant = newTenant("s6d-detail")
            val actor = seedUser("detail-actor")
            fixture.grantPlatformPermissionsWithViews(actor, "tenant.suspend", "tenant.reactivate")
            val reason = """{"reason":"Regulatory review"}"""

            val suspended = decide(tenant, "suspend", reason, actor, "tenant.suspend")
            val reactivated = decide(tenant, "reactivate", reason, actor, "tenant.reactivate")

            assertEquals("SUSPENDED", suspended.status)
            assertEquals("ACTIVE", reactivated.status)
            // The detail GET answers the same bootstrap field from the same gated query.
            val read =
                mockMvc
                    .request(HttpMethod.GET, "${ApiPaths.PLATFORM_TENANTS}/$tenant") {
                        with(authentication(platformToken(actor, "tenant.view")))
                    }.andReturn()
                    .response
                    .contentAsString
            assertEquals(reactivated.bootstrapStatus, BOOTSTRAP_STATUS.find(read)?.value)
        }

        /** One `OrganisationProvisioningService` method, called directly as an actor. */
        private class ServiceCall(
            val name: String,
            val permission: String,
            val invoke: (UUID) -> Unit,
        )

        private fun serviceCalls(tenant: UUID) = draftCalls(tenant) + decisionCalls(tenant)

        private fun draftCalls(tenant: UUID): List<ServiceCall> {
            val service = organisationProvisioningService
            val admin = InitialAdministratorDraft("admin@s6d.test", "s6d-admin", "Admin", null)
            return listOf(
                ServiceCall("create", "tenant.create") {
                    service.createDraft(
                        CreateOrganisationDraftCommand(
                            tenantCode = DIRECT_CODE,
                            displayName = "S6d Direct",
                            legalName = null,
                            registrationNumber = null,
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            requestedBy = it,
                            admin = admin,
                        ),
                    )
                },
                ServiceCall("amend", "tenant.update_draft") {
                    service.amendDraft(
                        AmendOrganisationDraftCommand(
                            organisationId = tenant,
                            tenantCode = DIRECT_CODE,
                            displayName = "S6d Direct",
                            legalName = null,
                            registrationNumber = null,
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            actorId = it,
                            requestId = uuidV7(),
                            admin = admin,
                        ),
                    )
                },
                ServiceCall("submit", "tenant.submit_for_approval") {
                    service.submitForApproval(
                        SubmitOrganisationForApprovalCommand(tenant, actorId = it),
                    )
                },
            )
        }

        private fun decisionCalls(tenant: UUID): List<ServiceCall> {
            val service = organisationProvisioningService
            val reason = Reason.required("Regulatory review")
            return listOf(
                ServiceCall("approve", "tenant.approve") {
                    service.approveProvisioning(
                        ApproveOrganisationProvisioningCommand(tenant, actorId = it),
                    )
                },
                ServiceCall("reject", "tenant.reject") {
                    service.rejectProvisioning(
                        RejectOrganisationProvisioningCommand(tenant, reason, actorId = it),
                    )
                },
                ServiceCall("return", "tenant.reject") {
                    service.returnForChanges(
                        ReturnOrganisationForChangesCommand(tenant, reason, it),
                    )
                },
                ServiceCall("suspend", "tenant.suspend") {
                    service.suspend(SuspendOrganisationCommand(tenant, reason, it))
                },
                ServiceCall("reactivate", "tenant.reactivate") {
                    service.reactivate(ReactivateOrganisationCommand(tenant, null, it))
                },
                ServiceCall("deprovision", "tenant.deprovision") {
                    service.deprovision(DeprovisionOrganisationCommand(tenant, reason, it))
                },
                ServiceCall("bootstrap-retry", "tenant.bootstrap_retry") {
                    service.retryBootstrap(
                        RetryInitialAdministratorBootstrapCommand(
                            tenant,
                            PlatformCaller(it, PlatformOrganisation.ID),
                        ),
                    )
                },
            )
        }

        private data class Decision(
            val status: String,
            val bootstrapStatus: String?,
        )

        private fun decide(
            tenant: UUID,
            action: String,
            body: String,
            actor: UUID,
            permission: String,
        ): Decision {
            val response =
                send(HttpMethod.POST, "$BASE/$tenant/$action", body, actor, permission)
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.bootstrap_status") { exists() }
                    }.andReturn()
                    .response
                    .contentAsString
            return Decision(
                status = STATUS.find(response)!!.groupValues[1],
                bootstrapStatus = BOOTSTRAP_STATUS.find(response)?.value,
            )
        }

        private data class Route(
            val method: HttpMethod,
            val suffix: String,
            val permission: String,
            val body: String,
        ) {
            fun path(tenantId: UUID) = "${ApiPaths.PLATFORM_TENANTS}/$tenantId$suffix"
        }

        private fun routes(): List<Route> {
            val reason = """{"reason":"Regulatory review"}"""
            return listOf(
                Route(HttpMethod.PATCH, "", "tenant.update_draft", tenantBody()),
                Route(HttpMethod.POST, "/submit", "tenant.submit_for_approval", "{}"),
                Route(HttpMethod.POST, "/approve", "tenant.approve", "{}"),
                Route(HttpMethod.POST, "/reject", "tenant.reject", reason),
                Route(HttpMethod.POST, "/return", "tenant.reject", reason),
                Route(HttpMethod.POST, "/suspend", "tenant.suspend", reason),
                Route(HttpMethod.POST, "/reactivate", "tenant.reactivate", reason),
                Route(HttpMethod.POST, "/deprovision", "tenant.deprovision", reason),
                Route(HttpMethod.POST, "/bootstrap/retry", "tenant.bootstrap_retry", "{}"),
            )
        }

        private fun tenantBody() =
            """{"tenant_code":"$TENANT_CODE","display_name":"S6d Tenant","country_code":"KE",""" +
                """"base_currency_code":"KES","timezone":"Africa/Nairobi","admin":""" +
                """{"email":"admin@s6d.test","username":"s6d-admin",""" +
                """"display_name":"S6d Admin","phone_e164":"+254700000000"}}"""

        private fun newTenant(label: String): UUID =
            fixture.createActiveOrganisation(label, seedUser("$label-owner"))

        private fun send(
            method: HttpMethod,
            path: String,
            body: String,
            actor: UUID,
            permission: String,
            key: UUID = uuidV7(),
        ): ResultActionsDsl =
            mockMvc.request(method, path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key.toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(platformToken(actor, permission)))
            }

        private fun org.springframework.test.web.servlet.MockMvcResultMatchersDsl.forbiddenNaming(
            code: String,
        ) {
            status { isForbidden() }
            jsonPath("$.code") { value("forbidden") }
            jsonPath("$.detail") { value("Missing permission: $code.") }
        }

        private fun revokeView(actor: UUID) {
            dsl.execute(
                "DELETE FROM role_permission WHERE organisation_id = ? " +
                    "AND permission_id = (SELECT id FROM permission " +
                    "WHERE permission_code = 'tenant.view') " +
                    "AND role_id IN (SELECT role_id FROM user_role_assignment " +
                    "WHERE user_id = ? AND organisation_id = ?)",
                PlatformOrganisation.ID,
                actor,
                PlatformOrganisation.ID,
            )
            // invalidate() deletes synchronously; the Redis cache's clear() may run asynchronously.
            cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
        }

        /** What a refused request must not touch: the tenant's transitions, the actor's audit. */
        private fun footprint(
            actor: UUID,
            tenantId: UUID,
        ): List<Long> =
            dsl
                .fetchOne(
                    "SELECT (SELECT count(*) FROM audit_event WHERE actor_user_id = ?), " +
                        "(SELECT count(*) FROM organisation_transition_log " +
                        "WHERE organisation_id = ?)",
                    actor,
                    tenantId,
                )!!
                .intoArray()
                .map { (it as Number).toLong() }

        private fun auditRows(actor: UUID): Int =
            dsl
                .fetchOne("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", actor)!!
                .get(0, Int::class.java)

        private fun tenantsWithCode(code: String): Int =
            dsl
                .fetchOne("SELECT count(*) FROM organisation WHERE tenant_code = ?", code)!!
                .get(0, Int::class.java)

        private fun idempotencyRows(key: UUID): Int =
            dsl
                .fetchOne(
                    "SELECT count(*) FROM api_idempotency_record WHERE idempotency_key = ?",
                    key,
                )!!
                .get(0, Int::class.java)

        private fun tenantStatus(tenantId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM organisation WHERE id = ?", tenantId)!!
                .get(0, String::class.java)

        private fun platformToken(
            userId: UUID,
            vararg permissions: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = PlatformOrganisation.ID,
                membershipId = uuidV7(),
                email = "user@s6d.test",
                fullName = "Platform Tenant User",
                permissions = permissions.toSet(),
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            const val BASE = ApiPaths.PLATFORM_TENANTS
            const val TENANT_CODE = "s6d-refused-create"
            const val DIRECT_CODE = "s6d-direct-create"
            val STATUS = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"")
            val BOOTSTRAP_STATUS = Regex("\"bootstrap_status\"\\s*:\\s*\"[A-Z_]+\"")
        }
    }
