package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.transitions.DispatchingTransitionLogRepository
import com.finaxis.platform.common.transitions.TransitionLogRepository
import com.finaxis.platform.common.transitions.TransitionLogWriter
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the dispatching repository is what the executor actually got, and that lifecycle claims
 * exactly the four aggregate types it has tables for.
 *
 * The second half is the one that decays quietly: a module that adds a state machine and forgets
 * its writer, or claims a type it has no table for, produces a runtime `error` inside a committed
 * transaction rather than a startup failure.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationTransitionLogOwnershipTests(
    private val applicationContext: ApplicationContext,
    private val transitionLogRepository: TransitionLogRepository,
) {
    @Test
    fun `the executor is wired to the dispatching repository, not to one module's writer`() {
        // AopUtils.getTargetClass, not ::class.simpleName: the moment this bean gains
        // @Transactional or any other advice, Spring hands back a proxy and the name changes even
        // though the target is still the dispatcher, failing for a reason unrelated to what is
        // asserted here.
        assertEquals(
            DispatchingTransitionLogRepository::class.java,
            AopUtils.getTargetClass(transitionLogRepository),
            "a module-specific repository here is how a second module's transitions started " +
                "failing inside a committed transaction",
        )
    }

    @Test
    fun `every aggregate type with a transition log table is claimed by exactly one writer`() {
        val writers = applicationContext.getBeansOfType(TransitionLogWriter::class.java).values
        val claimed =
            FOUNDATION_AGGREGATE_TYPES.associateWith { type ->
                writers.count { it.supports(type) }
            }

        assertEquals(
            FOUNDATION_AGGREGATE_TYPES.associateWith { 1 },
            claimed,
            "each foundation aggregate type must be owned by exactly one writer",
        )
        // Mutation-checked, because this asserts an absence: making lifecycle's `supports`
        // return true unconditionally fails here as intended.
        assertTrue(
            writers.none { it.supports("NOT_AN_AGGREGATE") },
            "a writer that claims an unknown type would swallow another module's logs",
        )
    }

    private companion object {
        /**
         * The four aggregate types `V1` created a transition-log table for.
         *
         * Deliberately written out here rather than read from the writer under test: reading them
         * back out of the thing being verified is how this test would pass for a writer that
         * claims nothing at all.
         */
        val FOUNDATION_AGGREGATE_TYPES =
            listOf("ORGANISATION", "BRANCH", "USER_ACCOUNT", "MEMBERSHIP")
    }
}
