package com.finaxis.platform.accounting.application.reconciliation

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerProofProvider
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException

/**
 * The one place a [SubledgerProofProvider] is resolved, and the point at which a malformed or
 * conflicting registration is refused.
 *
 * Both checks run once, when the bean is constructed, so a misconfiguration fails context startup
 * rather than the run that needed it. That matters differently for each:
 *
 * - **A name the evidence row would reject.** `chk_control_account_reconciliation_run_provider`
 *   accepts [SubledgerProofProvider.PROVIDER_NAME_PATTERN] only. A provider called `Savings Ledger`
 *   otherwise compiles, starts, answers both reads of a proof, and fails at the insert - after the
 *   proof has done all of its work and with nothing recorded to show it ran.
 * - **Two providers claiming one class.** Ownership of a subsidiary ledger is exclusive: two
 *   answers for `SAVINGS_DEPOSITS` mean the ledger has no single aggregate, and picking either is
 *   arbitrary. This was previously a `check` inside the run path, which made a deployment-time
 *   defect look like a runtime failure of one tenant's proof.
 *
 * A class with **no** provider is not a defect and is not checked here: no product module exists
 * yet, and a control account whose class nobody answers for is reported per run as
 * [PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING].
 */
class SubledgerProofProviderRegistry(
    providers: List<SubledgerProofProvider>,
) {
    private val byKind: Map<ControlSubledgerKind, SubledgerProofProvider>

    init {
        val malformed =
            providers
                .map { it.providerName }
                .filterNot { SubledgerProofProvider.PROVIDER_NAME_PATTERN.matches(it) }
        require(malformed.isEmpty()) {
            "sub-ledger proof provider names $malformed do not match " +
                "${SubledgerProofProvider.PROVIDER_NAME_PATTERN.pattern}, which " +
                "control_account_reconciliation_run.provider requires"
        }
        val claims =
            ControlSubledgerKind.entries.associateWith { kind ->
                providers.filter { it.supports(kind) }
            }
        val contested =
            claims
                .filterValues { it.size > 1 }
                .mapValues { (_, claimants) -> claimants.map(SubledgerProofProvider::providerName) }
        require(contested.isEmpty()) {
            "control classes are claimed by more than one provider ($contested); " +
                "ownership of a subsidiary ledger must be exclusive"
        }
        byKind =
            claims
                .mapValues { (_, claimants) -> claimants.singleOrNull() }
                .filterValues { it != null }
                .mapValues { (_, provider) -> requireNotNull(provider) }
    }

    /** The provider that owns [kind], or a refusal naming the class no module answers for. */
    fun providerFor(kind: ControlSubledgerKind): SubledgerProofProvider =
        byKind[kind]
            ?: throw ConflictException(
                code = PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING,
                safeDetail = "No module answers for the $kind subsidiary ledger yet.",
            )
}
