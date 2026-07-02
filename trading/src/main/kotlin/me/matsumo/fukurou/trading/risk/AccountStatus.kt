package me.matsumo.fukurou.trading.risk

import kotlinx.serialization.Serializable

/**
 * account.get_account_status が返す最小 account status。
 *
 * @param mode Step1.5 では paper 前提の実行モード
 * @param riskState DB risk_state から導いた状態
 * @param drawdownRatio DB risk_state 上の drawdown ratio
 * @param hardHalt DB risk_state が唯一の真実として持つ HARD_HALT flag
 */
@Serializable
data class AccountStatus(
    val mode: String,
    val riskState: String,
    val drawdownRatio: String,
    val hardHalt: Boolean,
)

/**
 * risk_state から read 系 account status を組み立てる service。
 */
class AccountStatusService(
    private val riskStateRepository: RiskStateRepository,
) {

    /**
     * DB risk_state を唯一の真実として account status を返す。
     */
    suspend fun getAccountStatus(): Result<AccountStatus> {
        return riskStateRepository.current().map { riskState ->
            AccountStatus(
                mode = "PAPER",
                riskState = if (riskState.hardHalt) "HARD_HALT" else "RUNNING",
                drawdownRatio = riskState.drawdownRatio.toPlainString(),
                hardHalt = riskState.hardHalt,
            )
        }
    }
}
