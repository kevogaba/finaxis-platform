package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureRecorder
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Proves the tenant and platform tenant detail routes return only a code from the closed set for
 * a failed administrator bootstrap, even when the exception that failed it carries an email
 * address and SQL text. The raw message must reach neither the column nor either response.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BootstrapFailureCodeExposureIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val failureRecorder: InitialAdministratorBootstrapFailureRecorder,
        private val bootstrapService: InitialAdministratorBootstrapService,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        @Test
        fun `both tenant detail routes return a closed code never the exception message`() {
            val cases =
                listOf(
                    DataIntegrityViolationException(LEAKY_MESSAGE) to
                        InitialAdministratorBootstrapFailureCode.DATABASE_ERROR,
                    IllegalStateException(LEAKY_MESSAGE) to
                        InitialAdministratorBootstrapFailureCode.INVALID_STATE,
                    RuntimeException(LEAKY_MESSAGE) to
                        InitialAdministratorBootstrapFailureCode.UNEXPECTED,
                )
            cases.forEach { (failure, expected) ->
                val tenantAdmin = seedUser("tenant-admin")
                val operator = fixture.createPlatformOperator("failure-operator")
                val tenantId = fixture.createActiveOrganisation("bsfail", tenantAdmin)
                failureRecorder.recordFailure(tenantId, failure)

                mockMvc
                    .get(ApiPaths.TENANT) {
                        with(authentication(token(tenantAdmin, tenantId, setOf("tenant.view"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.bootstrap_status") { value("FAILED") }
                        jsonPath("$.bootstrap_failure_code") { value(expected.name) }
                        content { string(not(containsString(LEAKED_EMAIL))) }
                        content { string(not(containsString("insert into"))) }
                    }
                mockMvc
                    .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                        with(
                            authentication(
                                token(operator, PlatformOrganisation.ID, setOf("tenant.view")),
                            ),
                        )
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.bootstrap_failure_code") { value(expected.name) }
                        content { string(not(containsString(LEAKED_EMAIL))) }
                        content { string(not(containsString("insert into"))) }
                    }
                val stored =
                    dsl
                        .fetchOne(
                            "SELECT last_failure_code FROM " +
                                "organisation_initial_administrator_bootstrap " +
                                "WHERE organisation_id = ?",
                            tenantId,
                        )!!
                        .get(0, String::class.java)
                kotlin.test.assertEquals(expected.name, stored)
            }
        }

        @Test
        fun `a bootstrap that really fails and rolls back leaves a closed code on both routes`() {
            val tenantAdmin = seedUser("tenant-admin")
            val operator = fixture.createPlatformOperator("failure-operator")
            val tenantId = fixture.createActiveOrganisation("bsreal", tenantAdmin)
            val taken = seedUser("taken")
            // The administrator's username is already held by another account, so the real
            // bootstrap() raises inside its own transaction, which rolls back.
            dsl.execute(
                "UPDATE organisation_initial_administrator_bootstrap SET status = 'QUEUED', " +
                    "user_id = NULL, membership_id = NULL, last_failure_code = NULL, " +
                    "admin_username = (SELECT username FROM user_account WHERE id = ?), " +
                    "admin_email = ? WHERE organisation_id = ?",
                taken,
                "new-admin-$tenantId@tenant.test",
                tenantId,
            )

            withRequestContext {
                assertFailsWith<ConflictException> { bootstrapService.bootstrap(tenantId) }
            }

            listOf(
                mockMvc.get(ApiPaths.TENANT) {
                    with(authentication(token(tenantAdmin, tenantId, setOf("tenant.view"))))
                },
                mockMvc.get("${ApiPaths.PLATFORM_TENANTS}/$tenantId") {
                    with(
                        authentication(
                            token(operator, PlatformOrganisation.ID, setOf("tenant.view")),
                        ),
                    )
                },
            ).forEach {
                it.andExpect {
                    status { isOk() }
                    jsonPath("$.bootstrap_status") { value("FAILED") }
                    jsonPath("$.bootstrap_failure_code") { value("CONFLICT") }
                    content { string(not(containsString("already in use"))) }
                }
            }
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

        private fun token(
            userId: UUID,
            organisationId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "it-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "it@example.test",
                fullName = "Integration User",
                permissions = permissions,
            ),
        )

        private companion object {
            const val LEAKED_EMAIL = "jane.doe@acme.test"
            const val LEAKY_MESSAGE =
                "insert into user_account (email) values ('$LEAKED_EMAIL'): duplicate key " +
                    "value violates unique constraint \"uq_user_account_lower_email\""
        }
    }
