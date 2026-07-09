package me.matsumo.fukurou.trading.decision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * system-prompt-v1 の file 参照と hash 計算を検証するテスト。
 */
class SystemPromptV1Test {

    @Test
    fun systemPromptV1_fileIsVersionedAndHashable() {
        val promptPath = repositoryRoot().resolve(SystemPromptV1.RELATIVE_PATH)
        val content = Files.readString(promptPath)
        val contentHash = SystemPromptV1.calculateContentHash(content)

        assertEquals("system-prompt-v1.10", SystemPromptV1.VERSION)
        assertTrue(content.contains("submit_decision"))
        assertTrue(content.contains("submit_falsification"))
        assertTrue(content.contains("preview_order"))
        assertTrue(content.contains("get_trade_intent"))
        assertTrue(content.contains("knowledge_get_recent_lessons"))
        assertTrue(content.contains("no_trade_conditions_ja"))
        assertTrue(content.contains("revision_count <= 2"))
        assertTrue(content.contains("LIMIT / STOP entry intent"))
        assertTrue(content.contains("entry を再開するための条件（entry trigger）"))
        assertTrue(content.contains("ロング前提を撤回するための条件（invalidation）"))
        assertTrue(content.contains("ENTER 提出を優先"))
        assertTrue(content.contains("invalidation が成立している場合は NO_TRADE を維持"))
        assertTrue(content.contains("現在価格に合わせて条件を単に切り上げ・切り下げ"))
        assertTrue(content.contains("ATR や volatility の高さだけを NO_TRADE 理由にしてはいけません"))
        assertTrue(content.contains("risk-based sizing で数量が縮小"))
        assertTrue(content.contains("STOP entry intent を提出できるかを必ず検討"))
        assertTrue(content.contains("LIMIT entry を maker fee(rebate)"))
        assertTrue(content.contains("MARKET / STOP entry を taker fee"))
        assertTrue(content.contains("market slippage reserve"))
        assertTrue(content.contains("p < 0.5 でも ENTER / ADD_LONG"))
        assertTrue(content.contains("REDUCE を提案する場合は"))
        assertTrue(content.contains("`close_ratio` は対象 position 残量の決済比率"))
        assertTrue(content.contains("EXIT は常に full close"))
        assertTrue(content.contains("ENTER / ADD_LONG は、Falsifier の APPROVED 後"))
        assertTrue(content.contains("STOP なしの ENTER / ADD_LONG"))
        assertTrue(content.contains("追加 risk が初回 risk budget の 50% 以下"))
        assertTrue(content.contains("APPROVED 後に runner"))
        assertTrue(content.contains("条件を変えて再試行せず"))
        assertTrue(content.contains("entry が成立しなかったものとして記録"))
        assertTrue(content.contains("未約定 entry order は TTL sweep に委ねます"))
        assertTrue(content.contains("target が現在価格と STOP の両方を上回る場合"))
        assertTrue(content.contains("読み替えられる余地を残してはいけません"))
        assertFalse(content.contains("`place_order` の前に必ず `preview_order` を呼び"))
        assertEquals(64, contentHash.length)
        assertTrue(contentHash.matches(Regex("[0-9a-f]{64}")))
    }
}

private fun repositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()

    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) {
            "repository root was not found."
        }
    }

    return candidate
}
