package me.matsumo.fukurou.trading.invoker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.matsumo.fukurou.trading.audit.ManifestPersistencePolicy

/** runner、bootstrap、MCP registration が共有する実JSON schema contract catalog。 */
object McpToolContractCatalog {
    val proposerTools = setOf(
        "get_trade_intent", "get_ticker", "get_candles", "get_orderbook", "get_trades", "get_symbol_rules",
        "calc_indicator", "get_balance", "get_positions", "get_open_orders", "get_account_status",
        "knowledge_get_recent_lessons", "knowledge_search_similar_setups", "submit_decision",
    )
    val falsifierTools = setOf(
        "get_trade_intent", "preview_order", "get_ticker", "get_candles", "get_orderbook", "get_trades",
        "get_symbol_rules", "calc_indicator", "get_balance", "get_positions", "get_open_orders",
        "get_account_status", "knowledge_get_recent_lessons", "knowledge_search_similar_setups",
        "submit_falsification",
    )
    val riskReductionTools = setOf(
        "get_balance", "get_positions", "get_open_orders", "get_account_status", "submit_decision",
    )
    val allTools = proposerTools + falsifierTools

    fun toolsFor(phase: LlmInvocationPhase): Set<String> = when (phase) {
        LlmInvocationPhase.PROPOSER -> proposerTools
        LlmInvocationPhase.FALSIFIER -> falsifierTools
        LlmInvocationPhase.RISK_REDUCTION_ONLY -> riskReductionTools
        LlmInvocationPhase.PRE_FILTER,
        LlmInvocationPhase.REFLECTION,
        LlmInvocationPhase.EVALUATION_REPORT,
        -> emptySet()
    }

    fun requireCanonical(phase: LlmInvocationPhase, tools: Collection<String>) {
        val shortNames = tools.map { it.substringAfterLast("__") }
        require(shortNames.size == shortNames.distinct().size) { "MCP tool allowlist contains duplicates." }
        require(shortNames.all(SCHEMA_JSON_BY_TOOL::containsKey)) {
            "MCP tool allowlist contains unknown tool or missing schema."
        }
        require(shortNames.toSet() == toolsFor(phase)) { "MCP tool allowlist is not canonical for phase." }
    }

    fun schema(toolName: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(SCHEMA_JSON_BY_TOOL[toolName]) { "MCP tool schema is not registered: $toolName" },
    ).jsonObject

    fun canonicalSchemaBundle(phase: LlmInvocationPhase): String = canonicalSchemaBundle(
        phase = phase,
        schemaJsonByTool = SCHEMA_JSON_BY_TOOL,
    )

    fun canonicalSchemaHash(phase: LlmInvocationPhase): String =
        ManifestPersistencePolicy.sha256(canonicalSchemaBundle(phase))

    internal fun canonicalSchemaBundle(phase: LlmInvocationPhase, schemaJsonByTool: Map<String, String>): String =
        toolsFor(phase).sorted().joinToString(prefix = "mcp-tool-catalog-v1\n", separator = "\n") { tool ->
            "$tool:${requireNotNull(schemaJsonByTool[tool])}"
        }

    internal fun schemaJsonByTool(): Map<String, String> = SCHEMA_JSON_BY_TOOL
}

