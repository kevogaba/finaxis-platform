package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * An authenticated caller's `403` writes one `DENIED` / `HIGH` audit row (#184), from the
 * application's refusal and from method security alike, and a caller who keeps asking writes no
 * more than its bucket (two rows here) however many refusals it collects.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["finaxis.audit.denied-access.capacity=2"])
class DeniedAccessAuditIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
    ) {
        @Test
        fun `a refused request writes a denied high row with the actor route and request id`() {
            val caller = seedUser("denied-service")
            val target = seedUser("denied-service-target")

            val requestId =
                patchUser(target, platformToken(caller, "user.view"))
                    .andExpect { status { isForbidden() } }
                    .andReturn()
                    .response
                    .getHeader("X-Request-Id")

            val row = rows(caller).single()
            assertEquals("DENIED", row.get(AUDIT_EVENT.OUTCOME))
            assertEquals("HIGH", row.get(AUDIT_EVENT.SEVERITY))
            assertEquals(PlatformOrganisation.ID, row.get(AUDIT_EVENT.ORGANISATION_ID))
            assertEquals("forbidden", row.get(AUDIT_EVENT.REASON))
            assertEquals(requestId, row.get(AUDIT_EVENT.REQUEST_ID))
            val metadata = requireNotNull(row.get(AUDIT_EVENT.METADATA_JSONB)).data()
            assert(metadata.contains("/api/v1/platform/users/{user_id}")) { metadata }
            assert(!metadata.contains(target.toString())) { "no path value is audited" }
        }

        @Test
        fun `a method security denial writes the same row`() {
            val caller = seedUser("denied-method")

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/settings") {
                    with(authentication(platformToken(caller, "tenant.view")))
                }.andExpect { status { isForbidden() } }

            val row = rows(caller).single()
            assertEquals("DENIED", row.get(AUDIT_EVENT.OUTCOME))
            assertEquals("HIGH", row.get(AUDIT_EVENT.SEVERITY))
            assertEquals("forbidden", row.get(AUDIT_EVENT.REASON))
        }

        @Test
        fun `a caller who keeps being refused writes no more rows than its bucket`() {
            val caller = seedUser("denied-flood")
            val target = seedUser("denied-flood-target")

            repeat(12) {
                patchUser(target, platformToken(caller, "user.view"))
                    .andExpect { status { isForbidden() } }
            }

            assertEquals(2, rows(caller).size)
        }

        private fun rows(actor: UUID) =
            dsl
                .selectFrom(AUDIT_EVENT)
                .where(AUDIT_EVENT.ACTION.eq("api.access_denied"))
                .and(AUDIT_EVENT.ACTOR_USER_ID.eq(actor))
                .fetch()

        private fun patchUser(
            user: UUID,
            token: AppPrincipalAuthenticationToken,
        ) = mockMvc.patch("${ApiPaths.PLATFORM_USERS}/$user") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name":"Nobody Here"}"""
            with(authentication(token))
        }

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@denied.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private fun platformToken(
            userId: UUID,
            vararg permissions: String,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = PlatformOrganisation.ID,
                membershipId = uuidV7(),
                email = "user@denied.test",
                fullName = "Denied User",
                permissions = permissions.toSet(),
            ),
        )
    }
