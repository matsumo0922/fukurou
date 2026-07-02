package me.matsumo.fukurou

import kotlinx.serialization.Serializable

/**
 * ヘルスチェック応答。サーバの稼働確認に用いる最小レスポンス。
 *
 * @param status 稼働状態。正常時は "ok"
 * @param service サービス識別子
 */
@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "fukurou",
)

/**
 * readiness 応答。外部依存を含めた処理可否を表す。
 *
 * @param status 状態。準備完了は "ready"、未準備は "not_ready"
 * @param lastReconciledAt ProtectionReconciler が最後に pass を完了した時刻
 * @param lastMarketDataAt ProtectionReconciler が最後に market data を見た時刻
 */
@Serializable
data class ReadinessResponse(
    val status: String,
    val lastReconciledAt: String? = null,
    val lastMarketDataAt: String? = null,
)

/**
 * エラー応答。未捕捉例外を JSON で返すための共通フォーマット。
 *
 * @param message エラー内容の概要
 */
@Serializable
data class ErrorResponse(
    val message: String,
)
