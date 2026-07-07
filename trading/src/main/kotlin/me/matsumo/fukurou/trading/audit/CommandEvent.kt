package me.matsumo.fukurou.trading.audit

import me.matsumo.fukurou.trading.feed.StableFeedCursor
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
     * SOFT_HALT を DB 上で有効化した。
     */
    SOFT_HALT_SET,

    /**
     * 評価成績が kill 基準に到達した。
     */
    KILL_CRITERION_BREACHED,

    /**
     * 手動再開の骨格が reason 付きで実行された。
     */
    MANUAL_RESUME_REQUESTED,

    /**
     * one-shot runner の phase が完了した。
     */
    RUNNER_PHASE_COMPLETED,

    /**
     * one-shot runner の deterministic lifecycle action が完了した。
     */
    DECISION_LIFECYCLE_COMPLETED,

    /**
     * LLM daemon scheduler worker が起動した。
     */
    DAEMON_STARTED,

    /**
     * LLM daemon scheduler が起動を skip した。
     */
    DAEMON_TRIGGER_SKIPPED,

    /**
     * LLM daemon scheduler が one-shot 起動予約を取得した。
     */
    DAEMON_TRIGGER_LAUNCHED,

    /**
     * LLM daemon scheduler が one-shot 起動結果を記録した。
     */
    DAEMON_INVOCATION_COMPLETED,
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

    /**
     * 指定時刻以降に audit へ現れた distinct decision run ID 数を返す。
     */
    suspend fun countDistinctDecisionRunsSince(since: Instant): Result<Int>

    /**
     * 指定 decision run ID に紐づく tool call 監査イベント数を返す。
     */
    suspend fun countToolCallEvents(
        decisionRunId: String,
        toolNames: Set<String>,
    ): Result<Int>
}

/**
 * command_event_log を raw feed として読むための狭い read repository。
 */
interface CommandEventFeedReader {
    /**
     * 新しい順で command_event_log の event を読む。
     *
     * @param excludeEventTypes 除外する event_type。高頻度な heartbeat（例: RECONCILER_PASS_COMPLETED）を feed から外す用途に使う。
     */
    suspend fun findEvents(
        limit: Int,
        eventType: CommandEventType?,
        excludeEventTypes: Set<CommandEventType> = emptySet(),
    ): Result<List<CommandEvent>>

    /**
     * 指定時刻より古い command_event_log の event を新しい順で読む。
     *
     * @param before この時刻より古い event だけを返す排他的 cursor
     * @param eventTypes 許可する event_type。null なら全 event_type を対象にする
     * @param excludeEventTypes 除外する event_type。高頻度な heartbeat（例: RECONCILER_PASS_COMPLETED）を feed から外す用途に使う。
     */
    suspend fun findEventsBefore(
        limit: Int,
        before: Instant,
        eventTypes: Set<CommandEventType>?,
        excludeEventTypes: Set<CommandEventType> = emptySet(),
    ): Result<List<CommandEvent>>

    /**
     * 安定 cursor 条件に一致する command_event_log の event を Activity timeline 用に新しい順で読む。
     *
     * @param eventTypes 許可する event_type。null なら全 event_type を対象にする
     * @param excludeEventTypes 除外する event_type。高頻度な heartbeat（例: RECONCILER_PASS_COMPLETED）を feed から外す用途に使う。
     */
    suspend fun findEventsForStableFeed(
        cursor: StableFeedCursor,
        limit: Int,
        eventTypes: Set<CommandEventType>?,
        excludeEventTypes: Set<CommandEventType> = emptySet(),
    ): Result<List<CommandEvent>>
}
