package com.finaxis.platform.common.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import org.jooq.DSLContext
import org.jooq.impl.DataSourceConnectionProvider
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Asserts the production jOOQ and Spring transaction wiring, which nothing previously covered.
 *
 * Every atomicity guarantee in this codebase rests on one property: a jOOQ statement issued inside
 * a `@Transactional` method runs on the same physical PostgreSQL transaction Spring opened. That
 * property comes entirely from Spring Boot's `JooqAutoConfiguration` - there is no custom
 * `ConnectionProvider`, no custom `TransactionProvider` and no `@EnableTransactionManagement` in
 * this repository - so it is asserted here rather than assumed.
 *
 * The structural assertions can only prove the beans are of the expected types. The behavioural
 * assertion using `pg_current_xact_id()` is the one that actually proves shared transactional
 * state, and is the reason this test exists. See
 * `docs/architecture/financial-transaction-atomicity.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqSpringTransactionWiringTests(
    private val dsl: DSLContext,
    private val tenantSettingsService: TenantSettingsService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `jooq borrows connections through a transaction-aware datasource proxy`() {
        val connectionProvider = dsl.configuration().connectionProvider()
        val dataSourceProvider = assertIs<DataSourceConnectionProvider>(connectionProvider)

        assertIs<TransactionAwareDataSourceProxy>(
            dataSourceProvider.dataSource(),
            "jOOQ must borrow the transaction-bound connection, not a fresh pooled one",
        )
    }

    @Test
    fun `jooq delegates its own transaction api to spring`() {
        // Asserted on the simple name: Spring Boot's SpringTransactionProvider is package-private
        // in the spring-boot-jooq module, so it cannot be referenced as a type here.
        assertEquals(
            "SpringTransactionProvider",
            dsl.configuration().transactionProvider()::class.java.simpleName,
        )
    }

    @Test
    fun `transactional services are proxied with a transaction interceptor`() {
        assertTrue(
            AopUtils.isAopProxy(tenantSettingsService),
            "TenantSettingsService must be an AOP proxy for @Transactional to take effect",
        )
        val advised = assertIs<Advised>(tenantSettingsService)
        assertTrue(
            advised.advisors.any { it.advice is TransactionInterceptor },
            "expected a TransactionInterceptor in the advisor chain, found " +
                advised.advisors.map { it.advice::class.java.simpleName },
        )
    }

    @Test
    fun `two jooq statements inside one transaction share one postgres transaction`() {
        val (first, second) =
            requireNotNull(
                transactions.execute {
                    currentTransactionId() to currentTransactionId()
                },
            )

        assertEquals(
            first,
            second,
            "jOOQ statements inside one TransactionTemplate must share one PostgreSQL transaction",
        )
    }

    @Test
    fun `two jooq statements outside any transaction do not share a postgres transaction`() {
        val first = currentTransactionId()
        val second = currentTransactionId()

        assertNotEquals(
            first,
            second,
            "autocommit statements must each get their own PostgreSQL transaction - if these " +
                "match, the previous test proves nothing",
        )
    }

    @Test
    fun `transaction synchronization reports an active transaction only inside one`() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(
            requireNotNull(
                transactions.execute {
                    TransactionSynchronizationManager.isActualTransactionActive()
                },
            ),
        )
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    /**
     * Assigns and returns the backend's current transaction id. Requires PostgreSQL 13 or later;
     * this repository pins `postgres:18.4`.
     */
    private fun currentTransactionId(): String =
        requireNotNull(dsl.fetchValue("SELECT pg_current_xact_id()::text")).toString()
}
