package me.matsumo.fukurou.trading.decision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

        assertEquals("system-prompt-v1.2", SystemPromptV1.VERSION)
        assertTrue(content.contains("submit_decision"))
        assertTrue(content.contains("submit_falsification"))
        assertTrue(content.contains("preview_order"))
        assertTrue(content.contains("knowledge.get_recent_lessons"))
        assertTrue(content.contains("no_trade_conditions_ja"))
        assertTrue(content.contains("revision_count <= 2"))
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
