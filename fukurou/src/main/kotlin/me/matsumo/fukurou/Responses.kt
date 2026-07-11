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
 * @param lastTransportActivityAt 最後に transport activity を確認した時刻
 * @param lastTradeAt 最後に realtime trade を確認した時刻
 * @param lastMaintenanceAt 最後に periodic maintenance が成功した時刻
 * @param marketDataState realtime market-data 接続状態
 * @param gapStartedAt active または直近 gap の開始時刻
 * @param recoveredAt gap 復旧後の最初の realtime event 時刻
 * @param gapReason gap 理由
 */
@Serializable
data class ReadinessResponse(
    val status: String,
    val lastTransportActivityAt: String? = null,
    val lastTradeAt: String? = null,
    val lastMaintenanceAt: String? = null,
    val marketDataState: String = "DISCONNECTED",
    val gapStartedAt: String? = null,
    val recoveredAt: String? = null,
    val gapReason: String? = null,
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
