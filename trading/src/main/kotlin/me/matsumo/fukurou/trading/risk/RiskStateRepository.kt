package me.matsumo.fukurou.trading.risk

import java.time.Instant

/**
 * risk_state single row を読み書きする repository。
 */
interface RiskStateRepository {
    /**
     * 現在の risk_state を読む。
     */
    suspend fun current(): Result<RiskState>

    /**
     * sticky HARD_HALT を reason 付きで立てる。
     */
    suspend fun setHardHalt(reason: String, at: Instant): Result<RiskState>

    /**
     * SOFT_HALT を reason 付きで立てる。
     */
    suspend fun setSoftHalt(reason: String, at: Instant): Result<RiskState>

    /**
     * cleanup SAFE と zero-open-risk を確認して手動再開を reason 付きで記録する。
     */
    suspend fun resume(reason: String, at: Instant): Result<RiskState>
}
