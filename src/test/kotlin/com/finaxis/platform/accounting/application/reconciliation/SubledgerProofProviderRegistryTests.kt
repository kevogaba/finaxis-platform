package com.finaxis.platform.accounting.application.reconciliation

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerAggregate
import com.finaxis.platform.accounting.SubledgerProofProvider
import com.finaxis.platform.accounting.SubledgerProofQuery
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SubledgerProofProviderRegistryTests {
    @Test
    fun `a provider named something the evidence row could not store fails at construction`() {
        // "Savings Ledger" passes every compiler check, supports its kind, and answers both reads
        // of a proof - then fails chk_control_account_reconciliation_run_provider at the insert,
        // after the work is done and with nothing recorded to show the run happened.
        val malformed =
            assertFailsWith<IllegalArgumentException> {
                SubledgerProofProviderRegistry(
                    listOf(provider("Savings Ledger", ControlSubledgerKind.SAVINGS_DEPOSITS)),
                )
            }
        assertEquals(true, malformed.message?.contains("Savings Ledger"))

        // The V9 character class, at both bounds, is accepted.
        SubledgerProofProviderRegistry(
            listOf(
                provider("savings.v2_", ControlSubledgerKind.SAVINGS_DEPOSITS),
                provider("L".repeat(128), ControlSubledgerKind.LOAN_PRINCIPAL),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            SubledgerProofProviderRegistry(
                listOf(provider("L".repeat(129), ControlSubledgerKind.LOAN_PRINCIPAL)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SubledgerProofProviderRegistry(
                listOf(provider("", ControlSubledgerKind.LOAN_PRINCIPAL)),
            )
        }
    }

    @Test
    fun `two providers claiming one control class fail at construction, not at the run`() {
        val contested =
            assertFailsWith<IllegalArgumentException> {
                SubledgerProofProviderRegistry(
                    listOf(
                        provider("savings", ControlSubledgerKind.SAVINGS_DEPOSITS),
                        provider("deposits", ControlSubledgerKind.SAVINGS_DEPOSITS),
                    ),
                )
            }
        assertEquals(true, contested.message?.contains("SAVINGS_DEPOSITS"))
    }

    @Test
    fun `a class with a provider resolves to it, and one without is a named refusal`() {
        val savings = provider("savings", ControlSubledgerKind.SAVINGS_DEPOSITS)
        val registry = SubledgerProofProviderRegistry(listOf(savings))

        assertSame(savings, registry.providerFor(ControlSubledgerKind.SAVINGS_DEPOSITS))

        // No product module exists yet, so a class nobody answers for is the ordinary case and is
        // reported per run rather than refused at startup.
        val missing =
            assertFailsWith<ConflictException> {
                registry.providerFor(ControlSubledgerKind.LOAN_PRINCIPAL)
            }
        assertEquals(PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING, missing.code)
    }

    @Test
    fun `no providers at all is a legitimate registry`() {
        val registry = SubledgerProofProviderRegistry(emptyList())

        assertFailsWith<ConflictException> {
            registry.providerFor(ControlSubledgerKind.SAVINGS_DEPOSITS)
        }
    }

    /**
     * The claim the port's KDoc and `docs/architecture/accounting-module-boundary.md` both make:
     * a bad registration fails the *context*, not the first proof that needs it.
     *
     * Asserting only that the constructor throws would leave that claim untested, because a
     * constructor can throw and still be reached lazily. This builds the registry the way
     * `AccountingModuleConfiguration` does - from whatever providers the context holds - and pins
     * that a deployment carrying a bad provider does not start.
     */
    @Test
    fun `a bad registration fails context startup rather than the first proof`() {
        val runner =
            ApplicationContextRunner()
                .withBean(SubledgerProofProviderRegistry::class.java)

        runner
            .withBean("malformed", SubledgerProofProvider::class.java, {
                provider("Savings Ledger", ControlSubledgerKind.SAVINGS_DEPOSITS)
            })
            .run { context ->
                assertNotNull(context.startupFailure, "a malformed provider name must not start")
            }

        runner
            .withBean("savings", SubledgerProofProvider::class.java, {
                provider("savings", ControlSubledgerKind.SAVINGS_DEPOSITS)
            })
            .withBean("deposits", SubledgerProofProvider::class.java, {
                provider("deposits", ControlSubledgerKind.SAVINGS_DEPOSITS)
            })
            .run { context ->
                assertNotNull(context.startupFailure, "a contested control class must not start")
            }

        // And the shape a real deployment has today: no providers, and it starts.
        runner.run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(SubledgerProofProviderRegistry::class.java))
        }
    }

    private fun provider(
        name: String,
        vararg kinds: ControlSubledgerKind,
    ) = object : SubledgerProofProvider {
        override val providerName = name

        override fun supports(kind: ControlSubledgerKind) = kind in kinds

        override fun aggregate(query: SubledgerProofQuery) =
            SubledgerAggregate(BigDecimal.ZERO, query.currencyCode, positionCount = 0)
    }
}
