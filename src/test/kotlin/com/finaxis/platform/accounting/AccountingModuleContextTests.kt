package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.adapter.outbound.context.RequestContextAccountingLookup
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingService
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertContentEquals
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
    fun `no posting service implementation exists yet`() {
        // Deliberate: issue #31 ships the contract, issue #41 ships the engine. Asserting the
        // absence means a later accidental partial implementation is a visible, reviewed change
        // rather than something that quietly starts satisfying injection points.
        assertContentEquals(
            emptyArray(),
            applicationContext.getBeanNamesForType(PostingService::class.java),
        )
    }
}
