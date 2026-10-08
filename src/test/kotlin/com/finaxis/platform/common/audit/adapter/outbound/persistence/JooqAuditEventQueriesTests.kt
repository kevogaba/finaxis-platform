package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditActorType
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqAuditEventQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqAuditEventQueries,
) {
    @Test
    fun `search scopes results to the requested tenant`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        insertAuditEvent(organisationId, actorId = null, action = "organisation.activate")
        insertAuditEvent(otherOrganisationId, actorId = null, action = "organisation.activate")

        val page = queries.search(AuditEventFilter(organisationId = organisationId))

        assertEquals(1, page.items.size)
        assertEquals(1, page.totalItems)
    }

    @Test
    fun `search returns empty page for extreme page offset`() {
        val organisationId = insertOrganisation()
        insertAuditEvent(organisationId, actorId = null, action = "organisation.activate")

        val page =
            queries.search(
                AuditEventFilter(
                    organisationId = organisationId,
                    page = Int.MAX_VALUE,
                    size = 100,
                ),
            )

        assertEquals(emptyList(), page.items)
        assertEquals(1, page.totalItems)
    }

    @Test
    fun `search filters by entity type and id`() {
        val organisationId = insertOrganisation()
        val entityId = uuidV7()
        insertAuditEvent(
            organisationId,
            actorId = null,
            action = "branch.activate",
            entityType = "BRANCH",
            entityId = entityId,
        )
        insertAuditEvent(
            organisationId,
            actorId = null,
            action = "organisation.activate",
            entityType = "ORGANISATION",
            entityId = uuidV7(),
        )

        val page =
            queries.search(
                AuditEventFilter(
                    organisationId = organisationId,
                    entityType = "BRANCH",
                    entityId = entityId,
                ),
            )

        assertEquals(1, page.items.size)
        assertEquals("branch.activate", page.items.single().action)
    }

    @Test
    fun `search filters by actor id`() {
        val organisationId = insertOrganisation()
        val actorId = insertUserAccount()
        insertAuditEvent(organisationId, actorId = actorId, action = "user.invite")
        insertAuditEvent(organisationId, actorId = insertUserAccount(), action = "user.invite")

        val page =
            queries.search(AuditEventFilter(organisationId = organisationId, actorId = actorId))

        assertEquals(1, page.items.size)
        assertEquals(actorId, page.items.single().actorUserId)
    }

    @Test
    fun `search orders results by event time descending and paginates`() {
        val organisationId = insertOrganisation()
        val base = Instant.parse("2026-07-01T00:00:00Z")
        repeat(3) { index ->
            insertAuditEvent(
                organisationId,
                actorId = null,
                action = "organisation.activate",
                eventTime = base.plusSeconds(index.toLong()),
            )
        }

        val firstPage =
            queries.search(AuditEventFilter(organisationId = organisationId, page = 0, size = 2))
        val secondPage =
            queries.search(AuditEventFilter(organisationId = organisationId, page = 1, size = 2))

        assertEquals(2, firstPage.items.size)
        assertEquals(3, firstPage.totalItems)
        assertEquals(1, secondPage.items.size)
        assertEquals(base.plusSeconds(2), firstPage.items.first().occurredAt)
    }

    @Test
    fun `search filters by outcome`() {
        val organisationId = insertOrganisation()
        val denied = insertAuditEvent(organisationId, null, "branch.approve", outcome = "DENIED")
        insertAuditEvent(organisationId, null, "branch.approve", outcome = "FAILURE")
        insertAuditEvent(organisationId, null, "branch.approve")
        insertAuditEvent(insertOrganisation(), null, "branch.approve", outcome = "DENIED")

        val page =
            queries.search(AuditEventFilter(organisationId, outcome = AuditOutcome.DENIED))

        assertEquals(listOf(denied), page.items.map { it.id })
        assertEquals(1, page.totalItems)
    }

    @Test
    fun `search filters by exact severity and by minimum severity`() {
        val organisationId = insertOrganisation()
        val time = Instant.parse("2026-07-13T10:00:00Z")
        val bySeverity =
            listOf("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")
                .mapIndexed { index, severity ->
                    severity to
                        insertAuditEvent(
                            organisationId,
                            null,
                            "x.y",
                            severity = severity,
                            eventTime = time.plusSeconds(index.toLong()),
                        )
                }.toMap()
        insertAuditEvent(insertOrganisation(), null, "x.y", severity = "CRITICAL")

        assertEquals(
            listOf(bySeverity.getValue("MEDIUM")),
            ids(AuditEventFilter(organisationId, severity = AuditSeverity.MEDIUM)),
        )
        assertEquals(
            listOf(bySeverity.getValue("CRITICAL"), bySeverity.getValue("HIGH")),
            ids(AuditEventFilter(organisationId, minSeverity = AuditSeverity.HIGH)),
        )
        assertEquals(
            5,
            ids(AuditEventFilter(organisationId, minSeverity = AuditSeverity.INFO)).size,
        )
    }

    @Test
    fun `search filters by branch`() {
        val organisationId = insertOrganisation()
        val branchId = insertBranch(organisationId)
        val onBranch = insertAuditEvent(organisationId, null, "branch.update", branchId = branchId)
        insertAuditEvent(
            organisationId,
            null,
            "branch.update",
            branchId = insertBranch(organisationId),
        )
        insertAuditEvent(organisationId, null, "branch.update")

        assertEquals(listOf(onBranch), ids(AuditEventFilter(organisationId, branchId = branchId)))
    }

    @Test
    fun `search filters by a literal action prefix with LIKE wildcards escaped`() {
        val organisationId = insertOrganisation()
        val branchUpdate = insertAuditEvent(organisationId, null, "branch.update")
        val branchApprove = insertAuditEvent(organisationId, null, "branch.approve")
        insertAuditEvent(organisationId, null, "branchXupdate")
        insertAuditEvent(organisationId, null, "user.branch.update")
        val literal = insertAuditEvent(organisationId, null, "a%_b.c")
        insertAuditEvent(organisationId, null, "aXYb.c")

        assertEquals(
            setOf(branchUpdate, branchApprove),
            ids(AuditEventFilter(organisationId, actionPrefix = "branch.")).toSet(),
        )
        assertEquals(
            listOf(literal),
            ids(AuditEventFilter(organisationId, actionPrefix = "a%_b")),
        )
        assertEquals(
            emptyList(),
            ids(AuditEventFilter(organisationId, actionPrefix = "%")),
        )
    }

    @Test
    fun `search keeps the action filter exact`() {
        val organisationId = insertOrganisation()
        val exact = insertAuditEvent(organisationId, null, "branch.update")
        insertAuditEvent(organisationId, null, "branch.update.extra")

        assertEquals(listOf(exact), ids(AuditEventFilter(organisationId, action = "branch.update")))
    }

    @Test
    fun `search text matches action, entity type and reason case-insensitively`() {
        val organisationId = insertOrganisation()
        val byAction = insertAuditEvent(organisationId, null, "journal.APPROVE")
        val byEntityType =
            insertAuditEvent(organisationId, null, "x.y", entityType = "APPROVAL_POLICY")
        val byReason = insertAuditEvent(organisationId, null, "x.y", reason = "Was approved")
        insertAuditEvent(organisationId, null, "x.y", reason = "unrelated")
        insertAuditEvent(insertOrganisation(), null, "journal.approve")

        assertEquals(
            setOf(byAction, byEntityType, byReason),
            ids(AuditEventFilter(organisationId, q = "aPpRoV")).toSet(),
        )
    }

    @Test
    fun `search text treats LIKE wildcards literally`() {
        val organisationId = insertOrganisation()
        val literal = insertAuditEvent(organisationId, null, "x.y", reason = "rate 100% done")
        insertAuditEvent(organisationId, null, "x.y", reason = "rate 1000 done")
        val underscore = insertAuditEvent(organisationId, null, "x.y", reason = "a_b")
        insertAuditEvent(organisationId, null, "x.y", reason = "aXb")

        assertEquals(listOf(literal), ids(AuditEventFilter(organisationId, q = "00%")))
        assertEquals(listOf(underscore), ids(AuditEventFilter(organisationId, q = "a_b")))
    }

    @Test
    fun `the escape character and a backslash are literal in a prefix and a search text`() {
        val organisationId = insertOrganisation()
        val bang = insertAuditEvent(organisationId, null, "a!%b.c", reason = "x!_y")
        insertAuditEvent(organisationId, null, "a!Xb.c", reason = "x!Zy")
        insertAuditEvent(organisationId, null, "a%b.c", reason = "x_y")
        val backslash = insertAuditEvent(organisationId, null, "a\\%b.c", reason = "p\\_q")
        insertAuditEvent(organisationId, null, "a%b.d", reason = "p_q")

        assertEquals(listOf(bang), ids(AuditEventFilter(organisationId, actionPrefix = "a!%")))
        assertEquals(listOf(bang), ids(AuditEventFilter(organisationId, q = "x!_y")))
        assertEquals(
            listOf(backslash),
            ids(AuditEventFilter(organisationId, actionPrefix = "a\\%")),
        )
        assertEquals(listOf(backslash), ids(AuditEventFilter(organisationId, q = "p\\_q")))
    }

    @Test
    fun `search filters by actor type and actor subject`() {
        val organisationId = insertOrganisation()
        val user = insertUserAccount()
        val userRow =
            insertAuditEvent(organisationId, actorId = user, "user.invite", actorSubject = "kc-1")
        insertAuditEvent(organisationId, actorId = user, "user.invite", actorSubject = "kc-2")
        val systemRow = insertAuditEvent(organisationId, actorId = null, "user.invite")

        assertEquals(
            listOf(systemRow),
            ids(AuditEventFilter(organisationId, actorType = AuditActorType.SYSTEM)),
        )
        assertEquals(
            2,
            ids(AuditEventFilter(organisationId, actorType = AuditActorType.USER)).size,
        )
        assertEquals(listOf(userRow), ids(AuditEventFilter(organisationId, actorSubject = "kc-1")))
    }

    @Test
    fun `search sorts by event time ascending on request with the id as tie-break`() {
        val organisationId = insertOrganisation()
        val time = Instant.parse("2026-07-01T00:00:00Z")
        val first = insertAuditEvent(organisationId, null, "x.y", eventTime = time)
        // PostgreSQL orders uuid byte-wise, which is the order of the lower-case text form.
        val (tieLow, tieHigh) =
            List(2) { insertAuditEvent(organisationId, null, "x.y", time.plusSeconds(5)) }
                .sortedBy { it.toString() }

        assertEquals(
            listOf(first, tieLow, tieHigh),
            ids(AuditEventFilter(organisationId, ascending = true)),
        )
        assertEquals(listOf(tieHigh, tieLow, first), ids(AuditEventFilter(organisationId)))
    }

    @Test
    fun `combined filters are AND-ed and never cross the tenant`() {
        val organisationId = insertOrganisation()
        val other = insertOrganisation()
        val branchId = insertBranch(organisationId)
        val otherBranch = insertBranch(other)
        val match =
            insertAuditEvent(
                organisationId,
                null,
                "branch.approve",
                outcome = "DENIED",
                severity = "HIGH",
                branchId = branchId,
                reason = "Maker cannot approve",
            )
        insertAuditEvent(organisationId, null, "branch.approve", outcome = "DENIED")
        insertAuditEvent(
            other,
            null,
            "branch.approve",
            outcome = "DENIED",
            severity = "HIGH",
            branchId = otherBranch,
            reason = "Maker cannot approve",
        )

        val filter =
            AuditEventFilter(
                organisationId,
                outcome = AuditOutcome.DENIED,
                minSeverity = AuditSeverity.HIGH,
                branchId = branchId,
                actionPrefix = "branch.",
                q = "maker",
                actorType = AuditActorType.SYSTEM,
            )
        assertEquals(listOf(match), ids(filter))
        assertEquals(emptyList(), ids(filter.copy(organisationId = other)))
        assertEquals(emptyList(), ids(filter.copy(branchId = otherBranch)))
    }

    @Test
    fun `findById retrieves a detailed audit event within the organisation scope`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, eventId)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(
                AUDIT_EVENT.EVENT_TIME,
                Instant.parse("2026-07-13T10:00:00Z").atOffset(ZoneOffset.UTC),
            ).set(AUDIT_EVENT.ACTOR_TYPE, "SYSTEM")
            .set(AUDIT_EVENT.EVENT_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ENTITY_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ACTION, "organisation.activate")
            .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
            .set(AUDIT_EVENT.SEVERITY, "INFO")
            .set(AUDIT_EVENT.METADATA_JSONB, org.jooq.JSONB.jsonb("{}"))
            .execute()

        val detail = queries.findById(eventId, organisationId)
        assertNotNull(detail)
        assertEquals(eventId, detail.id)
        assertEquals(organisationId, detail.organisationId)
        assertEquals("organisation.activate", detail.action)
    }

    @Test
    fun `findById returns null for cross-tenant request`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val eventId = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, eventId)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(
                AUDIT_EVENT.EVENT_TIME,
                Instant.parse("2026-07-13T10:00:00Z").atOffset(ZoneOffset.UTC),
            ).set(AUDIT_EVENT.ACTOR_TYPE, "SYSTEM")
            .set(AUDIT_EVENT.EVENT_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ENTITY_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ACTION, "organisation.activate")
            .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
            .set(AUDIT_EVENT.SEVERITY, "INFO")
            .execute()

        val detail = queries.findById(eventId, otherOrganisationId)
        kotlin.test.assertNull(detail)
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUserAccount(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertAuditEvent(
        organisationId: UUID,
        actorId: UUID?,
        action: String,
        eventTime: Instant = Instant.parse("2026-07-13T10:00:00Z"),
        entityType: String = "ORGANISATION",
        entityId: UUID? = null,
        outcome: String = "SUCCESS",
        severity: String = "INFO",
        branchId: UUID? = null,
        actorSubject: String? = null,
        reason: String? = null,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, id)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(AUDIT_EVENT.EVENT_TIME, eventTime.atOffset(ZoneOffset.UTC))
            .set(AUDIT_EVENT.ACTOR_USER_ID, actorId)
            .set(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT, actorSubject)
            .set(AUDIT_EVENT.ACTOR_TYPE, if (actorId == null) "SYSTEM" else "USER")
            .set(AUDIT_EVENT.BRANCH_ID, branchId)
            .set(AUDIT_EVENT.EVENT_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_ID, entityId)
            .set(AUDIT_EVENT.ACTION, action)
            .set(AUDIT_EVENT.OUTCOME, outcome)
            .set(AUDIT_EVENT.SEVERITY, severity)
            .set(AUDIT_EVENT.REASON, reason)
            .execute()
        return id
    }

    private fun ids(filter: AuditEventFilter): List<UUID> =
        queries.search(filter).items.map { it.id }
}
