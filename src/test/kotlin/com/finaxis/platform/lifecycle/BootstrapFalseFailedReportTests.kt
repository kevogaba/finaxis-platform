package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The operator report of issue #163 (`docs/operations/bootstrap-false-failed-report.md`): the SQL
 * text is read **from its file** and run against crafted data, so the documented query is the one
 * tested. Each scenario is a bootstrap record that either must or must not be listed.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BootstrapFalseFailedReportTests(
    private val jdbcTemplate: JdbcTemplate,
    private val dsl: DSLContext,
    private val bootstrapStore: InitialAdministratorBootstrapStore,
) {
    @Test
    fun `lists a failed bootstrap whose administrator dispatch succeeded`() {
        val confirmed = scenario(FAILED, adminDispatch = "SUCCEEDED")
        confirmed.otherUserDispatch("FAILED")
        confirmed.otherUserDispatch("FAILED")
        confirmed.otherUserDispatch("SUCCEEDED")
        val candidate = scenario(FAILED, adminDispatch = "SUCCEEDED")
        val genuine = scenario(FAILED, adminDispatch = "FAILED")
        genuine.otherUserDispatch("FAILED")
        val pending = scenario(FAILED, adminDispatch = "PENDING")
        val noDispatch = scenario(FAILED, adminDispatch = null)
        val completed = scenario(InitialAdministratorBootstrapStatus.COMPLETED, "SUCCEEDED")
        completed.otherUserDispatch("FAILED")
        val queued = scenario(InitialAdministratorBootstrapStatus.QUEUED, "SUCCEEDED")

        val rows = report()
        val byOrganisation = rows.associateBy { it["organisation_id"] as UUID }

        val ours = setOf(confirmed, candidate, genuine, pending, noDispatch, completed, queued)
        val ourIds = ours.map { it.organisationId }.toSet()
        assertEquals(
            setOf(confirmed.organisationId, candidate.organisationId),
            rows.map { it["organisation_id"] as UUID }.filter { it in ourIds }.toSet(),
        )
        val confirmedRow = byOrganisation.getValue(confirmed.organisationId)
        assertEquals(confirmed.organisationCode, confirmedRow["organisation_code"])
        assertEquals(confirmed.adminUserId, confirmedRow["admin_user_id"])
        assertEquals(2L, confirmedRow["other_user_failed_dispatches"])
        assertEquals(
            0L,
            byOrganisation.getValue(candidate.organisationId)["other_user_failed_dispatches"],
        )
        assertTrue(rows.size == rows.map { it["organisation_id"] }.toSet().size, "one row each")
    }

    @Test
    fun `lists a late failure only with an active membership and a linked identity`() {
        // The administrator's job completed the bootstrap, then its SUCCEEDED dispatch write
        // failed, so the dispatch reads FAILED. Only the membership and the identity link show
        // the provisioning really ran to the end.
        val late = scenario(FAILED, adminDispatch = "FAILED")
        late.completedProvisioning()
        val notActivated = scenario(FAILED, adminDispatch = "FAILED")
        notActivated.completedProvisioning(membershipStatus = "PENDING_APPROVAL")
        val notLinked = scenario(FAILED, adminDispatch = "FAILED")
        notLinked.completedProvisioning(linkIdentity = false)
        val succeeded = scenario(FAILED, adminDispatch = "SUCCEEDED")
        succeeded.completedProvisioning()

        val byOrganisation = report().associateBy { it["organisation_id"] as UUID }

        assertEquals(
            "LATE_FAILURE_ACTIVE_MEMBERSHIP",
            byOrganisation.getValue(late.organisationId)["completion_evidence"],
        )
        assertEquals(
            "DISPATCH_SUCCEEDED",
            byOrganisation.getValue(succeeded.organisationId)["completion_evidence"],
        )
        assertTrue(notActivated.organisationId !in byOrganisation, "membership not active")
        assertTrue(notLinked.organisationId !in byOrganisation, "no linked identity")
    }

    private fun report() = jdbcTemplate.queryForList(sql("bootstrap-false-failed.sql"))

    private fun sql(file: String): String = Files.readString(sqlDirectory().resolve(file))

    /** Walks up from the working directory, so a Gradle or an IDE run both find the docs. */
    private fun sqlDirectory(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/operations/sql") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("docs/operations/sql not found above ${System.getProperty("user.dir")}")

    private fun scenario(
        status: InitialAdministratorBootstrapStatus,
        adminDispatch: String?,
    ): Scenario {
        val organisationId = uuidV7()
        val code = "false-failed-${organisationId.toString().takeLast(LABEL_LENGTH)}"
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, organisationId)
            .set(ORGANISATION.TENANT_CODE, code)
            .set(ORGANISATION.DISPLAY_NAME, "False FAILED Report Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.ACTIVE.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        bootstrapStore.createDraft(
            organisationId,
            InitialAdministratorDraft(
                email = "admin-$code@example.test",
                username = "admin-$code",
                displayName = "Initial Admin",
                phoneE164 = null,
            ),
            uuidV7(),
        )
        val adminUserId = insertUser("admin-$code")
        bootstrapStore.linkResolvedEntities(organisationId, adminUserId, null, null, null)
        bootstrapStore.updateStatus(organisationId, status)
        val scenario = Scenario(organisationId, code, adminUserId)
        adminDispatch?.let { insertDispatch(organisationId, adminUserId, it) }
        return scenario
    }

    private fun insertUser(label: String): UUID {
        val userId = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, "$label-${userId.toString().takeLast(LABEL_LENGTH)}")
            .set(USER_ACCOUNT.EMAIL, "$label-${userId.toString().takeLast(LABEL_LENGTH)}@x.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Report User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return userId
    }

    private fun insertDispatch(
        organisationId: UUID,
        userId: UUID,
        status: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(IDENTITY_DISPATCH_LOG)
            .set(IDENTITY_DISPATCH_LOG.ORGANISATION_ID, organisationId)
            .set(IDENTITY_DISPATCH_LOG.USER_ID, userId)
            .set(IDENTITY_DISPATCH_LOG.DISPATCH_TYPE, "KEYCLOAK_PROVISIONING")
            .set(
                IDENTITY_DISPATCH_LOG.DISPATCH_KEY,
                "$organisationId:$userId:KEYCLOAK_PROVISIONING",
            ).set(IDENTITY_DISPATCH_LOG.STATUS, status)
            .set(IDENTITY_DISPATCH_LOG.CREATED_AT, now)
            .set(IDENTITY_DISPATCH_LOG.UPDATED_AT, now)
            .execute()
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        status: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "ADMIN")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
    }

    private fun insertIdentityLink(userId: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(KEYCLOAK_IDENTITY_LINK)
            .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
            .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, uuidV7().toString())
            .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
            .execute()
    }

    private inner class Scenario(
        val organisationId: UUID,
        val organisationCode: String,
        val adminUserId: UUID,
    ) {
        fun otherUserDispatch(status: String) {
            insertDispatch(organisationId, insertUser("other"), status)
        }

        fun completedProvisioning(
            membershipStatus: String = "ACTIVE",
            linkIdentity: Boolean = true,
        ) {
            insertMembership(organisationId, adminUserId, membershipStatus)
            if (linkIdentity) insertIdentityLink(adminUserId)
        }
    }

    private companion object {
        val FAILED = InitialAdministratorBootstrapStatus.FAILED
        const val LABEL_LENGTH = 12
    }
}
