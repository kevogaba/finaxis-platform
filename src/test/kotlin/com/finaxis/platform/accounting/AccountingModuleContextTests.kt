package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.adapter.outbound.context.RequestContextAccountingLookup
import com.finaxis.platform.accounting.application.ledger.DefaultPostingService
import com.finaxis.platform.accounting.application.ledger.PostingLegResolver
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.reconciliation.ProofSnapshot
import com.finaxis.platform.accounting.application.reconciliation.SubledgerProofProviderRegistry
import com.finaxis.platform.accounting.application.rules.RuleBackedPostingLegResolver
import com.finaxis.platform.common.application.ConflictException
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Proves the accounting module is a live Spring module rather than a folder of interfaces, and
 * pins what it deliberately does not yet provide.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AccountingModuleContextTests(
    private val applicationContext: ApplicationContext,
) {
    @Test
    fun `the accounting context lookup is wired`() {
        val lookup = applicationContext.getBean(AccountingContextLookup::class.java)

        assertIs<RequestContextAccountingLookup>(lookup)
    }

    @Test
    fun `identity supplies the accounting permission guard`() {
        assertNotNull(applicationContext.getBean(AccountingPermissionGuard::class.java))
    }

    @Test
    fun `lifecycle supplies the accounting business date and tenant lookups`() {
        assertNotNull(applicationContext.getBean(AccountingBusinessDateLookup::class.java))
        assertNotNull(applicationContext.getBean(AccountingTenantLookup::class.java))
    }

    @Test
    fun `exactly one posting service exists and it is the engine-backed one`() {
        // Inverted from Phase A, which asserted the bean's absence so a partial implementation
        // would be a visible change. Issue #41 is that change: one bean, the engine behind it.
        assertContentEquals(
            arrayOf("postingService"),
            applicationContext.getBeanNamesForType(PostingService::class.java),
        )
        assertIs<DefaultPostingService>(applicationContext.getBean(PostingService::class.java))
    }

    @Test
    fun `the engine resolves intents through the rule-backed resolver`() {
        // Issue #45 replaced the Phase C placeholder. Asserting the type means a second resolver
        // landing beside it - two beans, or a silently overridden one - is a visible change rather
        // than a startup ambiguity.
        assertIs<RuleBackedPostingLegResolver>(
            applicationContext.getBean(PostingLegResolver::class.java),
        )
    }

    @Test
    fun `the sub-ledger provider registry is wired and empty, and says so per run`() {
        // No product module exists, so the registry validates an empty set and starts. The absence
        // is reported when a proof asks for a class, not by refusing to boot - which is the
        // distinction that lets the platform run before any sub-ledger does.
        val registry = applicationContext.getBean(SubledgerProofProviderRegistry::class.java)

        val missing =
            assertFailsWith<ConflictException> {
                registry.providerFor(ControlSubledgerKind.SAVINGS_DEPOSITS)
            }
        assertEquals(PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING, missing.code)
    }

    @Test
    fun `the proof snapshot port has a PostgreSQL implementation`() {
        // The reconciliation proof reads both of its sides from one snapshot, which needs an
        // adapter that can both prove the isolation and export the snapshot identity a provider
        // outside the transaction adopts. A missing bean here would leave the service unbuildable.
        assertNotNull(applicationContext.getBean(ProofSnapshot::class.java))
    }
}
