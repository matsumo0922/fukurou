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
    private val stableRequestMutexRegistry: StableRequestMutexRegistry = StableRequestMutexRegistry(),
    private val faultAfterLedgerPublishBeforeConsumption: (() -> Unit)? = null,
) : AuthorizedAtomicPaperEntryBackend {

    override suspend fun <T> withStableRequestScope(
        identity: AuthorizedAtomicEntryIdentity,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T {
        identity.requireValid()

        return stableRequestMutexRegistry.withLock(identity.clientRequestId) {
            InMemoryStableRequestScope.block()
        }
    }

    override suspend fun strictReplay(identity: AuthorizedAtomicEntryIdentity): Result<AuthorizedPlaceOrderReplay> {
        identity.requireValid()

        return ledger.findAuthorizedPlaceOrderReplay(identity)
    }

    override suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult> {
        request.requireStableIdentityValid()
        val initialReplay = strictReplay(request.identity)
        initialReplay.exceptionOrNull()?.let { failure -> return Result.failure(failure) }
        when (val replay = initialReplay.getOrThrow()) {
            is AuthorizedPlaceOrderReplay.Exact -> return Result.success(AuthorizedAtomicEntryResult.Exact(replay.result))
            AuthorizedPlaceOrderReplay.Ambiguous -> {
                return Result.failure(AuthorizedAtomicEntryReplayIndeterminateException())
            }
            AuthorizedPlaceOrderReplay.Missing -> Unit
        }

        return decisions.withAuthorizedAtomicEntryTransaction {
            ledger.commitAuthorizedAtomicEntry(
                decision = this,
                request = request,
                faultAfterPublish = faultAfterLedgerPublishBeforeConsumption,
            )
        }
    }
}

private data object InMemoryStableRequestScope : AuthorizedStableRequestScope {
    override suspend fun verifyOwnership(): Result<Unit> = Result.success(Unit)

    override fun markBackendResultConfirmed() = Unit
}
