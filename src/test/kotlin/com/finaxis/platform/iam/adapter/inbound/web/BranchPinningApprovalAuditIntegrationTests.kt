package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A member approved by a checker pinned to a branch (#186): the user's own transitions are about no
 * branch, though the approver has one selected. Kept apart from [BranchPinningIntegrationTests],
 * which is at the detekt LargeClass limit.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchPinningApprovalAuditIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    private val createdUserIds = mutableListOf<UUID>()
    private val createdMembershipIds = mutableListOf<UUID>()
    private val createdRoleIds = mutableListOf<UUID>()

    @BeforeEach
    fun resetSeedState() {
        // Other integration classes share this database and leave the seed mutated.
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .where(USER_ACCOUNT.ID.`in`(USER_ID, CHECKER_ID))
            .execute()
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, "ACTIVE")
            .where(ORGANISATION.ID.eq(ORGANISATION_ID))
            .execute()
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, "ACTIVE")
            .where(BRANCH.ID.eq(HEAD_OFFICE_BRANCH_ID))
            .execute()
    }

    @AfterEach
    fun removeCreatedRows() {
        // A member this class invited: its rows go in dependency order (test data only).
        createdUserIds.forEach { userId ->
            dsl
                .deleteFrom(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                .execute()
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
                .execute()
            dsl
                .deleteFrom(IDENTITY_DISPATCH_LOG)
                .where(IDENTITY_DISPATCH_LOG.USER_ID.eq(userId))
                .execute()
            dsl
                .deleteFrom(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG)
                .where(
                    USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ENTITY_ID.`in`(
                        createdMembershipIds,
                    ),
                ).execute()
            dsl
                .deleteFrom(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                .execute()
            dsl
                .deleteFrom(USER_ACCOUNT_TRANSITION_LOG)
                .where(USER_ACCOUNT_TRANSITION_LOG.ENTITY_ID.eq(userId))
                .execute()
            dsl.deleteFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).execute()
        }
        createdUserIds.clear()
        createdMembershipIds.clear()
        createdRoleIds.forEach { roleId ->
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
    }

    @Test
    fun `a member approved while the approver is pinned to a branch is audited with no branch`() {
        val email = "pinned-${shortId()}@pinning.test"
        val headOffice = contextToken(HEAD_OFFICE_BRANCH_ID)
        mockMvc
            .post(ApiPaths.TENANT_USERS) {
                with(localJwt())
                header(CONTEXT_HEADER, headOffice)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","username":"pinned-${shortId()}",""" +
                    """"display_name":"Pinned Invitee","membership_type":"STAFF",""" +
                    """"primary_branch_id":"$HEAD_OFFICE_BRANCH_ID",""" +
                    """"branch_assignments":[{"branch_id":"$HEAD_OFFICE_BRANCH_ID",""" +
                    """"assignment_type":"HOME"}],""" +
                    """"role_assignments":[{"role_id":"${createRole()}","scope_type":"TENANT"}]}"""
            }.andExpect { status { isCreated() } }

        val userId =
            requireNotNull(
                dsl
                    .select(USER_ACCOUNT.ID)
                    .from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.EMAIL.eq(email))
                    .fetchOne(USER_ACCOUNT.ID),
            )
        val membershipId =
            requireNotNull(
                dsl
                    .select(USER_ORGANISATION_MEMBERSHIP.ID)
                    .from(USER_ORGANISATION_MEMBERSHIP)
                    .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(ORGANISATION_ID))
                    .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                    .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
            )
        createdUserIds += userId
        createdMembershipIds += membershipId

        mockMvc
            .post("${ApiPaths.MEMBERSHIPS}/$membershipId/activate") {
                with(checkerJwt())
                header(CONTEXT_HEADER, checkerToken(HEAD_OFFICE_BRANCH_ID))
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isAccepted() } }

        // The user's own transitions are about no branch, though the approver selected one.
        val userRows =
            dsl
                .select(AUDIT_EVENT.BRANCH_ID)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ENTITY_TYPE.eq("USER_ACCOUNT"))
                .and(AUDIT_EVENT.ENTITY_ID.eq(userId))
                .fetch(AUDIT_EVENT.BRANCH_ID)
        assertThat(userRows).isNotEmpty()
        assertThat(userRows).containsOnlyNulls()
    }

    /** An ACTIVE tenant role with no grants, so assigning it to the invitee widens nobody. */
    private fun createRole(): UUID {
        val now = OffsetDateTime.now()
        val code = "approval-audit-${shortId()}"
        return requireNotNull(
            dsl
                .insertInto(ROLE)
                .set(ROLE.ORGANISATION_ID, ORGANISATION_ID)
                .set(ROLE.ROLE_CODE, code)
                .set(ROLE.ROLE_NAME, code)
                .set(ROLE.STATUS, "ACTIVE")
                .set(ROLE.CREATED_AT, now)
                .set(ROLE.UPDATED_AT, now)
                .returning(ROLE.ID)
                .fetchOne()
                ?.id,
        ).also { createdRoleIds += it }
    }

    private fun contextToken(branchId: UUID?): String =
        contextService.issue(
            ActiveOrganisationContext(USER_ID, ORGANISATION_ID, MEMBERSHIP_ID, branchId),
        )

    /** local.checker (V4), a second administrator: an approver who is not the branch's maker. */
    private fun checkerToken(branchId: UUID?): String =
        contextService.issue(
            ActiveOrganisationContext(CHECKER_ID, ORGANISATION_ID, CHECKER_MEMBERSHIP_ID, branchId),
        )

    private fun localJwt() = jwt().jwt { token -> token.subject(USER_ID.toString()) }

    private fun checkerJwt() = jwt().jwt { token -> token.subject(CHECKER_ID.toString()) }

    private fun shortId(): String = uuidV7().toString().takeLast(SHORT_ID_LENGTH).uppercase()

    private companion object {
        const val SHORT_ID_LENGTH = 8
        const val CONTEXT_HEADER = ActiveOrganisationContextService.HEADER
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val CHECKER_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddd01")
        val CHECKER_MEMBERSHIP_ID: UUID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddd03")
    }
}