private val SCHEMA_JSON_BY_TOOL = mapOf(
    "get_account_status" to """{"type":"object"}""",
    "get_balance" to """{"type":"object"}""",
    "get_open_orders" to """{"type":"object"}""",
    "get_positions" to """{"type":"object"}""",
    "get_ticker" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"}},"type":"object"}""",
    "get_orderbook" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"depth":{"type":"integer","description":"Number of bid and ask levels.","default":10,"minimum":1,"maximum":100}},"type":"object"}""",
    "get_trades" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"limit":{"type":"integer","description":"Number of recent trades.","default":50,"minimum":1,"maximum":100}},"type":"object"}""",
    "get_symbol_rules" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"}},"type":"object"}""",
    "get_candles" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"interval":{"type":"string","description":"Candle interval.","enum":["1min","5min","10min","15min","30min","1hour","4hour","8hour","12hour","1day","1week","1month"]},"limit":{"type":"integer","description":"Number of recent candles.","default":100,"minimum":1,"maximum":500}},"required":["interval"],"type":"object"}""",
    "calc_indicator" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"interval":{"type":"string","description":"Candle interval.","enum":["1min","5min","10min","15min","30min","1hour","4hour","8hour","12hour","1day","1week","1month"]},"indicator":{"type":"string","description":"Indicator name.","enum":["ATR","EMA","RSI","SMA","MACD","ATR_PERCENTILE","VWAP_SESSION","VOLUME_Z_SCORE"]},"params":{"type":"object","description":"Indicator params. Use period, lookback, fast_period, slow_period, signal_period, and limit as needed. DAY-based candle limits are capped by 7 stitched GMO business dates."}},"required":["interval","indicator"],"type":"object"}""",
    "get_trade_intent" to """{"properties":{"intent_id":{"type":"string","description":"Trade intent UUID."}},"required":["intent_id"],"type":"object"}""",
    "knowledge_get_recent_lessons" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"limit":{"type":"integer","description":"Maximum number of lessons to return.","default":5,"minimum":1,"maximum":10},"lookback_days":{"type":"integer","description":"Number of past days to inspect.","default":30,"minimum":1,"maximum":365}},"type":"object"}""",
    "knowledge_search_similar_setups" to """{"properties":{"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"setup_tags":{"type":"array","description":"Setup taxonomy tags to match.","items":{"type":"string"}},"regime":{"type":"string","description":"Short market regime label or description."},"signal_summary":{"type":"string","description":"Short signal summary for text matching."},"limit":{"type":"integer","description":"Maximum number of similar decisions to return.","default":3,"minimum":1,"maximum":5},"lookback_days":{"type":"integer","description":"Number of past days to inspect.","default":180,"minimum":1,"maximum":365}},"type":"object"}""",
    "preview_order" to """{"properties":{"intent_id":{"type":"string","description":"Trade intent UUID approved by submit_falsification."},"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"side":{"type":"string","description":"BUY entry only for BTC spot.","enum":["BUY"]},"type":{"type":"string","description":"MARKET, LIMIT, or STOP.","enum":["MARKET","LIMIT","STOP"]},"size_btc":{"type":"string","description":"BTC order size."},"price_jpy":{"type":"string","description":"LIMIT or STOP price. Omit for MARKET."},"trade_group_id":{"type":"string","description":"Optional trade group UUID when adding to an existing group."},"protective_stop_price_jpy":{"type":"string","description":"Required protective STOP price after entry fill."},"take_profit_price_jpy":{"type":"string","description":"Required virtual take-profit trigger price for SafetyFloor EV calculation."},"estimated_win_probability":{"type":"string","description":"Estimated win probability from 0 to 1. SafetyFloor calculates EV from this value."},"reason":{"type":"string","description":"Required audit reason."},"client_request_id":{"type":"string","description":"Optional caller-provided request ID."}},"required":["intent_id","side","type","size_btc","protective_stop_price_jpy","take_profit_price_jpy","estimated_win_probability","reason"],"type":"object"}""",
    "submit_falsification" to """{"properties":{"intent_id":{"type":"string","description":"Trade intent UUID."},"verdict":{"type":"string","description":"APPROVED or REJECTED.","enum":["APPROVED","REJECTED"]},"llm_provider":{"type":"string","description":"Falsifier provider name."},"reason_ja":{"type":"string","description":"Falsifier reason in Japanese."},"client_request_id":{"type":"string","description":"Optional caller-provided request ID."}},"required":["intent_id","verdict","reason_ja"],"type":"object"}""",
    "submit_decision" to """{"properties":{"action":{"type":"string","description":"ENTER, EXIT, REDUCE, ADD_LONG, ADJUST_PROTECTION, or NO_TRADE.","enum":["ENTER","EXIT","REDUCE","ADD_LONG","ADJUST_PROTECTION","NO_TRADE"]},"close_ratio":{"type":"string","description":"Position close ratio for REDUCE. Decimal string with 0 < close_ratio <= 1.00."},"setup_tags":{"type":"array","description":"Setup taxonomy tags. Required for ENTER and ADD_LONG.","items":{"type":"string"}},"estimated_win_probability":{"type":"string","description":"Estimated win probability from 0 to 1."},"expected_r_multiple":{"type":"string","description":"Required expected R for every action. Submit 0 when no setup or managed-plan residual R is unavailable; negative values are valid."},"round_trip_cost_r":{"type":"string","description":"Round-trip cost expressed in R."},"tool_evidence_ids":{"type":"array","description":"Tool call IDs used as decision evidence.","items":{"type":"string"}},"fact_check":{"type":"string","description":"Fact-check JSON string."},"self_review":{"type":"string","description":"Self-review JSON string."},"reason_ja":{"type":"string","description":"Decision reason in Japanese."},"missing_data_ja":{"type":"array","description":"Missing data list for NO_TRADE and calibration.","items":{"type":"string"}},"no_trade_conditions_ja":{"type":"array","description":"Conditions to wait for before trading.","items":{"type":"string"}},"symbol":{"type":"string","description":"Spot symbol. BTC only.","default":"BTC"},"side":{"type":"string","description":"BUY entry only for ENTER and ADD_LONG.","enum":["BUY"]},"type":{"type":"string","description":"MARKET, LIMIT, or STOP for ENTER and ADD_LONG.","enum":["MARKET","LIMIT","STOP"]},"size_btc":{"type":"string","description":"BTC intent size."},"price_jpy":{"type":"string","description":"LIMIT or STOP intent price. Omit for MARKET."},"protective_stop_price_jpy":{"type":"string","description":"Protective STOP price for ENTER and ADD_LONG."},"take_profit_price_jpy":{"type":"string","description":"Virtual take-profit price for ENTER and ADD_LONG."},"parent_trade_plan_id":{"type":"string","description":"Parent TradePlan UUID when revising."},"trade_plan_revision_count":{"type":"integer","description":"TradePlan revision count. ENTER starts at 0.","default":0},"trade_plan_thesis_ja":{"type":"string","description":"TradePlan thesis in Japanese."},"trade_plan_invalidation_conditions_ja":{"type":"array","description":"TradePlan invalidation conditions.","items":{"type":"string"}},"trade_plan_invalidation_predicates":{"type":"array","description":"Machine-evaluable invalidation predicates. Required for ENTER and ADD_LONG.","items":{"type":"object","properties":{"type":{"type":"string","enum":["LAST_PRICE_AT_OR_BELOW","LAST_PRICE_AT_OR_ABOVE","BEST_BID_AT_OR_BELOW","BEST_ASK_AT_OR_ABOVE","TIME_AT_OR_AFTER","MATERIAL_STATE_CHANGED"]},"threshold_jpy":{"type":"string"},"threshold_at":{"type":"string"}},"required":["type"]}},"trade_plan_target_price_jpy":{"type":"string","description":"TradePlan target price."},"trade_plan_time_stop_at":{"type":"string","description":"Optional ISO-8601 time stop."},"client_request_id":{"type":"string","description":"Optional caller-provided request ID."}},"required":["action","estimated_win_probability","expected_r_multiple","fact_check","self_review","reason_ja"],"type":"object"}""",
)
