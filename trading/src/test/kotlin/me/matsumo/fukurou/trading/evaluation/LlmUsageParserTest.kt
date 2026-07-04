package me.matsumo.fukurou.trading.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * LlmUsageParser の best-effort 抽出を検証するテスト。
 */
class LlmUsageParserTest {

    @Test
    fun parseClaudeStdout_extractsCostTurnsDurationUsageAndModelUsage() {
        val details = LlmUsageParser.parseClaudeStdout(
            """
                {
                  "total_cost_usd": 0.0123,
                  "num_turns": 3,
                  "duration_ms": 1200,
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 40,
                    "cache_creation_input_tokens": 5,
                    "cache_read_input_tokens": 9
                  },
                  "modelUsage": {
                    "claude-sonnet": {
                      "input_tokens": 80,
                      "output_tokens": 30
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("0.0123", details?.totalCostUsd?.toPlainString())
        assertEquals(3, details?.numTurns)
        assertEquals(1200, details?.durationMs)
        assertEquals(100, details?.usage?.inputTokens)
        assertEquals("claude-sonnet", details?.modelUsages?.single()?.model)
        assertEquals(80, details?.modelUsages?.single()?.usage?.inputTokens)
    }

    @Test
    fun parseClaudeStdout_returnsNullForNonJsonOrTruncatedOutput() {
        assertNull(LlmUsageParser.parseClaudeStdout("not-json"))
        assertNull(LlmUsageParser.parseClaudeStdout("""{"total_cost_usd":0.1}...[TRUNCATED]"""))
    }

    @Test
    fun parseClaudeStdout_acceptsPartialUsageFields() {
        val details = LlmUsageParser.parseClaudeStdout(
            """
                {
                  "total_cost_usd": 0.01,
                  "num_turns": 2
                }
            """.trimIndent(),
        )

        assertEquals("0.01", details?.totalCostUsd?.toPlainString())
        assertEquals(2, details?.numTurns)
        assertNull(details?.usage)
    }

    @Test
    fun toJsonObject_keepsOnlyNumericUsageAndModelNames() {
        val details = LlmUsageParser.parseClaudeStdout(
            """
                {
                  "total_cost_usd": 0.01,
                  "num_turns": 2,
                  "session_id": "secret-session",
                  "transcript_path": "/tmp/secret-token",
                  "modelUsage": {
                    "claude-sonnet": {
                      "input_tokens": 10,
                      "output_tokens": 4,
                      "unexpected_string": "secret-value"
                    }
                  }
                }
            """.trimIndent(),
        )

        val savedJson = LlmUsageParser.toJsonObject(requireNotNull(details)).toString()

        assertFalse(savedJson.contains("secret-session"))
        assertFalse(savedJson.contains("secret-token"))
        assertFalse(savedJson.contains("secret-value"))
        assertEquals("""{"totalCostUsd":"0.01","numTurns":2,"modelUsage":{"claude-sonnet":{"inputTokens":10,"outputTokens":4}}}""", savedJson)
    }
}
