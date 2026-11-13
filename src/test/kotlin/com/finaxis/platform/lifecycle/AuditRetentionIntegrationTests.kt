package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.persistence.TransactionLockBound
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.AuditRetention
import com.finaxis.platform.lifecycle.application.AuditRetentionBatch
import com.finaxis.platform.lifecycle.application.AuditRetentionProperties
import com.finaxis.platform.lifecycle.application.AuditRetentionProperties.Mode
import com.finaxis.platform.lifecycle.application.AuditRetentionStore
import com.finaxis.platform.lifecycle.application.AuditRetentionTarget
import com.finaxis.platform.lifecycle.application.BranchLifecycleStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationSettingsStore
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import com.finaxis.platform.lifecycle.domain.TenantSettingValueType
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audit retention job (#278, ADR 0042) against PostgreSQL: it deletes only a tenant's routine
 * successful rows older than its period (never fewer than the floor), in bounded batches, keeps
 * every refusal, failure, notable row and amendment the maker-checker rules still read (#255),
 * touches no tenant without a period, and records one HIGH row of its own.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuditRetentionIntegrationTests(
    private val dsl: DSLContext,
    private val store: AuditRetentionStore,
    private val batches: AuditRetentionBatch,
    private val auditEvents: AuditEventRepository,
    private val lockBound: TransactionLockBound,
    private val settings: OrganisationSettingsStore,
    private val branches: BranchLifecycleStore,
    private val transactions: TransactionTemplate,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val admin = seedUser()

    @Test
    fun `routine old rows go in batches and everything else stays`() {
        val tenant = tenant(retentionDays = "2555")
        val untouched = tenant(retentionDays = null)
        val draftBranch = branch(tenant, "DRAFT")
        val activeBranch = branch(tenant, "ACTIVE")
        val routine = List(3) { audit(tenant, OLD) }
        val denied = audit(tenant, OLD, outcome = "DENIED")
        val high = audit(tenant, OLD, severity = "HIGH")
        val recent = audit(tenant, RECENT)
        val stillRead = audit(tenant, OLD, action = "branch.update", branchEntity = draftBranch)
        val settled = audit(tenant, OLD, action = "branch.update", branchEntity = activeBranch)
        val otherTenant = audit(untouched, OLD)

        val run = retention(Mode.ON, batchSize = 2).run()

        assertTrue(run.deleted >= 4)
        (routine + settled).forEach { assertTrue(!exists(it), "deleted: $it") }
        listOf(denied, high, recent, stillRead, otherTenant).forEach {
            assertTrue(exists(it), "kept: $it")
        }
        // The amender of a branch still awaiting approval is still refused (#255).
        assertTrue(branches.hasAmended(tenant, draftBranch, admin))
        // Each batch that deleted rows wrote its own record, and together they count every row.
        val records = purgeRows(tenant)
        assertTrue(records >= 1)
        assertEquals(4L, purgedCount(tenant))
        assertEquals(0, purgeRows(untouched))

        // A second run finds nothing more and leaves the purge records alone.
        retention(Mode.ON).run()
        assertEquals(records, purgeRows(tenant))
    }

    @Test
    fun `a stored period below the floor keeps the floor`() {
        val tenant = tenant(retentionDays = "30")
        val yearOld = audit(tenant, OffsetDateTime.now(ZoneOffset.UTC).minusDays(YEAR))
        val decadeOld = audit(tenant, OLD)

        retention(Mode.ON).run()

        assertTrue(exists(yearOld), "30 days was stored, but the seven-year floor still applies")
        assertTrue(!exists(decadeOld))
    }

    @Test
    fun `a period raised or removed after the run listed the tenant is the one each batch uses`() {
        val tenant = tenant(retentionDays = "2555")
        val eightYearsOld = audit(tenant, OffsetDateTime.now(ZoneOffset.UTC).minusDays(EIGHT_YEARS))
        // The run listed the tenant at 2555 days; an administrator then raises the period.
        val listedEarlier = listing(AuditRetentionTarget(tenant, 2555))
        setPeriod(tenant, "3650")

        retention(Mode.ON, listing = listedEarlier).run()
        assertTrue(exists(eightYearsOld), "the raised period applies to the batch")

        deactivatePeriod(tenant)
        retention(Mode.ON, listing = listedEarlier).run()
        assertTrue(exists(eightYearsOld), "a removed period keeps everything")
        assertEquals(0, purgeRows(tenant))

        setPeriod(tenant, "2555")
        retention(Mode.ON, listing = listedEarlier).run()
        assertTrue(!exists(eightYearsOld))
        assertEquals(1L, purgedCount(tenant))
    }

    @Test
    fun `a purge record that cannot be written takes its batch back and the run moves on`() {
        val failing = tenant(retentionDays = "2555")
        val healthy = tenant(retentionDays = "2555")
        val kept = audit(failing, OLD)
        val gone = audit(healthy, OLD)
        val listed =
            listing(AuditRetentionTarget(failing, 2555), AuditRetentionTarget(healthy, 2555))

        retention(Mode.ON, listing = listed, batch = batchFailingRecordFor(failing)).run()

        assertTrue(exists(kept), "the delete rolled back with its record")
        assertEquals(0, purgeRows(failing))
        assertTrue(!exists(gone), "the next organisation was still purged")
        assertEquals(1L, purgedCount(healthy))
    }

    @Test
    fun `targets are read in organisation-id windows that cover every tenant`() {
        val stored = List(3) { tenant(retentionDays = "2555") }
        val total = store.countRetentionTargets()

        val ids =
            (0 until total)
                .flatMap {
                    store.retentionTargets(
                        1,
                        it,
                    )
                }.map { it.organisationId }

        assertEquals(total, ids.distinct().size, "each window holds different tenants")
        assertEquals(ids.sorted(), ids, "in organisation-id order")
        assertTrue(ids.containsAll(stored))
    }

    @Test
    fun `dry run counts and off reads nothing and neither deletes`() {
        val tenant = tenant(retentionDays = "2555")
        val row = audit(tenant, OLD)

        val dryRun = retention(Mode.DRY_RUN).run()
        val off = retention(Mode.OFF).run()

        assertTrue(dryRun.deletable >= 1)
        assertEquals(0, dryRun.deleted)
        assertEquals(0, off.organisations)
        assertTrue(exists(row))
        assertEquals(0, purgeRows(tenant))
    }

    @Test
    fun `two runs at once never delete a row twice`() {
        val tenant = tenant(retentionDays = "2555")
        val rows = List(CONCURRENT_ROWS) { audit(tenant, OLD) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val runs =
            try {
                val futures =
                    List(2) {
                        pool.submit<Long> {
                            start.await()
                            retention(Mode.ON, batchSize = 1).run().deleted
                        }
                    }
                start.countDown()
                futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        rows.forEach { assertTrue(!exists(it)) }
        assertTrue(runs.sum() >= CONCURRENT_ROWS)
        assertEquals(
            CONCURRENT_ROWS.toLong(),
            purgedCount(tenant),
            "the purge records add up to exactly the rows deleted",
        )
    }

    private fun retention(
        mode: Mode,
        batchSize: Int = 500,
        listing: AuditRetentionStore = store,
        batch: AuditRetentionBatch = batches,
    ) = AuditRetention(
        listing,
        batch,
        AuditRetentionProperties(
            mode = mode,
            batchSize = batchSize,
            maxBatchesPerRun = 100,
            lockTimeout = Duration.ofSeconds(5),
        ),
        Clock.systemUTC(),
    )

    /** The store, except that the run lists exactly [targets], as if read before a change. */
    private fun listing(vararg targets: AuditRetentionTarget): AuditRetentionStore =
        object : AuditRetentionStore by store {
            override fun countRetentionTargets() = targets.size

            override fun retentionTargets(
                limit: Int,
                offset: Int,
            ) = targets.toList()
        }

    /**
     * A batch whose purge record fails for [organisationId], run as Spring would run the bean's
     * method: in a transaction of its own.
     */
    private fun batchFailingRecordFor(organisationId: UUID): AuditRetentionBatch {
        val failingAudit =
            object : AuditService(auditEvents, Clock.systemUTC()) {
                override fun record(command: AuditCommand): AuditEvent {
                    check(command.tenantId != organisationId.toString()) { "record refused" }
                    return super.record(command)
                }
            }
        val requiresNew =
            TransactionTemplate(requireNotNull(transactions.transactionManager)).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }
        return object :
            AuditRetentionBatch(store, settings, failingAudit, lockBound, Clock.systemUTC()) {
            override fun purgeBatch(
                organisationId: UUID,
                batchSize: Int,
                lockTimeout: Duration,
            ): Int =
                requireNotNull(
                    requiresNew.execute {
                        super.purgeBatch(organisationId, batchSize, lockTimeout)
                    },
                )
        }
    }

    private fun setPeriod(
        organisationId: UUID,
        days: String,
    ) {
        transactions.executeWithoutResult {
            settings.upsertSetting(
                organisationId,
                TenantSettingCatalog.AUDIT_RETENTION_DAYS_KEY,
                days,
                TenantSettingValueType.INT.name,
                false,
                SystemActor.ID,
            )
        }
    }

    private fun deactivatePeriod(organisationId: UUID) {
        transactions.executeWithoutResult {
            settings.deactivateSetting(
                organisationId,
                TenantSettingCatalog.AUDIT_RETENTION_DAYS_KEY,
                SystemActor.ID,
            )
        }
    }

    private fun tenant(retentionDays: String?): UUID {
        val organisationId = fixture.createActiveOrganisation("retention", admin)
        retentionDays?.let { days ->
            // Written by the store, not the catalogue, so a below-floor value can be planted.
            transactions.executeWithoutResult {
                settings.upsertSetting(
                    organisationId,
                    TenantSettingCatalog.AUDIT_RETENTION_DAYS_KEY,
                    days,
                    TenantSettingValueType.INT.name,
                    false,
                    SystemActor.ID,
                )
            }
        }
        return organisationId
    }

    private fun branch(
        organisationId: UUID,
        status: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "R-${id.toString().takeLast(8)}")
            .set(BRANCH.BRANCH_NAME, "Retention Branch")
            .set(BRANCH.BRANCH_TYPE, "OPERATIONAL")
            .set(BRANCH.STATUS, status)
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun audit(
        organisationId: UUID,
        at: OffsetDateTime,
        action: String = "test.routine",
        outcome: String = "SUCCESS",
        severity: String = "INFO",
        branchEntity: UUID? = null,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, id)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(AUDIT_EVENT.EVENT_TIME, at)
            .set(AUDIT_EVENT.ACTOR_USER_ID, admin)
            .set(AUDIT_EVENT.ACTOR_TYPE, "USER")
            .set(AUDIT_EVENT.EVENT_TYPE, "TEST")
            .set(AUDIT_EVENT.ENTITY_TYPE, if (branchEntity == null) "TEST" else "BRANCH")
            .set(AUDIT_EVENT.ENTITY_ID, branchEntity)
            .set(AUDIT_EVENT.ACTION, action)
            .set(AUDIT_EVENT.OUTCOME, outcome)
            .set(AUDIT_EVENT.SEVERITY, severity)
            .execute()
        return id
    }

    private fun exists(id: UUID): Boolean = dsl.fetchExists(AUDIT_EVENT, AUDIT_EVENT.ID.eq(id))

    private fun purgeRows(organisationId: UUID): Int =
        dsl.fetchCount(
            AUDIT_EVENT,
            AUDIT_EVENT.ORGANISATION_ID
                .eq(organisationId)
                .and(AUDIT_EVENT.ACTION.eq("audit.retention_purge"))
                .and(AUDIT_EVENT.ACTOR_TYPE.eq("SYSTEM"))
                .and(AUDIT_EVENT.SEVERITY.eq("HIGH")),
        )

    private fun purgedCount(organisationId: UUID): Long =
        dsl
            .select(AUDIT_EVENT.METADATA_JSONB)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
            .and(AUDIT_EVENT.ACTION.eq("audit.retention_purge"))
            .fetch { record ->
                Regex("\"deleted\"\\s*:\\s*(\\d+)")
                    .find(requireNotNull(record.value1()).data())
                    ?.groupValues
                    ?.get(1)
                    ?.toLong() ?: 0L
            }.sum()

    private fun seedUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "retention-$id")
            .set(USER_ACCOUNT.EMAIL, "retention-$id@retention.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Retention Admin")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    private companion object {
        val OLD: OffsetDateTime = OffsetDateTime.parse("2010-01-01T00:00:00Z")
        val RECENT: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)
        const val YEAR = 365L
        const val EIGHT_YEARS = 8 * YEAR
        const val CONCURRENT_ROWS = 6
        const val WAIT_SECONDS = 60L
    }
}
