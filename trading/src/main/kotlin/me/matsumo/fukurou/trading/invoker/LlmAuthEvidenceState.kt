package me.matsumo.fukurou.trading.invoker

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ある provider の credential が失敗したことを示す観測結果。
 *
 * provider output、例外 message、credential の内容は保持しない。監視 API が status を決めるために
 * 必要な最小限だけを持つ。
 *
 * @param observedAt evidence を観測した時刻
 * @param credentialGeneration この invocation が使った credential 世代
 * （renderer が per-run copy を作る直前に観測した credential source の最終更新時刻）。
 * 世代を観測できなかった invocation では null
 */
data class LlmAuthFailureEvidence(
    val observedAt: Instant,
    val credentialGeneration: Instant?,
)

/** provider 別の CLI auth 失敗 evidence を読む境界。 */
fun interface LlmAuthEvidenceReader {
    /** 指定 provider の最後の失敗 evidence を返す。無ければ null。 */
    fun lastFailure(provider: LlmProvider): LlmAuthFailureEvidence?
}

/**
 * LLM invocation 経路と監視 API が共有する in-process の CLI auth 失敗 evidence。
 *
 * credential が失効しているかを判定するために必要なのは「現在の credential が失敗を出したか」という
 * 1 bit であり、過去の全履歴ではない。invocation は監視 API と同じ process 内で走るため、観測した
 * 時点でその 1 bit を持てば足りる。audit log への記録は人間の事後診断用として別に残す。
 *
 * daemon tick の liveness を持つ `MutableLlmDaemonTickStatus` と同じ役割だが、provider 単位の
 * 複合更新を行うため `ConcurrentHashMap.compute` で原子性を確保する。
 *
 * process 再起動で内容は失われる。再起動直後は evidence 無しとして扱われ、失効が継続していれば
 * 次の invocation が作り直す。別 process で走る direct runner の観測も届かない。
 */
class LlmAuthEvidenceState : LlmAuthEvidenceReader {

    private val failures = ConcurrentHashMap<LlmProvider, LlmAuthFailureEvidence>()

    override fun lastFailure(provider: LlmProvider): LlmAuthFailureEvidence? = failures[provider]

    /**
     * 失敗 evidence を記録する。
     *
     * 既存 evidence より古い credential 世代では上書きしない。旧世代の invocation が新世代の
     * invocation より後に完了し得るため、無条件に上書きすると旧世代 evidence が新世代 evidence を
     * 消し、世代比較で無視されて降格が解除されてしまう。同一世代なら観測時刻の新しい方を残す。
     */
    fun recordFailure(provider: LlmProvider, evidence: LlmAuthFailureEvidence) {
        failures.compute(provider) { _, existing ->
            if (existing != null && existing.supersedes(evidence)) existing else evidence
        }
    }
}

/** この evidence が [candidate] より新しい（= candidate で上書きすべきでない）かを返す。 */
private fun LlmAuthFailureEvidence.supersedes(candidate: LlmAuthFailureEvidence): Boolean {
    val currentGeneration = credentialGeneration
    val candidateGeneration = candidate.credentialGeneration
    val bothGenerationsKnown = currentGeneration != null && candidateGeneration != null
    val generationsDiffer = bothGenerationsKnown && currentGeneration != candidateGeneration

    if (generationsDiffer) {
        return requireNotNull(currentGeneration).isAfter(candidateGeneration)
    }

    // 世代が同じか、どちらかが世代不明の場合は観測時刻で比べる
    return observedAt.isAfter(candidate.observedAt)
}
