package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.AmendOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.ReturnOrganisationForChangesCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closes the approve/return window (issue #204, ADR 0029): the maker-checker rule is validated
 * against the bootstrap record, and the record is only as current as the read that produced it.
 * Since the return edge exists, another request can commit return, amend and resubmit between a
 * decision's reads and the FSM transition's own re-read of the organisation, leaving the decision
 * with the previous submitter and previous named administrator while the tenant it then moves is
 * the new submission.
 *
 * Deterministic and with no hook in production code. A "rival" transaction takes a lock on the
 * organisation and holds it. The decision under test starts on its own thread and is observed
 * blocked behind the rival through `pg_blocking_pids`. The rival then performs return, amend and
 * resubmit as its own actors and commits, which releases the decision at exactly the point where,
 * before the fix, its reads were already behind it and its transition was about to read the
 * organisation. The decision must then be judged against the committed submission, not the one it
 * would have read had it run first.
 *
 * The asserted outcome holds only when the decision locks the organisation first, which is what
 * the fix does. A decision that reads the record before locking (the old shape) is held at a
 * different statement depending on the lock mode, and is then judged against the stale record:
 * approve and amend under a table lock or a row lock respectively succeed against the new
 * submission, and return under a row lock fails on the stale `row_version` instead of being
 * refused. The tests for those therefore fail on the old shape; only the eligible-checker test is
 * a regression guard that passes either way.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TenantApprovalReturnRaceIntegrationTests(
    private val dsl: DSLContext,
    private val service: OrganisationProvisioningService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(service, dsl)
    private val transaction = TransactionTemplate(transactionManager)

    // The service authorises its own use cases (ADR 0030, step 6): makers and submitters hold a
    // platform grant too. The maker-checker rules under test are about identity, not permission.
    private val makerId = seedUser("maker").also { fixture.grantPlatformSuperAdmin(it) }
    private val submitterId = seedUser("submitter").also { fixture.grantPlatformSuperAdmin(it) }

    /** Holds a platform grant, so it can decide on a tenant and return one. */
    private val checkerId = seedUser("checker").also { fixture.grantPlatformSuperAdmin(it) }

    /** The actor whose stale view the tests exploit; also a legitimate platform checker. */
    private val lateActorId =
        seedUser("late-actor").also { fixture.grantPlatformSuperAdmin(it) }

    @Test
    fun `an approval racing return amend and resubmit cannot be made by the new submitter`() {
        val tenantId = pendingTenant()

        val outcome =
            raceDecision(LockMode.TABLE, decide = { approve(tenantId, lateActorId) }) {
                returnTenant(tenantId, checkerId)
                amend(tenantId, makerId)
                submit(tenantId, lateActorId)
            }

        assertEquals("forbidden", assertIs<ForbiddenOperationException>(outcome).code)
        assertEquals("PENDING_APPROVAL", status(tenantId), "the refused approval changed nothing")
        assertEquals(lateActorId, submittedBy(tenantId))
        assertEquals(null, approvedBy(tenantId))
    }

    @Test
    fun `an approval racing a change of administrator is refused to the new administrator`() {
        val tenantId = pendingTenant()

        val outcome =
            raceDecision(LockMode.TABLE, decide = { approve(tenantId, lateActorId) }) {
                returnTenant(tenantId, checkerId)
                // The rival now names the approver themselves as the initial administrator.
                amend(tenantId, makerId, admin = administratorNamed(emailOf(lateActorId)))
                submit(tenantId, submitterId)
            }

        assertEquals(
            "lifecycle.approver_is_initial_administrator",
            assertIs<ForbiddenOperationException>(outcome).code,
        )
        assertEquals("PENDING_APPROVAL", status(tenantId))
        assertEquals(null, approvedBy(tenantId))
    }

    @Test
    fun `an eligible checker still approves the resubmission the rival committed`() {
        val tenantId = pendingTenant()

        val outcome =
            raceDecision(LockMode.TABLE, decide = { approve(tenantId, checkerId) }) {
                returnTenant(tenantId, checkerId)
                amend(tenantId, makerId)
                submit(tenantId, lateActorId)
            }

        assertEquals(null, outcome, "a checker who is neither maker nor submitter may approve")
        assertEquals("ACTIVE", status(tenantId))
        assertEquals(checkerId, approvedBy(tenantId))
        assertEquals(lateActorId, submittedBy(tenantId), "decided against the new submission")
    }

    @Test
    fun `a return racing return amend and resubmit cannot be made by the new submitter`() {
        val tenantId = pendingTenant()

        val outcome =
            raceDecision(LockMode.ROW, decide = { returnTenant(tenantId, lateActorId) }) {
                returnTenant(tenantId, checkerId)
                amend(tenantId, makerId)
                submit(tenantId, lateActorId)
            }

        assertIs<ForbiddenOperationException>(outcome)
        assertEquals("PENDING_APPROVAL", status(tenantId), "the submitter did not pull it back")
        assertEquals(lateActorId, submittedBy(tenantId))
    }

    @Test
    fun `an amendment racing a submission cannot rewrite the tenant it just submitted`() {
        val tenantId = draftTenant()

        val outcome =
            raceDecision(
                LockMode.ROW,
                decide = { amend(tenantId, makerId, displayName = "Late") },
            ) {
                submit(tenantId, submitterId)
            }

        assertIs<ConflictException>(outcome)
        assertEquals("PENDING_APPROVAL", status(tenantId))
        assertEquals("Race Tenant", displayName(tenantId), "the late amendment wrote nothing")
    }

    private enum class LockMode(
        val sql: String,
    ) {
        /** Stops every reader of the table, so even the transition's plain read waits. */
        TABLE("LOCK TABLE organisation IN ACCESS EXCLUSIVE MODE"),

        /** The row lock the service itself takes; plain reads pass, writers wait. */
        ROW("SELECT id FROM organisation WHERE id = ? FOR NO KEY UPDATE"),
    }

    /**
     * Runs [decide] on its own thread while a rival transaction holds the organisation lock,
     * waits until the rival is observed blocking something, runs [rival] inside that transaction,
     * commits, and returns what [decide] threw (null when it succeeded).
     */
    private fun raceDecision(
        mode: LockMode,
        decide: () -> Unit,
        rival: () -> Unit,
    ): Throwable? {
        var decision: CompletableFuture<Throwable?>? = null
        val decisionPid = CompletableFuture<String>()
        transaction.executeWithoutResult {
            if (mode == LockMode.TABLE) {
                dsl.execute(mode.sql)
            } else {
                dsl.fetch(mode.sql, currentTenant)
            }
            val rivalPid = requireNotNull(dsl.fetchValue("SELECT pg_backend_pid()")).toString()
            decision =
                CompletableFuture.supplyAsync(
                    {
                        try {
                            // One outer transaction, so the service joins it and the backend
                            // pid read here is the pid of the connection that does the work.
                            transaction.executeWithoutResult {
                                decisionPid.complete(
                                    requireNotNull(dsl.fetchValue("SELECT pg_backend_pid()"))
                                        .toString(),
                                )
                                withRequestContext { decide() }
                            }
                            null
                        } catch (expected: Exception) {
                            expected
                        }
                    },
                    executor,
                )
            awaitBlocked(decisionPid.get(WAIT.toSeconds(), TimeUnit.SECONDS), rivalPid)
            withRequestContext { rival() }
        }
        return try {
            requireNotNull(decision).get(WAIT.toSeconds(), TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    /** Waits until the decision's own backend is blocked by the rival's, and by nothing else. */
    private fun awaitBlocked(
        decisionPid: String,
        rivalPid: String,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (System.nanoTime() < deadline) {
            val blocked =
                dsl.fetchValue(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid = ?::int " +
                        "AND ?::int = ANY(pg_blocking_pids(pid))",
                    decisionPid,
                    rivalPid,
                )
            if ((blocked as Number).toLong() > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("the decision never blocked behind the rival transaction")
    }

    /** The tenant the current scenario races over, bound by [pendingTenant] / [draftTenant]. */
    private var currentTenant: UUID = UUID(0, 0)

    private fun draftTenant(): UUID =
        withRequestContext {
            service
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "race-${uuidV7().toString().takeLast(10)}",
                        displayName = "Race Tenant",
                        legalName = null,
                        registrationNumber = null,
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = makerId,
                        admin = administratorNamed("initial-${uuidV7()}@race.test"),
                    ),
                ).organisationId
        }.also { currentTenant = it }

    private fun pendingTenant(): UUID =
        draftTenant().also { tenantId ->
            withRequestContext { submit(tenantId, submitterId) }
        }

    private fun administratorNamed(email: String) =
        InitialAdministratorDraft(
            email = email,
            username = "admin-${uuidV7().toString().takeLast(8)}",
            displayName = "Initial Admin",
            phoneE164 = null,
        )

    private fun approve(
        tenantId: UUID,
        actorId: UUID,
    ) = service.approveProvisioning(
        ApproveOrganisationProvisioningCommand(tenantId, actorId = actorId),
    )

    private fun returnTenant(
        tenantId: UUID,
        actorId: UUID,
    ) = service.returnForChanges(
        ReturnOrganisationForChangesCommand(
            tenantId,
            Reason.required("Please correct the draft."),
            actorId,
        ),
    )

    private fun submit(
        tenantId: UUID,
        actorId: UUID,
    ) = service.submitForApproval(SubmitOrganisationForApprovalCommand(tenantId, actorId = actorId))

    private fun amend(
        tenantId: UUID,
        actorId: UUID,
        displayName: String = "Race Tenant",
        admin: InitialAdministratorDraft = existingAdministrator(tenantId),
    ) = service.amendDraft(
        AmendOrganisationDraftCommand(
            organisationId = tenantId,
            tenantCode = tenantCode(tenantId),
            displayName = displayName,
            legalName = null,
            registrationNumber = null,
            countryCode = "KE",
            baseCurrencyCode = "KES",
            timezone = "Africa/Nairobi",
            actorId = actorId,
            requestId = uuidV7(),
            admin = admin,
        ),
    )

    private fun existingAdministrator(tenantId: UUID): InitialAdministratorDraft {
        val record = ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP
        val row =
            requireNotNull(
                dsl.selectFrom(record).where(record.ORGANISATION_ID.eq(tenantId)).fetchOne(),
            )
        return InitialAdministratorDraft(
            email = requireNotNull(row.adminEmail),
            username = requireNotNull(row.adminUsername),
            displayName = requireNotNull(row.adminDisplayName),
            phoneE164 = row.adminPhoneE164,
        )
    }

    private fun tenantCode(tenantId: UUID): String =
        requireNotNull(
            dsl
                .select(ORGANISATION.TENANT_CODE)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(tenantId))
                .fetchOne(ORGANISATION.TENANT_CODE),
        )

    private fun status(tenantId: UUID): String? =
        dsl
            .select(ORGANISATION.STATUS)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenantId))
            .fetchOne(ORGANISATION.STATUS)

    private fun displayName(tenantId: UUID): String? =
        dsl
            .select(ORGANISATION.DISPLAY_NAME)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenantId))
            .fetchOne(ORGANISATION.DISPLAY_NAME)

    private fun submittedBy(tenantId: UUID): UUID? =
        dsl
            .select(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_BY)
            .from(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(tenantId))
            .fetchOne(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.SUBMITTED_BY)

    private fun approvedBy(tenantId: UUID): UUID? =
        dsl
            .select(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_BY)
            .from(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP)
            .where(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.ORGANISATION_ID.eq(tenantId))
            .fetchOne(ORGANISATION_INITIAL_ADMINISTRATOR_BOOTSTRAP.APPROVED_BY)

    private fun emailOf(userId: UUID): String = "race-$userId@tenant-race.test"

    private fun seedUser(label: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "race-$label-$id")
            .set(USER_ACCOUNT.EMAIL, emailOf(id))
            .set(USER_ACCOUNT.DISPLAY_NAME, label)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    private inline fun <reified T : Throwable> assertIs(value: Throwable?): T {
        assertNotNull(value, "the decision succeeded; it should have been refused")
        assertTrue(value is T, "expected ${T::class.simpleName} but was $value")
        return value
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(30)
        const val POLL_MILLIS = 20L
        val executor = Executors.newCachedThreadPool()

        @JvmStatic
        @AfterAll
        fun shutDown() {
            executor.shutdownNow()
        }
    }
}
