package me.matsumo.fukurou.trading.broker

import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository

/**
 * InMemory の authorized entry を decision、ledger、equity の順で直列化する A2a adapter。
 *
 * public broker path からは未接続である。
 */
internal class InMemoryAuthorizedAtomicPaperEntryBackend(
    private val decisions: InMemoryDecisionRepository,
    private val ledger: InMemoryPaperLedgerRepository,
    private val faultAfterLedgerPublishBeforeConsumption: (() -> Unit)? = null,
) : AuthorizedAtomicPaperEntryBackend {

    override suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult> {
        return decisions.withAuthorizedAtomicEntryTransaction {
            ledger.commitAuthorizedAtomicEntry(
                decision = this,
                request = request,
                faultAfterPublish = faultAfterLedgerPublishBeforeConsumption,
            )
        }
    }
}
