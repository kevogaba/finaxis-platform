package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
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
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof that a role changes status only from the state the operation names: an active
 * role is disabled once and a disabled role re-enabled once, and any other call is a 409 that
 * writes and audits nothing (#171). A repeat used to answer 200 and add an audit row each time.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoleStatusIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser()
        private val organisationId = fixture.createActiveOrganisation("rsi", admin)

        @Test
        fun `a role is disabled once and a repeat is a conflict that audits nothing`() {
            val roleId = createRole("ONCE")

            post("$ROLE_PATH/$roleId/deactivate", "role.deactivate").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("DISABLED") }
            }
            post("$ROLE_PATH/$roleId/deactivate", "role.deactivate").andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("conflict") }
            }

            assertEquals("DISABLED", statusOf(roleId))
            assertEquals(1, audits("role.deactivate", roleId))
        }

        @Test
        fun `only a disabled role can be enabled again`() {
            val roleId = createRole("AGAIN")

            // Active already: nothing to activate.
            post("$ROLE_PATH/$roleId/activate", "role.activate").andExpect {
                status { isConflict() }
            }
            assertEquals(0, audits("role.activate", roleId))

            post("$ROLE_PATH/$roleId/deactivate", "role.deactivate").andExpect {
                status { isOk() }
            }
            post("$ROLE_PATH/$roleId/activate", "role.activate").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
            }

            assertEquals("ACTIVE", statusOf(roleId))
            assertEquals(1, audits("role.activate", roleId))
            assertEquals(1, audits("role.deactivate", roleId))
        }

        private fun createRole(code: String): UUID {
            val body =
                post(ROLE_PATH, "role.create", """{"role_code":"$code","role_name":"$code"}""")
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(ID.find(body)!!.groupValues[1])
        }

        private fun statusOf(roleId: UUID): String =
            dsl.fetchValue("SELECT status FROM role WHERE id = ?", roleId).toString()

        private fun audits(
            action: String,
            roleId: UUID,
        ): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ? AND action = ? " +
                        "AND entity_id = ?",
                    organisationId,
                    action,
                    roleId,
                ).toString()
                .toInt()

        private fun post(
            path: String,
            permission: String,
            body: String = "{}",
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(permission)))
            }

        // The administrator holds every tenant permission in the database; the token carries the
        // coarse authority the route's gate reads.
        private fun token(permission: String) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = admin,
                    keycloakSubject = "user-$admin",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "admin@rsi.test",
                    fullName = "Role Status Admin",
                    permissions = setOf(permission, "role.view"),
                ),
            )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "rsi-$id")
                .set(USER_ACCOUNT.EMAIL, "rsi-$id@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "rsi")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            val ROLE_PATH = ApiPaths.ROLES
            val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        }
    }
