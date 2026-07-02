package me.matsumo.fukurou.trading.risk

import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.domain.AccountStatus

/**
 * Broker 読み取り層から account status を組み立てる service。
 */
class AccountStatusService(
    private val broker: Broker,
) {

    /**
     * DB risk_state と paper ledger を唯一の真実として account status を返す。
     */
    suspend fun getAccountStatus(): Result<AccountStatus> {
        return broker.getAccountStatus()
    }
}
