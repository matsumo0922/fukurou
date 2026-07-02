package me.matsumo.fukurou.trading.audit

import java.time.Instant
import java.util.UUID

/**
 * command_event_log に保存するイベント種別。
 */
enum class CommandEventType {
    /**
     * read 系を含む tool call が完了した。
     */
    TOOL_CALL_COMPLETED,

    /**
     * HARD_HALT により trade 系 tool call を拒否した。
     */
    TOOL_CALL_REJECTED_BY_HARD_HALT,

    /**
     * 呼び出し元契約として新規取引をせず終了した。
     */
    NO_TRADE_EXIT,

    /**
     * ProtectionReconciler worker が起動した。
     */
    RECONCILER_STARTED,

    /**
     * ProtectionReconciler が reconcile pass を完了した。
     */
    RECONCILER_PASS_COMPLETED,

    /**
     * ProtectionReconciler の pass が失敗状態へ遷移した。
     */
    RECONCILER_PASS_FAILED,

    /**
     * ProtectionReconciler の pass が失敗状態から回復した。
     */
    RECONCILER_PASS_RECOVERED,

    /**
     * HARD_HALT を DB 上で有効化した。
     */
    HARD_HALT_SET,

    /**
     * 手動再開の骨格が reason 付きで実行された。
     */
    MANUAL_RESUME_REQUESTED,
}

/**
 * command_event_log に append-only で残す監査イベント。
 *
 * @param id event log 行 ID
 * @param decisionRunContext LLM 起動と prompt 系の監査コンテキスト
 * @param toolName tool 名。reconciler など tool でない場合は論理名を入れる
 * @param toolCallId tool call 単位の ID
 * @param clientRequestId 呼び出し元が渡した冪等化・追跡用 ID
 * @param eventType 監査イベント種別
 * @param payload JSON 文字列 payload
 * @param occurredAt 発生時刻
 */
data class CommandEvent(
    val id: UUID = UUID.randomUUID(),
    val decisionRunContext: DecisionRunContext = DecisionRunContext.fromEnvironment(),
    val toolName: String,
    val toolCallId: String?,
    val clientRequestId: String?,
    val eventType: CommandEventType,
    val payload: String,
    val occurredAt: Instant,
)

/**
 * command_event_log へ監査イベントを保存する repository。
 */
interface CommandEventLog {
    /**
     * event を append-only で保存する。
     */
    suspend fun append(event: CommandEvent): Result<Unit>
}
