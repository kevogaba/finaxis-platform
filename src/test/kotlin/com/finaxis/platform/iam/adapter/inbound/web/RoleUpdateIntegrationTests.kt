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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Full-stack proof of the role update rules of #340. A blank `role_name` is refused before
 * anything is written; `description` keeps its value when absent, is cleared by an explicit
 * `null`, and refuses a blank string instead of silently overwriting the stored text.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoleUpdateIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser()
        private val organisationId = fixture.createActiveOrganisation("rui", admin)

        @Test
        fun `a blank name is refused and writes nothing`() {
            val roleId = createRole("BLANK", "Blank", "Keep")

            patch(roleId, """{"role_name":"   "}""").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }

            assertEquals("Blank", column("role_name", roleId))
            assertEquals(0, audits(roleId))
        }

        @Test
        fun `an absent description keeps it and an explicit null clears it`() {
            val roleId = createRole("CLEAR", "Clear", "First")

            patch(roleId, """{"role_name":"Renamed"}""").andExpect {
                status { isOk() }
                jsonPath("$.role_name") { value("Renamed") }
                jsonPath("$.description") { value("First") }
            }
            patch(roleId, """{"description":null}""").andExpect {
                status { isOk() }
                jsonPath("$.description") { doesNotExist() }
            }
            assertNull(column("description", roleId))

            // Clearing an empty description, and an empty body, are harmless no-op updates.
            patch(roleId, """{"description":null}""").andExpect { status { isOk() } }
            patch(roleId, "{}").andExpect { status { isOk() } }

            assertEquals("Renamed", column("role_name", roleId))
            assertEquals(4, audits(roleId))
        }

        @Test
        fun `a blank description is refused and the stored one stays`() {
            val roleId = createRole("STAY", "Stay", "Stay here")

            patch(roleId, """{"description":""}""").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }

            assertEquals("Stay here", column("description", roleId))
            assertEquals(0, audits(roleId))
        }

        private fun createRole(
            code: String,
            name: String,
            description: String,
        ): UUID {
            val body =
                mockMvc
                    .post(ApiPaths.ROLES) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            """{"role_code":"$code","role_name":"$name",""" +
                            """"description":"$description"}"""
                        with(authentication(token("role.create")))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(ID.find(body)!!.groupValues[1])
        }

        private fun patch(
            roleId: UUID,
            body: String,
        ): ResultActionsDsl =
            mockMvc.patch("${ApiPaths.ROLES}/$roleId") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token("role.update")))
            }

        private fun column(
            name: String,
            roleId: UUID,
        ): String? = dsl.fetchValue("SELECT $name FROM role WHERE id = ?", roleId)?.toString()

        private fun audits(roleId: UUID): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'role.update' AND entity_id = ?",
                    organisationId,
                    roleId,
                ).toString()
                .toInt()

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
                    email = "admin@rui.test",
                    fullName = "Role Update Admin",
                    permissions = setOf(permission, "role.view"),
                ),
            )

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "rui-$id")
                .set(USER_ACCOUNT.EMAIL, "rui-$id@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "rui")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        }
    }
