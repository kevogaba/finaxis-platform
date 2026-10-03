package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.put
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Drives `PUT /api/v1/tenant/settings/{key}` through the real idempotency aspect, replay-safety
 * filter and persistence stack. The slice test for this controller does not import any of that, so
 * a response field the replay filter rejects (the bare `key`) went unnoticed and every successful
 * PUT answered 500 and rolled back its setting write, audit row and outbox event.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantSettingIdempotentPutIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val outboxRecords: OutboxRecordRepository,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @Test
        fun `put setting succeeds persists its effects and replays identically`() {
            val organisationId =
                TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
                    .createActiveOrganisation("settings-put", LOCAL_USER_ID)
            val idempotencyKey = UUID.randomUUID().toString()

            val first = putSetting(organisationId, idempotencyKey)
            val replay = putSetting(organisationId, idempotencyKey)

            val expectedBody =
                """{"key":"base_currency","value":"KES","value_type":"CURRENCY",""" +
                    """"sensitive":false,"platform_admin_only":false}"""
            assertEquals(expectedBody, first)
            assertEquals(first, replay, "a replay must return the identical stored response")
            assertEquals(1, effectiveSettingCount(organisationId))
            assertEquals(1, settingsAuditCount(organisationId), "the replay must not audit again")
            // Wait for the poller to complete the record, then count only completed ones: reading
            // pending and completed in sequence can miss a record that completes in between.
            await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    assertEquals(1, settingsOutboxCount(organisationId))
                }
            assertEquals(1, settingsOutboxCount(organisationId), "the replay must not re-publish")
        }

        private fun putSetting(
            organisationId: UUID,
            idempotencyKey: String,
        ): String =
            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/base_currency") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"value":"KES"}"""
                    with(authentication(tenantToken(organisationId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.key") { value("base_currency") }
                    jsonPath("$.value") { value("KES") }
                }.andReturn()
                .response
                .contentAsString

        private fun effectiveSettingCount(organisationId: UUID): Int =
            dsl
                .selectCount()
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                .and(ORGANISATION_SETTING.SETTING_KEY.eq("base_currency"))
                .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
                .fetchOne(0, Int::class.java) ?: 0

        private fun settingsAuditCount(organisationId: UUID): Int =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .and(AUDIT_EVENT.ACTION.eq("settings.update"))
                .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
                .fetchOne(0, Int::class.java) ?: 0

        private fun settingsOutboxCount(organisationId: UUID): Int =
            outboxRecords
                .findCompletedRecords()
                .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                .count {
                    it.target == SETTINGS_UPDATED_TARGET &&
                        it.aggregateId == organisationId.toString()
                }

        private fun tenantToken(organisationId: UUID): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = LOCAL_USER_ID,
                    keycloakSubject = "tenant-user-$LOCAL_USER_ID",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = setOf("settings.update"),
                ),
            )

        private companion object {
            val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
            const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        }
    }
