package me.matsumo.fukurou.trading.persistence

import me.matsumo.fukurou.trading.broker.AuthorizedAtomicEntryResult
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicPaperEntryBackend
import me.matsumo.fukurou.trading.broker.AuthorizedAtomicPaperEntryRequest

/** Exposed/JDBC writer を A2a の module-internal capability として公開する adapter。 */
internal class ExposedAuthorizedAtomicPaperEntryBackend(
    private val writer: ExposedPaperLedgerWriter,
) : AuthorizedAtomicPaperEntryBackend {
    override suspend fun commit(request: AuthorizedAtomicPaperEntryRequest): Result<AuthorizedAtomicEntryResult> {
        return writer.commitAuthorizedAtomicEntry(request)
    }
}

/** A1 / public broker path から接続しない internal capability adapter を返す。 */
internal fun ExposedPaperLedgerRepository.authorizedAtomicEntryBackend(): AuthorizedAtomicPaperEntryBackend {
    return ExposedAuthorizedAtomicPaperEntryBackend(writer)
}
