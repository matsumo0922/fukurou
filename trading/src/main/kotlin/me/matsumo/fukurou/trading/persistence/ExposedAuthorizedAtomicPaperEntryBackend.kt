package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryIdentity
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryResult
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryUnavailableException
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicPaperEntryBackend
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicPaperEntryRequest
import me.matsumo.fukurou.trading.broker.AuthorizedPlaceOrderReplay
import me.matsumo.fukurou.trading.broker.AuthorizedStableRequestExecutionScope
import me.matsumo.fukurou.trading.broker.AuthorizedStableRequestScope

/** Exposed/JDBC writer を A2a の module-internal capability として公開する adapter。 */
internal class ExposedAuthorizedAtomicPaperEntryBackend(
    private val writer: ExposedPaperLedgerWriter,
    private val stableRequestScope: AuthorizedStableRequestExecutionScope? = null,
) : AuthorizedAtomicPaperEntryBackend {
    override suspend fun <T> withStableRequestScope(
        identity: AuthorizedAtomicEntryIdentity,
        block: suspend AuthorizedStableRequestScope.() -> T,
    ): T {
        identity.requireValid()
        val scope = stableRequestScope ?: throw AuthorizedAtomicEntryUnavailableException(
            IllegalStateException("PostgreSQL stable request scope is not configured."),
        )

        return scope.withScope(identity, block)
    }

    override suspend fun strictReplay(identity: AuthorizedAtomicEntryIdentity): Result<AuthorizedPlaceOrderReplay> {
        identity.requireValid()

        return writer.readAuthorizedAtomicEntryReplay(identity)
    }

    override suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult> {
        return writer.commitAuthorizedAtomicEntry(request)
    }
}

/** A1 / public broker path から接続しない internal capability adapter を返す。 */
internal fun ExposedPaperLedgerRepository.authorizedAtomicEntryBackend(): AuthorizedAtomicPaperEntryBackend {
    return ExposedAuthorizedAtomicPaperEntryBackend(writer)
}

/** opaque rootが所有するstable request scopeを注入したinternal capability adapterを返す。 */
internal fun ExposedPaperLedgerRepository.authorizedAtomicEntryBackend(
    stableRequestScope: AuthorizedStableRequestExecutionScope,
): AuthorizedAtomicPaperEntryBackend {
    return ExposedAuthorizedAtomicPaperEntryBackend(writer, stableRequestScope)
}
