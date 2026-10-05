package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof that the reserved `PLATFORM` organisation cannot be moved through the tenant
 * lifecycle (issue #205): a platform super-administrator who names it on any tenant action is
 * answered 409 with the protection message, and afterwards the organisation is still `ACTIVE`,
 * every platform membership is still `ACTIVE`, no transition was logged, and the same
 * administrator's next request still works. That last point is the one that matters operationally:
 * a suspended platform organisation refuses every platform principal, with recovery only by SQL.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrganisationProtectionIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val platformId = PlatformOrganisation.ID

        @Test
        fun `suspending the platform organisation is refused and changes nothing`() {
            val admin = platformAdmin()
            val before = platformSnapshot()

            post("suspend", admin, REASON_BODY).andExpectProtection()

            assertUnchanged(before)
            assertAdminStillWorks(admin)
        }

        @Test
        fun `deprovisioning the platform organisation is refused and revokes no membership`() {
            val admin = platformAdmin()
            val before = platformSnapshot()

            post("deprovision", admin, REASON_BODY).andExpectProtection()

            assertUnchanged(before)
            assertAdminStillWorks(admin)
        }

        @Test
        fun `every other tenant action on the platform organisation is refused too`() {
            val admin = platformAdmin()
            val before = platformSnapshot()

            post("approve", admin, null).andExpectProtection()
            post("reject", admin, REASON_BODY).andExpectProtection()
            post("return", admin, REASON_BODY).andExpectProtection()
            post("submit", admin, null).andExpectProtection()
            post("reactivate", admin, "{}").andExpectProtection()
            post("bootstrap/retry", admin, null).andExpectProtection()
            amend(admin).andExpectProtection()

            assertUnchanged(before)
            assertAdminStillWorks(admin)
        }

        @Test
        fun `a caller without the permission is refused with 403 before the platform guard`() {
            val narrow = seedUser("narrow")
            fixture.grantPlatformPermissionsWithViews(narrow, "tenant.view")
            val before = platformSnapshot()

            // The coarse authority is claimed on the token but not held in the database, so the
            // application's permission check answers 403 and the caller learns nothing about the
            // platform organisation being special.
            post("suspend", narrow, REASON_BODY).andExpect { status { isForbidden() } }
            post("deprovision", narrow, REASON_BODY).andExpect { status { isForbidden() } }
            post("return", narrow, REASON_BODY).andExpect { status { isForbidden() } }

            assertUnchanged(before)
        }

        @Test
        fun `an ordinary tenant is still suspended and reactivated`() {
            val admin = platformAdmin()
            val owner = seedUser("owner")
            val tenantId = fixture.createActiveOrganisation("guard-ordinary", owner)

            postTo(tenantId, "suspend", admin, REASON_BODY).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUSPENDED") }
            }
            postTo(tenantId, "reactivate", admin, "{}").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
            }
            assertEquals("ACTIVE", organisationStatus(platformId))
        }

        @Test
        fun `every refused attempt leaves exactly one durable denied audit naming the actor`() {
            val admin = platformAdmin()
            val attempts: List<Pair<String, () -> ResultActionsDsl>> =
                listOf(
                    "organisation.suspend" to { post("suspend", admin, REASON_BODY) },
                    "organisation.deprovision" to { post("deprovision", admin, REASON_BODY) },
                    "organisation.approve" to { post("approve", admin, null) },
                    "organisation.reject" to { post("reject", admin, REASON_BODY) },
                    "organisation.return_for_changes" to { post("return", admin, REASON_BODY) },
                    "organisation.submit_for_approval" to { post("submit", admin, null) },
                    "organisation.reactivate" to { post("reactivate", admin, "{}") },
                    "tenant.bootstrap_retry" to { post("bootstrap/retry", admin, null) },
                    "organisation.amend_draft" to { amend(admin) },
                )

            attempts.forEach { (action, attempt) ->
                val before = deniedAudits(action)

                // The 409 is unchanged and the use case rolled back, yet the audit row is there:
                // it is written in its own transaction before the refusal is raised.
                attempt().andExpectProtection()

                val rows = deniedAudits(action)
                assertEquals(before.size + 1, rows.size, "one new DENIED row for $action")
                val row = rows.last()
                assertEquals(admin, row.actor)
                assertEquals("ORGANISATION", row.entityType)
                assertEquals(platformId, row.organisationId)
                assertEquals("HIGH", row.severity)
                assertEquals("lifecycle.platform_organisation_protected", row.reason)
            }
        }

        @Test
        fun `an ordinary tenant action leaves no denied audit on the platform organisation`() {
            val admin = platformAdmin()
            val owner = seedUser("owner-audit")
            val tenantId = fixture.createActiveOrganisation("guard-no-denied", owner)
            val before = deniedAuditCount()

            postTo(tenantId, "suspend", admin, REASON_BODY).andExpect { status { isOk() } }

            assertEquals(before, deniedAuditCount())
        }

        private data class DeniedAudit(
            val actor: UUID?,
            val entityType: String,
            val organisationId: UUID,
            val severity: String,
            val reason: String?,
        )

        private fun deniedAudits(action: String): List<DeniedAudit> =
            dsl
                .fetch(
                    "SELECT actor_user_id, entity_type, organisation_id, severity, reason " +
                        "FROM audit_event WHERE entity_id = ? AND action = ? " +
                        "AND outcome = 'DENIED' ORDER BY event_time, id",
                    platformId,
                    action,
                ).map {
                    DeniedAudit(
                        it.get(0, UUID::class.java),
                        it.get(1, String::class.java),
                        it.get(2, UUID::class.java),
                        it.get(3, String::class.java),
                        it.get(4, String::class.java),
                    )
                }

        private fun deniedAuditCount(): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM audit_event WHERE entity_id = ? AND outcome = 'DENIED'",
                    platformId,
                )!!
                .get(0, Int::class.java)

        private fun ResultActionsDsl.andExpectProtection(): ResultActionsDsl =
            andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("lifecycle.platform_organisation_protected") }
                jsonPath("$.detail") { value(PROTECTION_MESSAGE) }
            }

        private fun assertAdminStillWorks(admin: UUID) {
            mockMvc
                .get(ApiPaths.PLATFORM_TENANTS) { with(authentication(token(admin))) }
                .andExpect { status { isOk() } }
        }

        private data class PlatformSnapshot(
            val status: String,
            val membershipStatuses: Map<String, Int>,
            val transitions: Int,
            val auditSuccesses: Int,
        )

        private fun platformSnapshot() =
            PlatformSnapshot(
                status = organisationStatus(platformId),
                membershipStatuses =
                    dsl
                        .fetch(
                            "SELECT membership_status, COUNT(*) " +
                                "FROM user_organisation_membership " +
                                "WHERE organisation_id = ? GROUP BY membership_status",
                            platformId,
                        ).associate { it.get(0, String::class.java) to it.get(1, Int::class.java) },
                transitions =
                    dsl
                        .fetchOne(
                            "SELECT COUNT(*) FROM organisation_transition_log WHERE entity_id = ?",
                            platformId,
                        )!!
                        .get(0, Int::class.java),
                auditSuccesses =
                    dsl
                        .fetchOne(
                            "SELECT COUNT(*) FROM audit_event WHERE entity_id = ? " +
                                "AND outcome = 'SUCCESS' AND action LIKE 'organisation.%'",
                            platformId,
                        )!!
                        .get(0, Int::class.java),
            )

        private fun assertUnchanged(before: PlatformSnapshot) {
            val after = platformSnapshot()
            assertEquals("ACTIVE", after.status)
            // Memberships: the same statuses in the same numbers, none revoked or suspended.
            assertEquals(before.membershipStatuses, after.membershipStatuses)
            assertEquals(before.transitions, after.transitions)
            assertEquals(before.auditSuccesses, after.auditSuccesses)
        }

        private fun organisationStatus(organisationId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM organisation WHERE id = ?", organisationId)!!
                .get(0, String::class.java)

        private fun platformAdmin(): UUID =
            seedUser("padmin").also { fixture.grantPlatformSuperAdmin(it) }

        private fun post(
            action: String,
            actor: UUID,
            body: String?,
        ) = postTo(platformId, action, actor, body)

        private fun postTo(
            tenantId: UUID,
            action: String,
            actor: UUID,
            body: String?,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/$action") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
                with(authentication(token(actor)))
            }

        private fun amend(actor: UUID): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.PLATFORM_TENANTS}/$platformId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = AMEND_BODY
                with(authentication(token(actor)))
            }

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun token(userId: UUID) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "it-user-$userId",
                    organisationId = platformId,
                    membershipId = uuidV7(),
                    email = "it@example.test",
                    fullName = "Integration User",
                    permissions = COARSE,
                ),
            )

        private companion object {
            const val REASON_BODY = """{"reason":"Valid reason"}"""
            const val AMEND_BODY =
                """{"tenant_code":"renamed","display_name":"Renamed","country_code":"KE",
                    "base_currency_code":"KES","timezone":"Africa/Nairobi","admin":
                    {"email":"a@example.test","username":"adminuser","display_name":"Admin User",
                     "phone_e164":"+254700000000"}}"""
            const val PROTECTION_MESSAGE =
                "The platform organisation cannot be suspended, deprovisioned or otherwise " +
                    "changed through the tenant lifecycle."

            /** Every coarse authority the routes need; the application re-checks in the DB. */
            val COARSE =
                setOf(
                    "tenant.create",
                    "tenant.update_draft",
                    "tenant.submit_for_approval",
                    "tenant.approve",
                    "tenant.reject",
                    "tenant.suspend",
                    "tenant.reactivate",
                    "tenant.deprovision",
                    "tenant.bootstrap_retry",
                    "tenant.view",
                )
        }
    }
