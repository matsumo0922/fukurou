package me.matsumo.fukurou.trading.decision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 設計書内の MCP tool 名表記を検証するテスト。
 */
class DesignDocumentMcpToolNameTest {

    @Test
    fun designDocument_usesFlatMcpToolNames() {
        val content = Files.readString(repositoryRoot().resolve("docs/design.md"))
        val namespacedToolNames = listOf(
            "market.get_ticker",
            "market.get_candles",
            "market.get_orderbook",
            "market.get_trades",
            "market.get_trade_rules",
            "market.calc_indicator",
            "market.get_microstructure_summary",
            "market.build_context_bundle",
            "account.get_balance",
            "account.get_positions",
            "account.get_open_orders",
            "account.get_account_status",
            "risk.get_safety_status",
            "risk.preview_order",
            "risk.preview_protection_update",
            "risk.preview_close",
            "risk.calculate_position_size",
            "risk.get_trailing_floor",
            "decision.submit_decision",
            "decision.submit_falsification",
            "trade.place_order",
            "trade.close_position",
            "trade.update_protection",
            "trade.cancel_order",
            "ops.request_override",
            "ops.get_runtime_limits",
            "ops.override",
        )

        val remainingNamespacedToolNames = namespacedToolNames
            .filter { namespacedToolName -> content.contains(namespacedToolName) }

        assertTrue(
            remainingNamespacedToolNames.isEmpty(),
            "docs/design.md must use flat MCP tool names: $remainingNamespacedToolNames",
        )
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
