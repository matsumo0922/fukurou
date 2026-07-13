package me.matsumo.fukurou.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.matsumo.fukurou.trading.activity.DecisionRunDecision
import me.matsumo.fukurou.trading.activity.DecisionRunFalsification
import me.matsumo.fukurou.trading.activity.DecisionRunIntent
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenial
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialPage
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialQuery
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyDenialReader
import me.matsumo.fukurou.trading.activity.DecisionRunSafetyViolation
import me.matsumo.fukurou.trading.audit.CommandEvent
import me.matsumo.fukurou.trading.audit.CommandEventLog
import me.matsumo.fukurou.trading.audit.CommandEventType
import me.matsumo.fukurou.trading.audit.DecisionRunContext
import me.matsumo.fukurou.trading.audit.InMemoryCommandEventLog
import me.matsumo.fukurou.trading.broker.AccountSnapshotWithUpdatedAt
import me.matsumo.fukurou.trading.broker.AccountStatusWithUpdatedAt
import me.matsumo.fukurou.trading.broker.Broker
import me.matsumo.fukurou.trading.broker.CancelOrderCommand
import me.matsumo.fukurou.trading.broker.ClosePositionCommand
import me.matsumo.fukurou.trading.broker.OpenOrdersWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PaperReconcileResult
import me.matsumo.fukurou.trading.broker.PaperTradeResult
import me.matsumo.fukurou.trading.broker.PlaceOrderCommand
import me.matsumo.fukurou.trading.broker.PositionsWithUpdatedAt
import me.matsumo.fukurou.trading.broker.PreviewOrderResult
import me.matsumo.fukurou.trading.broker.UpdateProtectionCommand
import me.matsumo.fukurou.trading.config.LlmRunnerConfig
import me.matsumo.fukurou.trading.config.RuntimeConfigCatalog
import me.matsumo.fukurou.trading.config.TradingBotConfig
import me.matsumo.fukurou.trading.daemon.LlmDaemonTriggerKind
import me.matsumo.fukurou.trading.decision.FalsificationVerdict
import me.matsumo.fukurou.trading.decision.InMemoryDecisionRepository
import me.matsumo.fukurou.trading.decision.identity.DecisionMaterialStateManifest
import me.matsumo.fukurou.trading.decision.identity.DecisionTriggerKind
import me.matsumo.fukurou.trading.decision.identity.MaterialFreshness
import me.matsumo.fukurou.trading.domain.AccountSnapshot
import me.matsumo.fukurou.trading.domain.AccountStatus
import me.matsumo.fukurou.trading.domain.Candle
import me.matsumo.fukurou.trading.domain.CandleInterval
import me.matsumo.fukurou.trading.domain.Order
import me.matsumo.fukurou.trading.domain.Orderbook
import me.matsumo.fukurou.trading.domain.OrderbookLevel
import me.matsumo.fukurou.trading.domain.Position
import me.matsumo.fukurou.trading.domain.ProtectionStatus
import me.matsumo.fukurou.trading.domain.RecentTrade
import me.matsumo.fukurou.trading.domain.SymbolRules
import me.matsumo.fukurou.trading.domain.Ticker
import me.matsumo.fukurou.trading.domain.TradeSide
import me.matsumo.fukurou.trading.domain.TradingMode
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.evaluation.LLM_RUN_STATUS_FAILED
import me.matsumo.fukurou.trading.evaluation.LlmRunFinish
import me.matsumo.fukurou.trading.evaluation.LlmRunRepository
import me.matsumo.fukurou.trading.evaluation.LlmRunStart
import me.matsumo.fukurou.trading.exchange.gmo.DeferredGmoPublicRequestAuditSink
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientConfig
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientRole
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicMarketDataSource
import me.matsumo.fukurou.trading.exchange.gmo.GmoPublicRequestCorrelation
import me.matsumo.fukurou.trading.exchange.gmo.GmoRetryConfig
import me.matsumo.fukurou.trading.invoker.LlmInvocationPhase
import me.matsumo.fukurou.trading.invoker.MCP_MANIFEST_VERSION
import me.matsumo.fukurou.trading.invoker.McpLaunchManifest
import me.matsumo.fukurou.trading.knowledge.KnowledgeService
import me.matsumo.fukurou.trading.market.GmoRequestAuditException
import me.matsumo.fukurou.trading.market.MarketDataSource
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.TickSnapshot
import me.matsumo.fukurou.trading.runner.CANONICAL_FALSIFIER_MCP_TOOL_NAMES
import me.matsumo.fukurou.trading.runner.CANONICAL_PROPOSER_MCP_TOOL_NAMES
import me.matsumo.fukurou.trading.runner.DEFAULT_RUNNER_MCP_SERVER_NAME
import me.matsumo.fukurou.trading.runner.SecretRedactor
import me.matsumo.fukurou.trading.runner.defaultFalsifierAllowedTools
import me.matsumo.fukurou.trading.runner.defaultProposerAllowedTools
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import me.matsumo.fukurou.trading.runtime.TradingRuntimeFactory
import me.matsumo.fukurou.trading.safety.SafetyFloorRule
import me.matsumo.fukurou.trading.safety.SafetyViolation
import me.matsumo.fukurou.trading.tool.GuardedToolCall
import me.matsumo.fukurou.trading.tool.ToolCallGuard
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.Container
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * MCP server の最小 contract を検証するテスト。
 */
class FukurouMcpServerTest {

    @Test
    fun constructor_acceptsInjectedMarketDataSource() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        )

        assertNotNull(server)
    }

    @Test
    fun createServer_exposesGmoCoinAndFukurouToolsOnSingleServer() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()

        assertEquals(
            setOf(
                "get_ticker",
                "get_candles",
                "get_orderbook",
                "get_trades",
                "get_symbol_rules",
                "calc_indicator",
                "get_balance",
                "get_positions",
                "get_open_orders",
                "get_account_status",
                "get_trade_intent",
                "knowledge_get_recent_lessons",
                "knowledge_search_similar_setups",
                "submit_decision",
                "submit_falsification",
                "preview_order",
                "place_order",
                "close_position",
                "update_protection",
                "cancel_order",
                "reject_dummy_trade",
                "simulate_tool_timeout",
            ),
            server.tools.keys,
        )
    }

    @Test
    fun createServer_registeredToolNamesMatchClaudeAllowedToolPattern() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val claudeAllowedToolNamePattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

        val invalidToolNames = server.tools.keys
            .filterNot { toolName -> toolName.matches(claudeAllowedToolNamePattern) }

        assertTrue(
            invalidToolNames.isEmpty(),
            "MCP tool names must match Claude CLI allowedTools pattern: $invalidToolNames",
        )
    }

    @Test
    fun createServer_containsDefaultRunnerAllowlistTools() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val defaultAllowedToolNames = (
            defaultProposerAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME) +
                defaultFalsifierAllowedTools(DEFAULT_RUNNER_MCP_SERVER_NAME)
            )
            .map { toolName -> toolName.substringAfterLast("__") }
            .toSet()

        val missingToolNames = defaultAllowedToolNames - server.tools.keys

        assertTrue(
            missingToolNames.isEmpty(),
            "Default runner allowlists must reference registered MCP tools: $missingToolNames",
        )
    }

    @Test
    fun getBalanceTool_returnsFreshnessMetadataFromPaperAccountUpdatedAt() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_balance")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().toString(),
            stalenessMs = 0L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getPositionsTool_returnsFreshnessMetadataFromPaperAccountUpdatedAt() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_positions")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().toString(),
            stalenessMs = 0L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getOpenOrdersTool_returnsFreshnessMetadataFromPaperAccountUpdatedAt() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_open_orders")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().toString(),
            stalenessMs = 0L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getAccountStatusTool_returnsFreshnessMetadataFromPaperAccountUpdatedAt() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_account_status")
        val structuredContent = assertNotNull(result.structuredContent)

        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = fixedInstant().toString(),
            stalenessMs = 0L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getBalanceTool_usesSingleAccountSnapshotForBodyAndFreshnessTimestamp() = runBlocking {
        val sourceTimestamp = fixedInstant().minusSeconds(3)
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock()).copy(
            broker = SnapshotOnlyBroker(
                balance = AccountSnapshotWithUpdatedAt(
                    accountSnapshot = accountSnapshot(totalEquityJpy = "123456.00000000"),
                    updatedAt = sourceTimestamp,
                ),
                accountStatus = AccountStatusWithUpdatedAt(
                    accountStatus = accountStatus(currentEquityJpy = "123456.00000000"),
                    updatedAt = sourceTimestamp,
                ),
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_balance")
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals("123456.00000000", structuredContent.getValue("totalEquityJpy").jsonPrimitive.contentOrNull)
        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = sourceTimestamp.toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getPositionsTool_usesSingleLedgerSnapshotForBodyAndFreshnessTimestamp() = runBlocking {
        val sourceTimestamp = fixedInstant().minusSeconds(3)
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock()).copy(
            broker = SnapshotOnlyBroker(
                balance = AccountSnapshotWithUpdatedAt(
                    accountSnapshot = accountSnapshot(),
                    updatedAt = fixedInstant(),
                ),
                accountStatus = AccountStatusWithUpdatedAt(
                    accountStatus = accountStatus(),
                    updatedAt = fixedInstant(),
                ),
                positions = PositionsWithUpdatedAt(
                    positions = emptyList(),
                    updatedAt = sourceTimestamp,
                ),
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_positions")
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals(0L, structuredContent.getValue("count").jsonPrimitive.longOrNull)
        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = sourceTimestamp.toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getOpenOrdersTool_usesSingleLedgerSnapshotForBodyAndFreshnessTimestamp() = runBlocking {
        val sourceTimestamp = fixedInstant().minusSeconds(3)
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock()).copy(
            broker = SnapshotOnlyBroker(
                balance = AccountSnapshotWithUpdatedAt(
                    accountSnapshot = accountSnapshot(),
                    updatedAt = fixedInstant(),
                ),
                accountStatus = AccountStatusWithUpdatedAt(
                    accountStatus = accountStatus(),
                    updatedAt = fixedInstant(),
                ),
                openOrders = OpenOrdersWithUpdatedAt(
                    openOrders = emptyList(),
                    updatedAt = sourceTimestamp,
                ),
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_open_orders")
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals(0L, structuredContent.getValue("count").jsonPrimitive.longOrNull)
        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = sourceTimestamp.toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun getAccountStatusTool_usesSingleAccountSnapshotForBodyAndFreshnessTimestamp() = runBlocking {
        val sourceTimestamp = fixedInstant().minusSeconds(3)
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock()).copy(
            broker = SnapshotOnlyBroker(
                balance = AccountSnapshotWithUpdatedAt(
                    accountSnapshot = accountSnapshot(totalEquityJpy = "654321.00000000"),
                    updatedAt = sourceTimestamp,
                ),
                accountStatus = AccountStatusWithUpdatedAt(
                    accountStatus = accountStatus(currentEquityJpy = "654321.00000000"),
                    updatedAt = sourceTimestamp,
                ),
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(server, "get_account_status")
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals("654321.00000000", structuredContent.getValue("currentEquityJpy").jsonPrimitive.contentOrNull)
        assertFreshness(
            structuredContent = structuredContent,
            fetchedAt = fixedInstant().toString(),
            sourceTimestamp = sourceTimestamp.toString(),
            stalenessMs = 3_000L,
            staleAfterMs = 10_000L,
            stale = false,
            source = "PAPER_LEDGER",
        )
    }

    @Test
    fun tradingRuntimeFactory_failsClosedWhenDatabaseEnvironmentIsMissing() {
        assertFailsWith<IllegalArgumentException> {
            TradingRuntimeFactory.fromEnvironment(environment = emptyMap())
        }
    }

    @Test
    fun updateProtectionTool_allowsNullTakeProfitClearInSchema() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val tool = requireNotNull(server.tools["update_protection"]?.tool)
        val takeProfitSchema = requireNotNull(tool.inputSchema.properties?.get("new_take_profit_price_jpy"))
            .jsonObject
        val typeNames = takeProfitSchema.getValue("type")
            .jsonArray
            .map { typeElement -> typeElement.jsonPrimitive.contentOrNull }

        assertEquals(listOf("string", "null"), typeNames)
    }

    @Test
    fun submitDecisionTool_requiresExpectedRMultipleInSchema() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val tool = requireNotNull(server.tools["submit_decision"]?.tool)

        assertEquals(true, tool.inputSchema.required?.contains("expected_r_multiple"))
    }

    @Test
    fun submitDecisionTool_exposesPartialTradingActionsAndCloseRatioSchema() {
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = TradingRuntimeFactory.inMemory(),
        ).createServer()
        val tool = requireNotNull(server.tools["submit_decision"]?.tool)
        val actionEnum = requireNotNull(tool.inputSchema.properties?.get("action"))
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { action -> action.jsonPrimitive.contentOrNull }
        val closeRatioSchema = requireNotNull(tool.inputSchema.properties?.get("close_ratio"))
            .jsonObject

        assertTrue(actionEnum.contains("REDUCE"))
        assertTrue(actionEnum.contains("ADD_LONG"))
        assertEquals("string", closeRatioSchema.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun submitDecisionTool_recordsNoTradeInMemoryRuntime() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = noTradeDecisionArguments(),
            ),
        )

        val result = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository
        val decisions = repository.snapshots.decisions()

        assertTrue(result.isError != true)
        assertEquals("NO_TRADE", structuredContent.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals(1, decisions.size)
        assertEquals("材料不足のため見送ります。", decisions.single().submission.reasonJa)
    }

    @Test
    fun submitDecisionTool_rejectsMissingExpectedRMultiple() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = noTradeDecisionArguments(expectedRMultiple = null),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertEquals(true, result.isError)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals(
            "expected_r_multiple is required.",
            structuredContent.getValue("message").jsonPrimitive.contentOrNull,
        )
        assertEquals(0, repository.snapshots.decisions().size)
    }

    @Test
    fun submitDecisionTool_rejectsReduceWithoutCloseRatio() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = reduceDecisionArguments(closeRatio = null),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError == true)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals(
            "REDUCE decision requires close_ratio.",
            structuredContent.getValue("message").jsonPrimitive.contentOrNull,
        )
        assertEquals(0, repository.snapshots.decisions().size)
    }

    @Test
    fun submitDecisionTool_rejectsCloseRatioForExit() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = exitDecisionArguments(closeRatio = "0.50"),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError == true)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals(
            "close_ratio is only supported for REDUCE decisions.",
            structuredContent.getValue("message").jsonPrimitive.contentOrNull,
        )
        assertEquals(0, repository.snapshots.decisions().size)
    }

    @Test
    fun submitDecisionTool_recordsReduceCloseRatio() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = reduceDecisionArguments(closeRatio = "0.50"),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository
        val submission = repository.snapshots.decisions().single().submission

        assertTrue(result.isError != true)
        assertEquals("REDUCE", structuredContent.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals("0.50", structuredContent.getValue("close_ratio").jsonPrimitive.contentOrNull)
        assertEquals("0.50", submission.closeRatio?.toPlainString())
    }

    @Test
    fun submitDecisionTool_acceptsNegativeExpectedRMultiple() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = noTradeDecisionArguments(expectedRMultiple = "-0.25"),
        )
        val repository = runtime.decisionRepository as InMemoryDecisionRepository
        val noTradeExpectedRMultiple = repository.snapshots.decisions().single().submission.expectedRMultiple

        assertTrue(result.isError != true)
        assertEquals("-0.25", noTradeExpectedRMultiple?.toPlainString())
    }

    @Test
    fun submitFalsificationTool_recordsVerdictForEnterIntentInMemoryRuntime() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val decisionRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = enterDecisionArguments(),
            ),
        )

        val decisionResult = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, decisionRequest)
        val intentId = assertNotNull(decisionResult.structuredContent)
            .getValue("intent_id")
            .jsonPrimitive
            .contentOrNull
        val falsificationRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_falsification",
                arguments = buildJsonObject {
                    put("intent_id", intentId)
                    put("verdict", FalsificationVerdict.APPROVED.name)
                    put("llm_provider", "codex")
                    put("reason_ja", "反証しても拒否理由が不足しています。")
                },
            ),
        )

        val falsificationResult = server.tools.getValue("submit_falsification")
            .handler
            .invoke(TestClientConnection, falsificationRequest)
        val duplicateResult = server.tools.getValue("submit_falsification")
            .handler
            .invoke(TestClientConnection, falsificationRequest)
        val structuredContent = assertNotNull(falsificationResult.structuredContent)
        val duplicateContent = assertNotNull(duplicateResult.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(falsificationResult.isError != true)
        assertEquals(FalsificationVerdict.APPROVED.name, structuredContent.getValue("verdict").jsonPrimitive.contentOrNull)
        assertEquals(1, repository.snapshots.tradeIntents().size)
        assertEquals(1, repository.snapshots.falsifications().size)
        assertEquals(true, duplicateResult.isError)
        assertEquals("invalid_request", duplicateContent.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun submitDecisionTool_recordsAddLongIntentWithTradePlanRevision() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val enterResult = callTool(server, "submit_decision", enterDecisionArguments())
        val parentTradePlanId = assertNotNull(enterResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
            .let { value -> assertNotNull(value) }

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = addLongDecisionArguments(parentTradePlanId = parentTradePlanId),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError != true)
        assertEquals("ADD_LONG", structuredContent.getValue("action").jsonPrimitive.contentOrNull)
        assertNotNull(structuredContent.getValue("intent_id").jsonPrimitive.contentOrNull)
        assertEquals("1", structuredContent.getValue("revision_count").jsonPrimitive.contentOrNull)
        assertEquals(2, repository.snapshots.tradeIntents().size)
        assertEquals(2, repository.snapshots.tradePlans().size)
    }

    @Test
    fun submitDecisionTool_rejectsAddLongWithoutSetupTags() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val enterResult = callTool(server, "submit_decision", enterDecisionArguments())
        val parentTradePlanId = assertNotNull(enterResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
            .let { value -> assertNotNull(value) }

        val result = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = addLongDecisionArguments(
                parentTradePlanId = parentTradePlanId,
                setupTags = stringArray(),
            ),
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError == true)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals(
            "ADD_LONG decision requires setup_tags.",
            structuredContent.getValue("message").jsonPrimitive.contentOrNull,
        )
        assertEquals(1, repository.snapshots.tradeIntents().size)
    }

    @Test
    fun getTradeIntentTool_returnsIntentAndTradePlanForFalsifierReview() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val decisionRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = enterDecisionArguments(),
            ),
        )

        val decisionResult = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, decisionRequest)
        val intentId = assertNotNull(decisionResult.structuredContent)
            .getValue("intent_id")
            .jsonPrimitive
            .contentOrNull
        val reviewRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_trade_intent",
                arguments = buildJsonObject {
                    put("intent_id", intentId)
                },
            ),
        )

        val reviewResult = server.tools.getValue("get_trade_intent").handler.invoke(TestClientConnection, reviewRequest)
        val structuredContent = assertNotNull(reviewResult.structuredContent)
        val tradePlan = structuredContent.getValue("trade_plan").jsonObject

        assertTrue(reviewResult.isError != true)
        assertEquals(intentId, structuredContent.getValue("intent_id").jsonPrimitive.contentOrNull)
        assertEquals("0.0050", structuredContent.getValue("size_btc").jsonPrimitive.contentOrNull)
        assertEquals("1時間足の上昇継続に乗る。", tradePlan.getValue("thesis_ja").jsonPrimitive.contentOrNull)
    }

    @Test
    fun knowledgeRecentLessonsTool_returnsBoundedReadOnlySummaries() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val longInvocationId = "recent-run-" + "x".repeat(140)
        insertFailedLlmRun(runtime.llmRunRepository, longInvocationId)
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        callTool(
            server = server,
            toolName = "submit_decision",
            arguments = noTradeDecisionArguments(invocationId = longInvocationId),
        )

        val result = callTool(
            server = server,
            toolName = "knowledge_get_recent_lessons",
            arguments = buildJsonObject {
                put("limit", 2)
            },
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val lessons = structuredContent.getValue("lessons").jsonArray
        val lesson = lessons.single().jsonObject
        val runSummary = structuredContent.getValue("run_summaries").jsonArray.single().jsonObject
        val failurePatterns = structuredContent.getValue("failure_patterns").jsonArray
        val failureSources = failurePatterns.map { pattern ->
            pattern.jsonObject.getValue("source").jsonPrimitive.contentOrNull
        }
        val runFailurePattern = failurePatterns
            .first()
            .jsonObject
        val runFailureSourceId = runFailurePattern
            .getValue("source_id")
            .jsonPrimitive
            .contentOrNull
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError != true)
        assertEquals("knowledge.recent_lessons.v2", structuredContent.getValue("schema_version").jsonPrimitive.contentOrNull)
        assertEquals("postgres", structuredContent.getValue("source").jsonPrimitive.contentOrNull)
        assertEquals(1L, structuredContent.getValue("item_count").jsonPrimitive.longOrNull)
        assertEquals("NO_TRADE", lesson.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals("材料不足のため見送ります。", lesson.getValue("reason_ja").jsonPrimitive.contentOrNull)
        assertEquals(
            true,
            lesson.getValue("invocation_id").jsonPrimitive.contentOrNull?.contains("[TRUNCATED]"),
        )
        assertEquals(LLM_RUN_STATUS_FAILED, runSummary.getValue("status").jsonPrimitive.contentOrNull)
        assertEquals(
            true,
            runSummary.getValue("invocation_id").jsonPrimitive.contentOrNull?.contains("[TRUNCATED]"),
        )
        assertEquals(listOf("llm_run", "decision"), failureSources)
        assertEquals(true, runFailureSourceId?.contains("[TRUNCATED]"))
        assertEquals(1, repository.snapshots.decisions().size)
        assertTrue(!structuredContent.toString().contains("fact_check"))
        assertTrue(!structuredContent.toString().contains("tool_evidence_ids"))
        assertEquals(0L, structuredContent.getValue("safety_floor_denial_item_count").jsonPrimitive.longOrNull)
        assertEquals(false, structuredContent.getValue("safety_floor_denials_truncated").jsonPrimitive.booleanOrNull)
        assertTrue(structuredContent.getValue("safety_floor_denials").jsonArray.isEmpty())
    }

    @Test
    fun knowledgeRecentLessonsTool_separatesLayers() = runBlocking {
        val clock = fixedClock()
        val runtime = TradingRuntimeFactory.inMemory(clock = clock)
        val reader = object : DecisionRunSafetyDenialReader {
            override suspend fun readSafetyDenials(
                query: DecisionRunSafetyDenialQuery,
            ): Result<DecisionRunSafetyDenialPage> {
                return Result.success(
                    DecisionRunSafetyDenialPage(
                        denials = listOf(
                            DecisionRunSafetyDenial(
                                invocationId = "denied-run",
                                deniedAt = Instant.now(clock),
                                finalReason = "preview_order_rejected",
                                decision = null,
                                intent = null,
                                falsification = null,
                                safetyViolation = DecisionRunSafetyViolation(
                                    rule = "EXPECTED_VALUE_GATE",
                                    measuredValue = "0.03357778",
                                    limitValue = "0.10",
                                    messageJa = "EV が不足しています。",
                                    createdAt = Instant.now(clock),
                                ),
                            ),
                        ),
                        truncated = false,
                    ),
                )
            }
        }
        val knowledgeService = KnowledgeService(
            decisionRepository = runtime.decisionRepository,
            llmRunRepository = runtime.llmRunRepository,
            evaluationRepository = runtime.evaluationRepository,
            safetyDenialReader = reader,
            clock = clock,
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = clock,
            tradingRuntime = runtime,
            knowledgeService = knowledgeService,
        ).createServer()

        val result = callTool(server, "knowledge_get_recent_lessons", buildJsonObject {})
        val structuredContent = assertNotNull(result.structuredContent)
        val denial = structuredContent.getValue("safety_floor_denials").jsonArray.single().jsonObject

        assertEquals("knowledge.recent_lessons.v2", structuredContent.getValue("schema_version").jsonPrimitive.contentOrNull)
        assertEquals("DENIED", denial.getValue("outcome").jsonPrimitive.contentOrNull)
        assertEquals("SafetyFloor", denial.getValue("deny_layer").jsonPrimitive.contentOrNull)
        assertEquals("preview_order_rejected", denial.getValue("final_reason").jsonPrimitive.contentOrNull)
        assertEquals("EXPECTED_VALUE_GATE", denial.getValue("machine_outcome").jsonObject.getValue("rule").jsonPrimitive.contentOrNull)
        assertEquals("0.03357778", denial.getValue("machine_outcome").jsonObject.getValue("measured_value").jsonPrimitive.contentOrNull)
        assertEquals(null, denial.getValue("prior_proposal").jsonPrimitive.contentOrNull)
        assertEquals(null, denial.getValue("falsifier").jsonPrimitive.contentOrNull)
    }

    @Test
    fun knowledgeRecentLessonsTool_capsSafetyDenialsAsNewestFirstPrefix() = runBlocking {
        val clock = fixedClock()
        val runtime = TradingRuntimeFactory.inMemory(clock = clock)
        val rawSecret = "raw-secret-value"
        val reader = object : DecisionRunSafetyDenialReader {
            override suspend fun readSafetyDenials(
                query: DecisionRunSafetyDenialQuery,
            ): Result<DecisionRunSafetyDenialPage> {
                return Result.success(
                    DecisionRunSafetyDenialPage(
                        denials = (1..5).map { index ->
                            largeSafetyDenial(index, rawSecret, Instant.now(clock).minusSeconds(index.toLong()))
                        },
                        truncated = false,
                    ),
                )
            }
        }
        val knowledgeService = KnowledgeService(
            decisionRepository = runtime.decisionRepository,
            llmRunRepository = runtime.llmRunRepository,
            evaluationRepository = runtime.evaluationRepository,
            safetyDenialReader = reader,
            clock = clock,
            redactor = SecretRedactor.fromEnvironment(mapOf("API_SECRET" to rawSecret)),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = clock,
            tradingRuntime = runtime,
            knowledgeService = knowledgeService,
        ).createServer()

        val result = callTool(server, "knowledge_get_recent_lessons", buildJsonObject {})
        val structuredContent = assertNotNull(result.structuredContent)
        val denials = structuredContent.getValue("safety_floor_denials").jsonArray
        val first = denials.first().jsonObject

        assertTrue(denials.size in 1..4)
        assertEquals(
            denials.size.toLong(),
            structuredContent.getValue("safety_floor_denial_item_count").jsonPrimitive.longOrNull,
        )
        assertEquals(
            true,
            structuredContent.getValue("safety_floor_denials_truncated").jsonPrimitive.booleanOrNull,
        )
        assertEquals(
            (1..denials.size).map { index -> "denial-$index" },
            denials.map { denial ->
                denial.jsonObject.getValue("invocation_id").jsonPrimitive.contentOrNull
            },
        )
        assertTrue(denials.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        assertNotNull(first.getValue("prior_proposal").jsonObject)
        assertNotNull(first.getValue("falsifier").jsonObject)
        assertNotNull(first.getValue("machine_outcome").jsonObject)
        assertTrue(!structuredContent.toString().contains(rawSecret))
    }

    @Test
    fun knowledgeRecentLessonsTool_readsSafetyDenialThroughPostgresRuntime() = runBlocking {
        if (!DockerClientFactory.instance().isDockerAvailable) return@runBlocking

        val container = PostgreSQLContainer("postgres:16-alpine")
        var runtime: me.matsumo.fukurou.trading.runtime.TradingRuntime? = null
        container.start()

        try {
            val clock = fixedClock()
            val database = ExposedDatabase.connect(
                url = container.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = container.username,
                password = container.password,
            )
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            runtime = TradingRuntimeFactory.postgres(
                config = TradingDatabaseConfig(container.jdbcUrl, container.username, container.password),
                clock = clock,
            )
            val invocationId = "postgres-denial-run"
            val startedAt = Instant.now(clock).minusSeconds(10)

            runtime.llmRunRepository.insertRunning(
                LlmRunStart(
                    invocationId = invocationId,
                    mode = TradingMode.PAPER,
                    symbol = TradingSymbol.BTC,
                    triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
                    startedAt = startedAt,
                ),
            ).getOrThrow()
            runtime.llmRunRepository.finish(
                LlmRunFinish(
                    invocationId = invocationId,
                    mode = TradingMode.PAPER,
                    symbol = TradingSymbol.BTC,
                    triggerKind = LlmDaemonTriggerKind.ECONOMIC_EVENT,
                    status = "SUCCEEDED",
                    startedAt = startedAt,
                    finishedAt = Instant.now(clock),
                    errorMessage = null,
                ),
            ).getOrThrow()
            runtime.safetyViolationRepository.append(
                SafetyViolation(
                    rule = SafetyFloorRule.EXPECTED_VALUE_GATE,
                    messageJa = "EV が不足しています。",
                    measuredValue = "0.03357778",
                    limitValue = "0.10",
                    commandName = "preview_order",
                    commandId = UUID.randomUUID(),
                    orderId = null,
                    decisionRunId = invocationId,
                    toolCallId = "tool-postgres-denial",
                    clientRequestId = "request-postgres-denial",
                    hardHaltRequired = false,
                    payloadJson = "{\"credential\":\"must-not-leak\"}",
                    createdAt = Instant.now(clock),
                ),
            ).getOrThrow()
            runtime.commandEventLog.append(
                CommandEvent(
                    decisionRunContext = DecisionRunContext(
                        decisionRunId = invocationId,
                        llmProvider = "claude",
                        promptHash = "prompt-hash",
                        systemPromptVersion = "system-prompt-v1.13",
                        marketSnapshotId = "snapshot-1",
                    ),
                    toolName = "runner",
                    toolCallId = null,
                    clientRequestId = null,
                    eventType = CommandEventType.NO_TRADE_EXIT,
                    payload = "{\"reason\":\"preview_order_rejected\"}",
                    occurredAt = Instant.now(clock),
                ),
            ).getOrThrow()
            val server = FukurouMcpServer(
                clientRole = GmoPublicClientRole.UNSPECIFIED,
                marketDataSource = FakeMarketDataSource,
                clock = clock,
                tradingRuntime = runtime,
            ).createServer()

            val result = callTool(server, "knowledge_get_recent_lessons", buildJsonObject {})
            val denial = assertNotNull(result.structuredContent)
                .getValue("safety_floor_denials")
                .jsonArray
                .single()
                .jsonObject

            assertEquals("postgres-denial-run", denial.getValue("invocation_id").jsonPrimitive.contentOrNull)
            assertEquals("EXPECTED_VALUE_GATE", denial.getValue("machine_outcome").jsonObject.getValue("rule").jsonPrimitive.contentOrNull)
            assertEquals("preview_order_rejected", denial.getValue("final_reason").jsonPrimitive.contentOrNull)
        } finally {
            runtime?.close()
            container.stop()
        }
    }

    @Test
    fun knowledgeSimilarSetupsTool_returnsMatchedDecisionOutcome() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        insertFailedLlmRun(runtime.llmRunRepository, "similar-run")
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        val decisionResult = callTool(
            server = server,
            toolName = "submit_decision",
            arguments = enterDecisionArguments(invocationId = "similar-run"),
        )
        val intentId = assertNotNull(decisionResult.structuredContent)
            .getValue("intent_id")
            .jsonPrimitive
            .contentOrNull
        callTool(
            server = server,
            toolName = "submit_falsification",
            arguments = buildJsonObject {
                put("intent_id", intentId)
                put("verdict", FalsificationVerdict.REJECTED.name)
                put("llm_provider", "codex")
                put("reason_ja", "過去の同種 setup はブレイク失敗が多いため拒否します。")
            },
        )

        val result = callTool(
            server = server,
            toolName = "knowledge_search_similar_setups",
            arguments = buildJsonObject {
                put("setup_tags", stringArray("breakout"))
                put("signal_summary", "1時間足の上昇継続")
                put("limit", 3)
            },
        )
        val structuredContent = assertNotNull(result.structuredContent)
        val hit = structuredContent.getValue("hits").jsonArray.single().jsonObject
        val lesson = hit.getValue("lesson").jsonObject
        val outcome = hit.getValue("outcome").jsonObject

        assertTrue(result.isError != true)
        assertEquals("ENTER", lesson.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals(true, outcome.getValue("has_trade_intent").jsonPrimitive.booleanOrNull)
        assertEquals(FalsificationVerdict.REJECTED.name, outcome.getValue("falsification_verdict").jsonPrimitive.contentOrNull)
        assertEquals(LLM_RUN_STATUS_FAILED, outcome.getValue("run_status").jsonPrimitive.contentOrNull)
        assertEquals(true, hit.getValue("score").jsonPrimitive.longOrNull?.let { score -> score > 0L })
        assertTrue(hit.getValue("matched_terms").jsonArray.isNotEmpty())
    }

    @Test
    fun knowledgeSimilarSetupsTool_doesNotSubstringMatchShortSearchTokens() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(clock = fixedClock())
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()

        callTool(
            server = server,
            toolName = "submit_decision",
            arguments = noTradeDecisionArguments(),
        )

        val result = callTool(
            server = server,
            toolName = "knowledge_search_similar_setups",
            arguments = buildJsonObject {
                put("signal_summary", "料不")
                put("limit", 3)
            },
        )
        val structuredContent = assertNotNull(result.structuredContent)

        assertTrue(result.isError != true)
        assertTrue(structuredContent.getValue("hits").jsonArray.isEmpty())
    }

    @Test
    fun previewOrderTool_acceptsApprovedIntentWithoutPaperExecutionSideEffects() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = PreviewMarketDataSource,
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = PreviewMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()
        val intentId = submitApprovedEnterIntent(server)

        val result = callTool(server, "preview_order", placeOrderArguments(intentId))
        val structuredContent = assertNotNull(result.structuredContent)
        val normalizedOrderContent = structuredContent.getValue("normalized_order_content").jsonObject
        val riskDetails = structuredContent.getValue("risk_details").jsonObject
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError != true)
        assertEquals(true, structuredContent.getValue("accepted").jsonPrimitive.booleanOrNull)
        assertEquals(64, structuredContent.getValue("preview_hash").jsonPrimitive.contentOrNull?.length)
        assertEquals(intentId, normalizedOrderContent.getValue("intent_id").jsonPrimitive.contentOrNull)
        assertEquals("0.0050", normalizedOrderContent.getValue("size_btc").jsonPrimitive.contentOrNull)
        assertNotNull(riskDetails.getValue("estimated_entry_price_jpy").jsonPrimitive.contentOrNull)
        assertEquals(0, runtime.broker.getPositions().getOrThrow().size)
        assertEquals(0, runtime.broker.getOpenOrders().getOrThrow().size)
        assertEquals(0, repository.snapshots.intentConsumptions().size)
    }

    @Test
    fun previewOrderTool_propagatesCorrelationAndAuditFailureThroughProductionHandler() = runBlocking {
        listOf(LlmInvocationPhase.PROPOSER, LlmInvocationPhase.FALSIFIER).forEach { phase ->
            val clock = fixedClock()
            val bootstrap = decodeBootstrap(bootstrapManifest(phase, clock), clock)
            val marketDataSource = CorrelationAuditFailingMarketDataSource()
            val runtime = TradingRuntimeFactory.inMemory(
                clock = clock,
                marketDataSource = marketDataSource,
            )
            val server = FukurouMcpServer(
                marketDataSource = marketDataSource,
                clock = clock,
                tradingRuntime = runtime,
                decisionRunContext = bootstrap.decisionRunContext,
                clientRole = bootstrap.phase.toGmoPublicClientRole(),
            ).createServer()
            val intentId = submitApprovedEnterIntent(server)

            val result = callTool(server, "preview_order", placeOrderArguments(intentId))
            val structuredContent = assertNotNull(result.structuredContent)
            val correlation = assertNotNull(marketDataSource.correlation)

            assertEquals(true, result.isError)
            assertEquals("audit_failed_after_execution", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
            assertEquals("true", structuredContent.getValue("executed").jsonPrimitive.contentOrNull)
            assertEquals(bootstrap.decisionRunContext.decisionRunId, correlation.decisionRunContext.decisionRunId)
            assertTrue(correlation.toolCallId?.isNotBlank() == true)
            assertEquals(phase.toGmoPublicClientRole(), correlation.clientRole)
            assertEquals(0, runtime.broker.getOpenOrders().getOrThrow().size)
            assertEquals(0, runtime.broker.getPositions().getOrThrow().size)
        }
    }

    @Test
    fun gmoPublicClientRole_mapsEveryLlmInvocationPhaseExplicitly() {
        assertEquals(GmoPublicClientRole.UNSPECIFIED, LlmInvocationPhase.PRE_FILTER.toGmoPublicClientRole())
        assertEquals(GmoPublicClientRole.PROPOSER, LlmInvocationPhase.PROPOSER.toGmoPublicClientRole())
        assertEquals(GmoPublicClientRole.FALSIFIER, LlmInvocationPhase.FALSIFIER.toGmoPublicClientRole())
        assertEquals(GmoPublicClientRole.UNSPECIFIED, LlmInvocationPhase.REFLECTION.toGmoPublicClientRole())
        assertEquals(GmoPublicClientRole.UNSPECIFIED, LlmInvocationPhase.EVALUATION_REPORT.toGmoPublicClientRole())
    }

    @Test
    fun previewOrderTool_rejectedPathMatchesPlaceOrderFirstSafetyViolation() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = PreviewMarketDataSource,
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = PreviewMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()
        val intentId = submitApprovedEnterIntent(server)
        val mismatchedArguments = placeOrderArguments(
            intentId = intentId,
            sizeBtc = "0.0100",
        )

        val previewResult = callTool(server, "preview_order", mismatchedArguments)
        val placeOrderResult = callTool(server, "place_order", mismatchedArguments)
        val previewContent = assertNotNull(previewResult.structuredContent)
        val placeOrderContent = assertNotNull(placeOrderResult.structuredContent)
        val previewViolation = previewContent.getValue("safety_violation").jsonObject
        val placeOrderViolation = placeOrderContent.getValue("safety_violation").jsonObject

        assertTrue(previewResult.isError != true)
        assertTrue(placeOrderResult.isError != true)
        assertEquals(false, previewContent.getValue("accepted").jsonPrimitive.booleanOrNull)
        assertEquals(false, placeOrderContent.getValue("accepted").jsonPrimitive.booleanOrNull)
        assertEquals(
            placeOrderViolation.getValue("rule").jsonPrimitive.contentOrNull,
            previewViolation.getValue("rule").jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun closePositionTool_allowsRiskReducingCloseDuringHardHalt() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = PreviewMarketDataSource,
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = PreviewMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()
        val intentId = submitApprovedEnterIntent(server)
        val placeOrderResult = callTool(server, "place_order", placeOrderArguments(intentId))

        assertTrue(placeOrderResult.isError != true)

        val positionId = runtime.broker.getPositions().getOrThrow().single().positionId

        runtime.riskStateRepository.setHardHalt("test hard halt", fixedInstant()).getOrThrow()

        val closeResult = callTool(
            server = server,
            toolName = "close_position",
            arguments = closePositionArguments(
                positionId = positionId,
                closeRatio = "0.50",
            ),
        )
        val structuredContent = assertNotNull(closeResult.structuredContent)
        val remainingPosition = runtime.broker.getPositions().getOrThrow().single()
        val eventTypes = (runtime.commandEventLog as InMemoryCommandEventLog).events().map { event -> event.eventType }

        assertTrue(closeResult.isError != true)
        assertEquals(true, structuredContent.getValue("accepted").jsonPrimitive.booleanOrNull)
        assertEquals("0.002500000000", remainingPosition.sizeBtc)
        assertTrue(!eventTypes.contains(CommandEventType.TOOL_CALL_REJECTED_BY_HARD_HALT))
    }

    @Test
    fun submitFalsificationTool_rejectsMissingIntentId() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_falsification",
                arguments = buildJsonObject {
                    put("verdict", FalsificationVerdict.APPROVED.name)
                    put("llm_provider", "codex")
                    put("reason_ja", "intent がないため拒否されます。")
                },
            ),
        )

        val result = server.tools.getValue("submit_falsification").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals(true, result.isError)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun submitDecisionTool_recordsTradePlanRevisionForNonEnterAction() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val enterRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = enterDecisionArguments(),
            ),
        )

        val enterResult = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, enterRequest)
        val parentTradePlanId = assertNotNull(enterResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "submit_decision",
                arguments = tradePlanRevisionDecisionArguments(
                    parentTradePlanId = assertNotNull(parentTradePlanId),
                    revisionCount = 1,
                ),
            ),
        )

        val result = server.tools.getValue("submit_decision").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertTrue(result.isError != true)
        assertEquals("ADJUST_PROTECTION", structuredContent.getValue("action").jsonPrimitive.contentOrNull)
        assertEquals("1", structuredContent.getValue("revision_count").jsonPrimitive.contentOrNull)
        assertEquals(2, repository.snapshots.tradePlans().size)
        assertEquals(1, repository.snapshots.tradeIntents().size)
    }

    @Test
    fun submitDecisionTool_rejectsTradePlanRevisionCountReset() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val enterResult = server.tools.getValue("submit_decision").handler.invoke(
            TestClientConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "submit_decision",
                    arguments = enterDecisionArguments(),
                ),
            ),
        )
        val firstParentTradePlanId = assertNotNull(enterResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
        val firstRevisionResult = server.tools.getValue("submit_decision").handler.invoke(
            TestClientConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "submit_decision",
                    arguments = tradePlanRevisionDecisionArguments(
                        parentTradePlanId = assertNotNull(firstParentTradePlanId),
                        revisionCount = 1,
                    ),
                ),
            ),
        )
        val secondParentTradePlanId = assertNotNull(firstRevisionResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
        val secondRevisionResult = server.tools.getValue("submit_decision").handler.invoke(
            TestClientConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "submit_decision",
                    arguments = tradePlanRevisionDecisionArguments(
                        parentTradePlanId = assertNotNull(secondParentTradePlanId),
                        revisionCount = 2,
                    ),
                ),
            ),
        )
        val thirdParentTradePlanId = assertNotNull(secondRevisionResult.structuredContent)
            .getValue("trade_plan_id")
            .jsonPrimitive
            .contentOrNull
        val resetRevisionResult = server.tools.getValue("submit_decision").handler.invoke(
            TestClientConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "submit_decision",
                    arguments = tradePlanRevisionDecisionArguments(
                        parentTradePlanId = assertNotNull(thirdParentTradePlanId),
                        revisionCount = 0,
                    ),
                ),
            ),
        )
        val structuredContent = assertNotNull(resetRevisionResult.structuredContent)
        val repository = runtime.decisionRepository as InMemoryDecisionRepository

        assertEquals(true, resetRevisionResult.isError)
        assertEquals("invalid_request", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals(3, repository.snapshots.tradePlans().size)
    }

    @Test
    fun embeddedMarketTool_preservesAuditCompletionFailureResponse() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val failingRuntime = runtime.copy(
            commandEventLog = FailingCommandEventLog,
            toolCallGuard = ToolCallGuard(
                riskStateRepository = runtime.riskStateRepository,
                commandEventLog = FailingCommandEventLog,
                tradingLock = runtime.tradingLock,
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = failingRuntime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_ticker",
                arguments = buildJsonObject {
                    put("symbol", TradingSymbol.BTC.apiSymbol)
                },
            ),
        )

        val result = server.tools.getValue("get_ticker").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)

        assertEquals(true, result.isError)
        assertEquals("audit_failed_after_execution", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("true", structuredContent.getValue("executed").jsonPrimitive.contentOrNull)
    }

    @Test
    fun toolCallLimitExceeded_returnsToolErrorAndNoTradeAudit() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxToolCallsPerRun = 1,
                maxActToolCallsPerRun = 1,
            ),
        )
        val runtime = TradingRuntimeFactory.inMemory(tradingConfig = config)
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            tradingConfig = config,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_balance",
                arguments = buildJsonObject {},
            ),
        )

        server.tools.getValue("get_balance").handler.invoke(TestClientConnection, request)
        val limitedResult = server.tools.getValue("get_balance").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(limitedResult.structuredContent)
        val eventLog = runtime.commandEventLog as InMemoryCommandEventLog
        val noTradeEvent = eventLog.events().single { event ->
            event.eventType == CommandEventType.NO_TRADE_EXIT
        }

        assertEquals(true, limitedResult.isError)
        assertEquals("tool_call_limit_exceeded", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(noTradeEvent.payload.contains("mcp_total_tool_call_limit_exceeded"))
    }

    @Test
    fun toolCallLimitCountsPreviousPhaseEventsForSameDecisionRun() = runBlocking {
        val decisionRunContext = DecisionRunContext(
            decisionRunId = "shared-run",
            llmProvider = "codex",
            promptHash = "hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot",
        )
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxToolCallsPerRun = 1,
                maxActToolCallsPerRun = 1,
            ),
        )
        val runtime = TradingRuntimeFactory.inMemory(tradingConfig = config)
        val eventLog = runtime.commandEventLog as InMemoryCommandEventLog

        eventLog.append(
            CommandEvent(
                decisionRunContext = decisionRunContext,
                toolName = "get_ticker",
                toolCallId = "prior-tool-call",
                clientRequestId = "prior-tool-call",
                eventType = CommandEventType.TOOL_CALL_COMPLETED,
                payload = "{}",
                occurredAt = Instant.parse("2026-07-03T00:00:00Z"),
            ),
        ).getOrThrow()

        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            tradingConfig = config,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
            decisionRunContext = decisionRunContext,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_balance",
                arguments = buildJsonObject {},
            ),
        )

        val limitedResult = server.tools.getValue("get_balance").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(limitedResult.structuredContent)
        val noTradeEvent = eventLog.events().single { event ->
            event.eventType == CommandEventType.NO_TRADE_EXIT
        }

        assertEquals(true, limitedResult.isError)
        assertEquals("tool_call_limit_exceeded", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(noTradeEvent.payload.contains("mcp_total_tool_call_limit_exceeded"))
    }

    @Test
    fun defaultToolCallLimitAllowsMeasuredEnterPathBudget() = runBlocking {
        val decisionRunContext = DecisionRunContext(
            decisionRunId = "measured-enter-run",
            llmProvider = "codex",
            promptHash = "hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot",
        )
        val runtime = TradingRuntimeFactory.inMemory()
        val eventLog = runtime.commandEventLog as InMemoryCommandEventLog
        val limiter = McpToolCallLimiter(
            config = TradingBotConfig().runner,
            toolCallGuard = runtime.toolCallGuard,
            countedToolNames = setOf("get_ticker"),
        )

        repeat(MEASURED_PROPOSER_TOOL_CALLS + MEASURED_FALSIFIER_TOOL_CALLS) { callIndex ->
            eventLog.append(
                CommandEvent(
                    decisionRunContext = decisionRunContext,
                    toolName = "get_ticker",
                    toolCallId = "prior-tool-call-$callIndex",
                    clientRequestId = "prior-tool-call-$callIndex",
                    eventType = CommandEventType.TOOL_CALL_COMPLETED,
                    payload = "{}",
                    occurredAt = Instant.parse("2026-07-03T00:00:00Z"),
                ),
            ).getOrThrow()
        }

        val result = limiter.acquire(
            call = GuardedToolCall(
                toolName = "get_ticker",
                toolCallId = "current-tool-call",
                clientRequestId = "current-tool-call",
                decisionRunContext = decisionRunContext,
                payload = "{}",
            ),
            kind = McpToolCallKind.READ_ONLY,
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun toolCallLimitCountUnavailable_returnsToolErrorAndNoTradeAudit() = runBlocking {
        val decisionRunContext = DecisionRunContext(
            decisionRunId = "count-failure-run",
            llmProvider = "codex",
            promptHash = "hash",
            systemPromptVersion = "system-prompt-v1",
            marketSnapshotId = "snapshot",
        )
        val baseRuntime = TradingRuntimeFactory.inMemory()
        val eventLog = CountFailingCommandEventLog(baseRuntime.commandEventLog as InMemoryCommandEventLog)
        val runtime = baseRuntime.copy(
            commandEventLog = eventLog,
            toolCallGuard = ToolCallGuard(
                riskStateRepository = baseRuntime.riskStateRepository,
                commandEventLog = eventLog,
                tradingLock = baseRuntime.tradingLock,
            ),
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
            decisionRunContext = decisionRunContext,
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_balance",
                arguments = buildJsonObject {},
            ),
        )

        val result = server.tools.getValue("get_balance").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(result.structuredContent)
        val noTradeEvent = eventLog.events().single { event ->
            event.eventType == CommandEventType.NO_TRADE_EXIT
        }

        assertEquals(true, result.isError)
        assertEquals("tool_call_limit_unavailable", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(noTradeEvent.payload.contains("mcp_tool_call_count_unavailable"))
    }

    @Test
    fun actToolCallLimitExceeded_doesNotRejectReadOnlyToolWhenTotalBudgetRemains() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxToolCallsPerRun = 5,
                maxActToolCallsPerRun = 1,
            ),
        )
        val runtime = TradingRuntimeFactory.inMemory(tradingConfig = config)
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            tradingConfig = config,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
        ).createServer()
        val tradeRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "reject_dummy_trade",
                arguments = buildJsonObject {
                    put("reason", "act limit test")
                },
            ),
        )
        val readRequest = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_balance",
                arguments = buildJsonObject {},
            ),
        )

        server.tools.getValue("reject_dummy_trade").handler.invoke(TestClientConnection, tradeRequest)
        val tradeLimitedResult = server.tools.getValue("reject_dummy_trade").handler.invoke(TestClientConnection, tradeRequest)
        val readResult = server.tools.getValue("get_balance").handler.invoke(TestClientConnection, readRequest)
        val tradeLimitedContent = assertNotNull(tradeLimitedResult.structuredContent)

        assertEquals(true, tradeLimitedResult.isError)
        assertEquals("tool_call_limit_exceeded", tradeLimitedContent.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(readResult.isError != true)
    }

    @Test
    fun previewOrderTool_doesNotConsumeActToolBudget() = runBlocking {
        val config = TradingBotConfig(
            runner = LlmRunnerConfig(
                maxToolCallsPerRun = 5,
                maxActToolCallsPerRun = 1,
            ),
        )
        val runtime = TradingRuntimeFactory.inMemory(
            clock = fixedClock(),
            marketDataSource = PreviewMarketDataSource,
            tradingConfig = config,
        )
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            tradingConfig = config,
            marketDataSource = PreviewMarketDataSource,
            clock = fixedClock(),
            tradingRuntime = runtime,
        ).createServer()
        val intentId = submitApprovedEnterIntent(server)
        val tradeRequest = buildJsonObject {
            put("reason", "act limit test")
        }

        val tradeResult = callTool(server, "reject_dummy_trade", tradeRequest)
        val previewResult = callTool(server, "preview_order", placeOrderArguments(intentId))
        val previewContent = assertNotNull(previewResult.structuredContent)

        assertEquals(true, tradeResult.isError)
        assertTrue(previewResult.isError != true)
        assertEquals(true, previewContent.getValue("accepted").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun toolAllowlistDenied_returnsToolErrorAndNoTradeAudit() = runBlocking {
        val runtime = TradingRuntimeFactory.inMemory()
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            tradingRuntime = runtime,
            toolCallLimiter = McpToolCallLimiter(
                config = TradingBotConfig().runner,
                toolCallGuard = runtime.toolCallGuard,
                allowedToolNames = setOf("get_balance"),
            ),
        ).createServer()
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_positions",
                arguments = buildJsonObject {},
            ),
        )

        val deniedResult = server.tools.getValue("get_positions").handler.invoke(TestClientConnection, request)
        val structuredContent = assertNotNull(deniedResult.structuredContent)
        val eventLog = runtime.commandEventLog as InMemoryCommandEventLog
        val noTradeEvent = eventLog.events().single { event ->
            event.eventType == CommandEventType.NO_TRADE_EXIT
        }

        assertEquals(true, deniedResult.isError)
        assertEquals("tool_call_not_allowed", structuredContent.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(noTradeEvent.payload.contains("mcp_tool_not_allowed"))
    }
}

/** least-privilege PostgreSQL role と production bootstrap/server path の integration。 */
class McpLaunchBootstrapPolicyTest {
    @Test
    fun bootstrapAndDatabaseConfig_redactPasswordFromToString() {
        val bootstrap = decodeBootstrap(bootstrapManifest(LlmInvocationPhase.PROPOSER, fixedClock()), fixedClock())

        assertFalse(bootstrap.toString().contains(MCP_TEST_PASSWORD))
        assertFalse(bootstrap.databaseConfig.toString().contains(MCP_TEST_PASSWORD))
        assertTrue(bootstrap.toString().contains("password=<redacted>"))
    }

    @Test
    fun bothPhasesRejectUnknownTamperedExpiredEmptyAndBudgetExceed() {
        val clock = fixedClock()
        listOf(LlmInvocationPhase.PROPOSER, LlmInvocationPhase.FALSIFIER).forEach { phase ->
            val canonical = bootstrapManifest(phase, clock)
            assertNotNull(decodeBootstrap(canonical, clock))
            listOf(
                canonical.copy(phase = "UNKNOWN"),
                canonical.copy(allowedTools = canonical.allowedTools + "place_order"),
                canonical.copy(allowedTools = emptyList()),
                canonical.copy(expiresAt = Instant.now(clock).minusSeconds(1).toString()),
                canonical.copy(totalToolCallLimit = 49),
                canonical.copy(actToolCallLimit = 4),
                canonical.copy(totalToolCallLimit = 1, actToolCallLimit = 2),
                canonical.copy(runtimeEnvironment = emptyMap()),
                canonical.copy(runtimeEnvironment = canonical.runtimeEnvironment + ("UNKNOWN_RUNTIME_KEY" to "tampered")),
                canonical.copy(systemPromptVersion = ""),
            ).forEach { rejected ->
                assertNotNull(runCatching { decodeBootstrap(rejected, clock) }.exceptionOrNull())
            }
        }
    }

    @Test
    fun bootstrapSystemPromptVersion_isUsedBySubmitDecisionFallbackAndAudit() = runBlocking {
        val clock = fixedClock()
        val bootstrap = decodeBootstrap(bootstrapManifest(LlmInvocationPhase.PROPOSER, clock), clock)
        val runtime = TradingRuntimeFactory.inMemory(clock = clock)
        val server = FukurouMcpServer(
            clientRole = GmoPublicClientRole.UNSPECIFIED,
            marketDataSource = FakeMarketDataSource,
            clock = clock,
            tradingRuntime = runtime,
            decisionRunContext = bootstrap.decisionRunContext,
            allowedToolNames = bootstrap.allowedTools,
            expiresAt = bootstrap.expiresAt,
        ).createServer()

        val result = callTool(server, "submit_decision", enterDecisionArguments())
        val repository = runtime.decisionRepository as InMemoryDecisionRepository
        val submission = repository.snapshots.decisions().single().submission
        val auditEvents = (runtime.commandEventLog as InMemoryCommandEventLog).events()
            .filter { event -> event.toolName == "submit_decision" }

        assertTrue(result.isError != true)
        assertEquals("fixture-system-prompt-v1", bootstrap.decisionRunContext.systemPromptVersion)
        assertEquals("fixture-system-prompt-v1", submission.systemPromptVersion)
        assertTrue(auditEvents.isNotEmpty())
        assertTrue(auditEvents.all { event -> event.decisionRunContext.systemPromptVersion == "fixture-system-prompt-v1" })
    }
}

private fun bootstrapManifest(phase: LlmInvocationPhase, clock: Clock): McpLaunchManifest {
    val allowedTools = when (phase) {
        LlmInvocationPhase.PROPOSER -> CANONICAL_PROPOSER_MCP_TOOL_NAMES
        LlmInvocationPhase.FALSIFIER -> CANONICAL_FALSIFIER_MCP_TOOL_NAMES
        else -> error("unsupported test phase")
    }
    return McpLaunchManifest(
        version = MCP_MANIFEST_VERSION,
        invocationId = "policy-test",
        phase = phase.name,
        expiresAt = Instant.now(clock).plusSeconds(60).toString(),
        allowedTools = allowedTools.sorted(),
        decisionRunId = "policy-test",
        llmProvider = "fixture",
        promptHash = "fixture-hash",
        systemPromptVersion = "fixture-system-prompt-v1",
        marketSnapshotId = "fixture-snapshot",
        dbUrl = "jdbc:postgresql://fixture/fukurou",
        dbUser = MCP_TEST_ROLE,
        gmoPublicBaseUrl = "http://127.0.0.1:1",
        runtimeEnvironment = RuntimeConfigCatalog.runtimeEnvironment(TradingBotConfig()),
        totalToolCallLimit = 48,
        actToolCallLimit = 3,
    )
}

private fun decodeBootstrap(manifest: McpLaunchManifest, clock: Clock): McpBootstrapConfig {
    val bytes = kotlinx.serialization.json.Json.encodeToString(manifest).encodeToByteArray()
    return McpLaunchBootstrap.decode(bytes, MCP_TEST_PASSWORD.encodeToByteArray(), clock)
}

/** least-privilege PostgreSQL role と production bootstrap/server path の integration。 */
class McpDatabaseRoleIntegrationTest {
    @Test
    fun leastPrivilegeRole_supportsRequiredMatrixAndRejectsForbiddenWrites() = runBlocking {
        if (!DockerClientFactory.instance().isDockerAvailable) return@runBlocking

        val container = PostgreSQLContainer("postgres:16-alpine")
        container.start()
        val marketFixture = GmoRequiredMatrixFixture.start()
        var runtime: me.matsumo.fukurou.trading.runtime.TradingRuntime? = null
        try {
            val clock = fixedClock()
            val database = ExposedDatabase.connect(container.jdbcUrl, driver = "org.postgresql.Driver", user = container.username, password = container.password)
            assertMcpRoleProvisionRequiresBootstrap(container)
            TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()
            seedRequiredMatrixRun(container, clock)
            seedDirtyMcpPrivileges(container)
            provisionMcpRole(container)
            provisionMcpRole(container)
            createFuturePrivilegeBoundaryObjects(container)
            assertRoleBoundary(container)
            TradingRuntimeFactory.postgres(
                TradingDatabaseConfig(container.jdbcUrl, container.username, container.password),
                clock = clock,
            ).close()

            val bootstrap = requiredMatrixBootstrap(container, clock, LlmInvocationPhase.PROPOSER)
            val tradingConfig = TradingBotConfig(
                gmoPublicClient = GmoPublicClientConfig(
                    baseUrl = marketFixture.baseUrl,
                    connectTimeout = Duration.ofSeconds(2),
                    requestTimeout = Duration.ofSeconds(2),
                    retry = GmoRetryConfig(maxAttempts = 1),
                ),
            )
            val requestAuditSink = DeferredGmoPublicRequestAuditSink()
            val marketDataSource = GmoPublicMarketDataSource.fromConfig(
                config = tradingConfig.gmoPublicClient,
                clientType = me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientType.FUKUROU_MCP,
                requestAuditSink = requestAuditSink,
                clock = clock,
            )
            val fixtureMarketDataSource = RequiredMatrixMarketDataSource(marketDataSource)
            runtime = TradingRuntimeFactory.postgresForMcp(
                config = bootstrap.databaseConfig,
                clock = clock,
                marketDataSource = fixtureMarketDataSource,
                tradingConfig = tradingConfig,
            )
            var server = FukurouMcpServer(
                tradingConfig = tradingConfig,
                requestAuditSink = requestAuditSink,
                marketDataSource = fixtureMarketDataSource,
                clock = clock,
                tradingRuntime = runtime,
                decisionRunContext = bootstrap.decisionRunContext,
                clientRole = bootstrap.phase.toGmoPublicClientRole(),
                allowedToolNames = bootstrap.allowedTools,
                expiresAt = bootstrap.expiresAt,
            ).createServer()
            val results = linkedMapOf<String, CallToolResult>()
            results["get_ticker"] = callTool(server, "get_ticker")
            results["get_candles"] = callTool(
                server = server,
                toolName = "get_candles",
                arguments = buildJsonObject {
                    put("interval", "1hour")
                    put("limit", 1)
                },
            )
            results["get_orderbook"] = callTool(server, "get_orderbook")
            results["get_trades"] = callTool(server, "get_trades")
            results["get_symbol_rules"] = callTool(server, "get_symbol_rules")
            results["calc_indicator"] = callTool(
                server = server,
                toolName = "calc_indicator",
                arguments = buildJsonObject {
                    put("interval", "1hour")
                    put("indicator", "SMA")
                    putJsonObject("params") {
                        put("period", 1)
                    }
                },
            )
            results["get_balance"] = callTool(server, "get_balance")
            results["get_positions"] = callTool(server, "get_positions")
            results["get_open_orders"] = callTool(server, "get_open_orders")
            results["get_account_status"] = callTool(server, "get_account_status")
            results["knowledge_get_recent_lessons"] = callTool(server, "knowledge_get_recent_lessons")
            results["knowledge_search_similar_setups"] = callTool(
                server = server,
                toolName = "knowledge_search_similar_setups",
                arguments = buildJsonObject {
                    put("signal_summary", "breakout")
                    put("limit", 3)
                },
            )
            val decision = callTool(server, "submit_decision", enterDecisionArguments(invocationId = MCP_MATRIX_RUN_ID))
            results["submit_decision"] = decision
            val intentId = assertNotNull(decision.structuredContent).getValue("intent_id").jsonPrimitive.contentOrNull
            results["get_trade_intent"] = callTool(server, "get_trade_intent", buildJsonObject { put("intent_id", intentId) })
            assertIdentityDualWrite(container)
            runtime.close()
            val falsifierBootstrap = requiredMatrixBootstrap(container, clock, LlmInvocationPhase.FALSIFIER)
            val falsifierRequestAuditSink = DeferredGmoPublicRequestAuditSink()
            val falsifierMarketDataSource = RequiredMatrixMarketDataSource(
                GmoPublicMarketDataSource.fromConfig(
                    config = tradingConfig.gmoPublicClient,
                    clientType = me.matsumo.fukurou.trading.exchange.gmo.GmoPublicClientType.FUKUROU_MCP,
                    requestAuditSink = falsifierRequestAuditSink,
                    clock = clock,
                ),
            )
            runtime = TradingRuntimeFactory.postgresForMcp(
                config = falsifierBootstrap.databaseConfig,
                clock = clock,
                marketDataSource = falsifierMarketDataSource,
                tradingConfig = tradingConfig,
            )
            server = FukurouMcpServer(
                tradingConfig = tradingConfig,
                requestAuditSink = falsifierRequestAuditSink,
                marketDataSource = falsifierMarketDataSource,
                clock = clock,
                tradingRuntime = runtime,
                decisionRunContext = falsifierBootstrap.decisionRunContext,
                clientRole = falsifierBootstrap.phase.toGmoPublicClientRole(),
                allowedToolNames = falsifierBootstrap.allowedTools,
                expiresAt = falsifierBootstrap.expiresAt,
            ).createServer()
            results["submit_falsification"] = callTool(
                server,
                "submit_falsification",
                buildJsonObject {
                    put("intent_id", intentId)
                    put("verdict", FalsificationVerdict.APPROVED.name)
                    put("llm_provider", "codex")
                    put("reason_ja", "fixture data の反証を完了しました。")
                },
            )
            results["preview_order"] = callTool(server, "preview_order", placeOrderArguments(assertNotNull(intentId)))

            assertEquals(MCP_REQUIRED_CALL_COUNT, results.size)
            assertTrue(results.none { (_, result) -> result.isError == true }, "Required MCP call failures: ${results.filterValues { it.isError == true }.keys}")
            assertForbiddenDml(container)
            assertNoFallback(container, clock)
        } finally {
            runtime?.close()
            marketFixture.close()
            container.stop()
        }
    }
}

private fun assertIdentityDualWrite(container: PostgreSQLContainer<*>) {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
            assertSqlCount(
                statement,
                """SELECT COUNT(*) FROM decisions d JOIN trade_intents ti ON ti.decision_id=d.id
                    WHERE d.opportunity_episode_id=ti.opportunity_episode_id
                    AND d.thesis_id=ti.thesis_id AND d.geometry_hash=ti.geometry_hash
                    AND d.material_state_hash=ti.material_state_hash
                    AND d.identity_schema_version=ti.identity_schema_version
                    AND d.opportunity_episode_id IS NOT NULL
                """.trimIndent(),
                1,
            )
        }
    }
}

private fun seedDirtyMcpPrivileges(container: PostgreSQLContainer<*>) {
    val sql = """
        CREATE ROLE $MCP_TEST_ROLE LOGIN PASSWORD '$MCP_TEST_PASSWORD' SUPERUSER CREATEDB CREATEROLE REPLICATION BYPASSRLS INHERIT;
        CREATE ROLE mcp_dirty_parent;
        CREATE ROLE mcp_dirty_child;
        CREATE ROLE mcp_dirty_grantor;
        GRANT mcp_dirty_parent TO $MCP_TEST_ROLE;
        GRANT $MCP_TEST_ROLE TO mcp_dirty_child;
        CREATE TABLE mcp_dirty_owned(id bigint generated always as identity, value text);
        ALTER TABLE mcp_dirty_owned OWNER TO $MCP_TEST_ROLE;
        CREATE FUNCTION mcp_dirty_function() RETURNS integer LANGUAGE sql AS 'SELECT 1';
        ALTER FUNCTION mcp_dirty_function() OWNER TO $MCP_TEST_ROLE;
        GRANT ALL ON ALL TABLES IN SCHEMA public TO $MCP_TEST_ROLE;
        REVOKE UPDATE ON orders FROM $MCP_TEST_ROLE;
        GRANT UPDATE (status) ON orders TO $MCP_TEST_ROLE;
        GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO $MCP_TEST_ROLE;
        GRANT ALL ON ALL FUNCTIONS IN SCHEMA public TO $MCP_TEST_ROLE;
        GRANT SELECT ON ALL TABLES IN SCHEMA public TO PUBLIC;
        REVOKE UPDATE ON orders FROM PUBLIC;
        GRANT UPDATE (status) ON orders TO PUBLIC;
        GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO PUBLIC;
        ALTER DEFAULT PRIVILEGES FOR ROLE ${container.username} IN SCHEMA public GRANT ALL ON TABLES TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE ${container.username} IN SCHEMA public GRANT ALL ON SEQUENCES TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE ${container.username} IN SCHEMA public GRANT ALL ON FUNCTIONS TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE ${container.username} IN SCHEMA public GRANT SELECT ON TABLES TO PUBLIC;
        ALTER DEFAULT PRIVILEGES FOR ROLE ${container.username} IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO PUBLIC;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor IN SCHEMA public GRANT SELECT ON TABLES TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor IN SCHEMA public GRANT SELECT ON TABLES TO PUBLIC;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor GRANT SELECT ON TABLES TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor GRANT SELECT ON TABLES TO PUBLIC;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor GRANT EXECUTE ON FUNCTIONS TO $MCP_TEST_ROLE;
        ALTER DEFAULT PRIVILEGES FOR ROLE mcp_dirty_grantor GRANT EXECUTE ON FUNCTIONS TO PUBLIC;
    """.trimIndent()
    val result = container.execInContainer(
        "psql", "-U", container.username, "-d", container.databaseName,
        "-v", "ON_ERROR_STOP=1", "-c", sql,
    )
    check(result.exitCode == 0) { "dirty MCP fixture failed: ${result.stderr}" }
}

private fun createFuturePrivilegeBoundaryObjects(container: PostgreSQLContainer<*>) {
    val sql = """
        CREATE TABLE mcp_future_boundary(id bigint);
        CREATE FUNCTION mcp_future_boundary_function() RETURNS integer LANGUAGE sql AS 'SELECT 1';
    """.trimIndent()
    val result = container.execInContainer(
        "psql", "-U", container.username, "-d", container.databaseName,
        "-v", "ON_ERROR_STOP=1", "-c", sql,
    )
    check(result.exitCode == 0) { "future privilege boundary fixture failed: ${result.stderr}" }
}

private suspend fun seedRequiredMatrixRun(container: PostgreSQLContainer<*>, clock: Clock) {
    val runtime = TradingRuntimeFactory.postgres(
        TradingDatabaseConfig(container.jdbcUrl, container.username, container.password),
        clock = clock,
    )
    try {
        runtime.llmRunRepository.insertRunning(
            LlmRunStart(
                invocationId = MCP_MATRIX_RUN_ID,
                mode = TradingMode.PAPER,
                symbol = TradingSymbol.BTC,
                triggerKind = null,
                startedAt = Instant.now(clock),
            ),
        ).getOrThrow()
        runtime.decisionMaterialStateRepository.append(
            DecisionMaterialStateManifest(
                invocationId = MCP_MATRIX_RUN_ID,
                capturedAt = Instant.now(clock),
                triggerKind = DecisionTriggerKind.MANUAL,
                symbol = TradingSymbol.BTC.apiSymbol,
                runtimeConfigVersion = null,
                runtimeConfigHash = null,
                riskState = "RUNNING",
                bestBidJpy = BigDecimal("99"),
                bestAskJpy = BigDecimal("101"),
                lastPriceJpy = BigDecimal("100"),
                sourceTimestamp = Instant.now(clock),
                freshness = MaterialFreshness.FRESH,
                atr14FiveMinutesJpy = BigDecimal("5"),
                latestCandleOpenJpy = BigDecimal("100"),
                latestCandleHighJpy = BigDecimal("110"),
                latestCandleLowJpy = BigDecimal("90"),
                latestCandleCloseJpy = BigDecimal("105"),
                openPositionFacts = emptyList(),
                openOrderFacts = emptyList(),
                missingSources = emptyList(),
                canonicalContentHash = "a".repeat(64),
                materialProjection = "risk=RUNNING\npriceMoveBand=0",
            ),
        ).getOrThrow()
    } finally {
        runtime.close()
    }
}

private fun provisionMcpRole(container: PostgreSQLContainer<*>) {
    val result = runMcpRoleProvision(container)
    check(result.exitCode == 0) { "MCP role SQL failed: ${result.stderr}" }
    val roleCheck = container.execInContainer(
        "psql", "-U", container.username, "-d", container.databaseName,
        "-Atc", "SELECT rolname FROM pg_roles WHERE rolname='$MCP_TEST_ROLE'",
    )
    check(roleCheck.stdout.trim() == MCP_TEST_ROLE) { "MCP role was not created; SQL output=${result.stdout}" }
}

private fun assertMcpRoleProvisionRequiresBootstrap(container: PostgreSQLContainer<*>) {
    val result = runMcpRoleProvision(container)

    assertTrue(result.exitCode != 0)
    assertTrue(result.stderr.contains("deploy the application schema/bootstrap"))
    val roleCheck = container.execInContainer(
        "psql", "-U", container.username, "-d", container.databaseName,
        "-Atc", "SELECT count(*) FROM pg_roles WHERE rolname='$MCP_TEST_ROLE'",
    )
    assertEquals("0", roleCheck.stdout.trim())
}

private fun runMcpRoleProvision(container: PostgreSQLContainer<*>): Container.ExecResult {
    val sqlPath = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
        .map { path -> path.resolve("scripts/deploy/sql/mcp-role.sql") }
        .first { path -> java.nio.file.Files.isRegularFile(path) }
    container.copyFileToContainer(MountableFile.forHostPath(sqlPath), "/tmp/mcp-role.sql")
    return container.execInContainer(
        "psql", "-U", container.username, "-d", container.databaseName,
        "-v", "mcp_role=$MCP_TEST_ROLE", "-v", "mcp_password=$MCP_TEST_PASSWORD",
        "-v", "database_name=${container.databaseName}", "-v", "app_role=${container.username}",
        "-f", "/tmp/mcp-role.sql",
    )
}

@Suppress("NestedBlockDepth")
private fun assertRoleBoundary(container: PostgreSQLContainer<*>) {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolinherit FROM pg_roles WHERE rolname='$MCP_TEST_ROLE'").use { rows ->
                assertTrue(rows.next())
                (1..6).forEach { column -> assertEquals(false, rows.getBoolean(column)) }
            }
            assertSqlCount(statement, "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE')", 0)
            assertSqlCount(statement, "SELECT count(*) FROM pg_auth_members WHERE roleid=(SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE')", 0)
            assertSqlCount(statement, "SELECT count(*) FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE')", 0)
            assertSqlCount(statement, "SELECT count(*) FROM pg_proc WHERE proowner=(SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE')", 0)
            statement.executeQuery("SELECT has_database_privilege('public', current_database(), 'CREATE'), has_database_privilege('public', current_database(), 'TEMP'), has_schema_privilege('public', 'public', 'CREATE')").use { rows ->
                assertTrue(rows.next())
                (1..3).forEach { column -> assertEquals(false, rows.getBoolean(column)) }
            }
            assertSqlCount(statement, "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee='PUBLIC' AND table_schema='public'", 0)
            statement.executeQuery(
                "SELECT has_function_privilege('public', 'pg_catalog.pg_advisory_xact_lock(bigint)', 'EXECUTE')",
            ).use { rows ->
                assertTrue(rows.next())
                assertFalse(rows.getBoolean(1))
            }
            assertSqlCount(
                statement,
                "SELECT count(*) FROM pg_attribute attribute " +
                    "JOIN pg_class relation ON relation.oid=attribute.attrelid " +
                    "JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace " +
                    "CROSS JOIN LATERAL aclexplode(attribute.attacl) acl " +
                    "WHERE namespace.nspname='public' AND acl.grantee IN (0, (SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE'))",
                2,
            )
            statement.executeQuery(
                "SELECT " +
                    "has_table_privilege('public', 'mcp_future_boundary', 'SELECT'), " +
                    "has_table_privilege('$MCP_TEST_ROLE', 'mcp_future_boundary', 'SELECT'), " +
                    "has_function_privilege('public', 'mcp_future_boundary_function()', 'EXECUTE'), " +
                    "has_function_privilege('$MCP_TEST_ROLE', 'mcp_future_boundary_function()', 'EXECUTE')",
            ).use { rows ->
                assertTrue(rows.next())
                (1..4).forEach { column -> assertFalse(rows.getBoolean(column)) }
            }
            assertSqlCount(
                statement,
                "SELECT count(*) FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(COALESCE(d.defaclacl, acldefault(d.defaclobjtype, d.defaclrole))) a " +
                    "WHERE d.defaclnamespace IN (0, 'public'::regnamespace) " +
                    "AND a.grantee IN (0, (SELECT oid FROM pg_roles WHERE rolname='$MCP_TEST_ROLE'))",
                0,
            )
            assertSqlCount(
                statement,
                "SELECT count(*) FROM information_schema.role_usage_grants WHERE grantee='$MCP_TEST_ROLE' AND object_schema='public'",
                0,
            )
        }
    }
    mcpTestConnection(container).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT has_table_privilege(current_user, 'command_event_log', 'SELECT,INSERT'), has_table_privilege(current_user, 'orders', 'SELECT'), has_table_privilege(current_user, 'orders', 'UPDATE'), has_function_privilege(current_user, 'pg_catalog.pg_try_advisory_lock(bigint)', 'EXECUTE'), has_function_privilege(current_user, 'pg_catalog.pg_advisory_xact_lock(bigint)', 'EXECUTE'), has_database_privilege(current_user, current_database(), 'TEMP'), has_column_privilege(current_user, 'opportunity_episodes', 'closed_at', 'UPDATE'), has_column_privilege(current_user, 'opportunity_episodes', 'close_reason', 'UPDATE')").use { rows ->
                assertTrue(rows.next())
                assertEquals(true, rows.getBoolean(1))
                assertEquals(true, rows.getBoolean(2))
                assertEquals(false, rows.getBoolean(3))
                assertEquals(true, rows.getBoolean(4))
                assertEquals(true, rows.getBoolean(5))
                assertEquals(false, rows.getBoolean(6))
                assertEquals(true, rows.getBoolean(7))
                assertEquals(true, rows.getBoolean(8))
            }
        }
    }
}

private fun requiredMatrixBootstrap(
    container: PostgreSQLContainer<*>,
    clock: Clock,
    phase: LlmInvocationPhase,
): McpBootstrapConfig {
    val allowedTools = when (phase) {
        LlmInvocationPhase.PROPOSER -> CANONICAL_PROPOSER_MCP_TOOL_NAMES
        LlmInvocationPhase.FALSIFIER -> CANONICAL_FALSIFIER_MCP_TOOL_NAMES
        else -> error("unsupported test phase")
    }
    val manifest = McpLaunchManifest(
        version = MCP_MANIFEST_VERSION,
        invocationId = MCP_MATRIX_RUN_ID,
        phase = phase.name,
        expiresAt = Instant.now(clock).plusSeconds(300).toString(),
        allowedTools = allowedTools.sorted(),
        decisionRunId = MCP_MATRIX_RUN_ID,
        llmProvider = "fixture",
        promptHash = "fixture-hash",
        systemPromptVersion = "fixture-system-prompt-v1",
        marketSnapshotId = "fixture-snapshot",
        dbUrl = container.jdbcUrl,
        dbUser = MCP_TEST_ROLE,
        gmoPublicBaseUrl = "http://127.0.0.1:1",
        runtimeEnvironment = RuntimeConfigCatalog.runtimeEnvironment(TradingBotConfig()),
        totalToolCallLimit = 48,
        actToolCallLimit = 3,
    )
    val manifestBytes = kotlinx.serialization.json.Json.encodeToString(manifest).encodeToByteArray()
    return McpLaunchBootstrap.decode(manifestBytes, MCP_TEST_PASSWORD.encodeToByteArray(), clock)
}

private fun assertForbiddenDml(container: PostgreSQLContainer<*>) {
    mcpTestConnection(container).use { connection ->
        listOf(
            "UPDATE orders SET status=status",
            "DELETE FROM executions",
            "TRUNCATE positions",
            "INSERT INTO orders DEFAULT VALUES",
            "UPDATE mcp_current_evaluation_scope SET account_initial_cash_jpy=account_initial_cash_jpy",
            "UPDATE mcp_evaluation_epochs SET initial_cash_jpy=initial_cash_jpy",
            "SELECT config_value FROM runtime_config_values LIMIT 1",
            "SELECT runtime_config_hash FROM paper_account_epochs LIMIT 1",
        ).forEach { sql ->
            assertNotNull(runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull())
        }
    }
}

private fun assertNoFallback(container: PostgreSQLContainer<*>, clock: Clock) {
    assertNotNull(
        runCatching {
            TradingRuntimeFactory.postgresForMcp(
                TradingDatabaseConfig("${container.jdbcUrl}?connectTimeout=2&socketTimeout=2", MCP_TEST_ROLE, "wrong-dummy-password"),
                clock = clock,
            )
        }.exceptionOrNull(),
    )
}

private fun mcpTestConnection(container: PostgreSQLContainer<*>) =
    DriverManager.getConnection(container.jdbcUrl, MCP_TEST_ROLE, MCP_TEST_PASSWORD)

private fun assertSqlCount(
    statement: java.sql.Statement,
    sql: String,
    expected: Int,
) {
    statement.executeQuery(sql).use { rows ->
        assertTrue(rows.next())
        assertEquals(expected, rows.getInt(1))
    }
}

private class GmoRequiredMatrixFixture private constructor(private val server: HttpServer) : AutoCloseable {
    val baseUrl = "http://127.0.0.1:${server.address.port}/public"

    override fun close() = server.stop(0)

    companion object {
        fun start(): GmoRequiredMatrixFixture {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/public/v1/") { exchange -> exchange.respondRequiredMatrixFixture() }
            server.start()
            return GmoRequiredMatrixFixture(server)
        }
    }
}

private class RequiredMatrixMarketDataSource(
    private val fixtureHttpSource: GmoPublicMarketDataSource,
) : MarketDataSource by FakeMarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> = fixtureHttpSource.getTicker(symbol)
}

private fun HttpExchange.respondRequiredMatrixFixture() {
    val body = when (requestURI.path.substringAfterLast('/')) {
        "ticker" -> """{"status":0,"data":[{"symbol":"BTC","ask":"101","bid":"99","high":"110","last":"100","low":"90","volume":"1.0","timestamp":"2026-07-01T00:00:00.000Z"}]}"""
        "orderbooks" -> """{"status":0,"data":{"asks":[{"price":"101","size":"0.1"}],"bids":[{"price":"99","size":"0.1"}],"symbol":"BTC"}}"""
        "trades" -> """{"status":0,"data":{"list":[{"price":"100","side":"BUY","size":"0.01","timestamp":"2026-07-01T00:00:00.000Z"}]}}"""
        "symbols" -> """{"status":0,"data":[{"symbol":"BTC","minOrderSize":"0.0001","maxOrderSize":"5","sizeStep":"0.0001","tickSize":"1","takerFee":"0.0005","makerFee":"-0.0001"}]}"""
        "klines" -> """{"status":0,"data":[{"openTime":"1751328000000","open":"100","high":"110","low":"90","close":"105","volume":"1.0"}]}"""
        else -> """{"status":1,"messages":[{"message_code":"ERR","message_string":"unknown fixture path"}]}"""
    }.encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, body.size.toLong())
    responseBody.use { output -> output.write(body) }
}

private const val MCP_TEST_ROLE = "fukurou_mcp"
private const val MCP_TEST_PASSWORD = "FUKUROU_CANARY_DB_ROLE_DUMMY_ONLY"
private const val MCP_MATRIX_RUN_ID = "mcp-required-matrix-run"
private const val MCP_REQUIRED_CALL_COUNT = 16

/**
 * #19 で観測した Proposer tool call 数。
 */
private const val MEASURED_PROPOSER_TOOL_CALLS = 16

/**
 * #19 で観測した Falsifier tool call 数。
 */
private const val MEASURED_FALSIFIER_TOOL_CALLS = 17

/**
 * NO_TRADE decision tool request の引数を作る。
 *
 * @param expectedRMultiple expected_r_multiple。null の場合は欠落ケースを作る。
 */
private fun noTradeDecisionArguments(expectedRMultiple: String? = "0", invocationId: String? = null) = buildJsonObject {
    invocationId?.let { value -> put("invocation_id", value) }
    put("action", "NO_TRADE")
    put("estimated_win_probability", "0.12")
    if (expectedRMultiple != null) {
        put("expected_r_multiple", expectedRMultiple)
    }
    put("tool_evidence_ids", stringArray("tool-1"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":["出来高不足"]}""")
    put("reason_ja", "材料不足のため見送ります。")
    put("missing_data_ja", stringArray("orderbook"))
    put("no_trade_conditions_ja", stringArray("出来高が戻るまで待つ"))
}

/**
 * ENTER decision tool request の引数を作る。
 */
private fun enterDecisionArguments(invocationId: String? = null) = buildJsonObject {
    invocationId?.let { value -> put("invocation_id", value) }
    put("action", "ENTER")
    put("setup_tags", stringArray("breakout", "trend-follow"))
    put("estimated_win_probability", "0.73")
    put("expected_r_multiple", "1.80")
    put("round_trip_cost_r", "0.05")
    put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":["spread"]}""")
    put("reason_ja", "ブレイク継続を狙います。")
    put("symbol", TradingSymbol.BTC.apiSymbol)
    put("side", "BUY")
    put("type", "MARKET")
    put("size_btc", "0.0050")
    put("protective_stop_price_jpy", "9700000")
    put("take_profit_price_jpy", "10500000")
    put("trade_plan_revision_count", 0)
    put("trade_plan_thesis_ja", "1時間足の上昇継続に乗る。")
    put("trade_plan_invalidation_conditions_ja", stringArray("直近安値割れ", "出来高急減"))
    put(
        "trade_plan_invalidation_predicates",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "LAST_PRICE_AT_OR_BELOW")
                    put("threshold_jpy", "9700000")
                },
            )
        },
    )
    put("trade_plan_target_price_jpy", "10500000")
    put("trade_plan_time_stop_at", "2026-07-02T01:00:00Z")
}

/**
 * EXIT decision tool request の引数を作る。
 *
 * @param closeRatio close_ratio。null の場合は指定しない。
 */
private fun exitDecisionArguments(closeRatio: String? = null) = buildJsonObject {
    put("action", "EXIT")
    closeRatio?.let { value -> put("close_ratio", value) }
    put("estimated_win_probability", "0.40")
    put("expected_r_multiple", "0")
    put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":["risk"]}""")
    put("reason_ja", "否定条件に達したため全量退出します。")
}

/**
 * REDUCE decision tool request の引数を作る。
 *
 * @param closeRatio close_ratio。null の場合は欠落ケースを作る。
 */
private fun reduceDecisionArguments(closeRatio: String? = "0.50") = buildJsonObject {
    put("action", "REDUCE")
    closeRatio?.let { value -> put("close_ratio", value) }
    put("estimated_win_probability", "0.62")
    put("expected_r_multiple", "0.80")
    put("round_trip_cost_r", "0.05")
    put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":[]}""")
    put("reason_ja", "含み益の一部を確定し、残りは保護を維持します。")
}

/**
 * ADD_LONG decision tool request の引数を作る。
 */
private fun addLongDecisionArguments(
    parentTradePlanId: String,
    setupTags: JsonArray = stringArray("breakout"),
): JsonObject {
    return buildJsonObject {
        put("action", "ADD_LONG")
        put("setup_tags", setupTags)
        put("estimated_win_probability", "0.74")
        put("expected_r_multiple", "1.60")
        put("round_trip_cost_r", "0.05")
        put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
        put("fact_check", """{"ticker":true}""")
        put("self_review", """{"reasonsNotToTrade":[]}""")
        put(
            "reason_ja",
            "含み益がピラミッディング条件を満たすため、既存 long に追加します。",
        )
        put("symbol", TradingSymbol.BTC.apiSymbol)
        put("side", "BUY")
        put("type", "MARKET")
        put("size_btc", "0.0010")
        put("protective_stop_price_jpy", "9900000")
        put("take_profit_price_jpy", "10600000")
        put("parent_trade_plan_id", parentTradePlanId)
        put("trade_plan_revision_count", 1)
        put("trade_plan_thesis_ja", "既存仮説の継続に追加します。")
        put("trade_plan_invalidation_conditions_ja", stringArray("直近安値割れ"))
        put(
            "trade_plan_invalidation_predicates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "LAST_PRICE_AT_OR_BELOW")
                        put("threshold_jpy", "9900000")
                    },
                )
            },
        )
        put("trade_plan_target_price_jpy", "10600000")
        put("trade_plan_time_stop_at", "2026-07-02T02:00:00Z")
    }
}

private suspend fun submitApprovedEnterIntent(server: io.modelcontextprotocol.kotlin.sdk.server.Server): String {
    val decisionResult = callTool(server, "submit_decision", enterDecisionArguments())
    val intentId = assertNotNull(decisionResult.structuredContent)
        .getValue("intent_id")
        .jsonPrimitive
        .contentOrNull
        .let { value -> assertNotNull(value) }
    val falsificationResult = callTool(
        server = server,
        toolName = "submit_falsification",
        arguments = buildJsonObject {
            put("intent_id", intentId)
            put("verdict", FalsificationVerdict.APPROVED.name)
            put("llm_provider", "codex")
            put("reason_ja", "preview test approved")
        },
    )

    assertTrue(falsificationResult.isError != true)

    return intentId
}

private fun placeOrderArguments(intentId: String, sizeBtc: String = "0.0050") = buildJsonObject {
    put("intent_id", intentId)
    put("symbol", TradingSymbol.BTC.apiSymbol)
    put("side", "BUY")
    put("type", "MARKET")
    put("size_btc", sizeBtc)
    put("protective_stop_price_jpy", "9700000")
    put("take_profit_price_jpy", "10500000")
    put("estimated_win_probability", "0.73")
    put("reason", "preview test order")
}

private fun closePositionArguments(positionId: String, closeRatio: String? = null) = buildJsonObject {
    put("position_id", positionId)
    closeRatio?.let { value -> put("close_ratio", value) }
    put("reason", "hard halt reduce test")
}

/**
 * TradePlan 正式修正 decision tool request の引数を作る。
 */
private fun tradePlanRevisionDecisionArguments(parentTradePlanId: String, revisionCount: Int) = buildJsonObject {
    put("action", "ADJUST_PROTECTION")
    put("setup_tags", stringArray("breakout", "trend-follow"))
    put("estimated_win_probability", "0.64")
    put("expected_r_multiple", "1.20")
    put("round_trip_cost_r", "0.05")
    put("tool_evidence_ids", stringArray("tool-1", "tool-2"))
    put("fact_check", """{"ticker":true}""")
    put("self_review", """{"reasonsNotToTrade":[]}""")
    put("reason_ja", "否定条件は未成立ですが、保護計画を正式修正します。")
    put("symbol", TradingSymbol.BTC.apiSymbol)
    put("parent_trade_plan_id", parentTradePlanId)
    put("trade_plan_revision_count", revisionCount)
    put("trade_plan_thesis_ja", "上昇継続の仮説は維持します。")
    put("trade_plan_invalidation_conditions_ja", stringArray("直近安値割れ"))
    put("trade_plan_target_price_jpy", "10400000")
    put("trade_plan_time_stop_at", "2026-07-02T02:00:00Z")
}

/**
 * failed llm_run fixture を作る。
 */
private fun failedLlmRun(invocationId: String): LlmRunFinish {
    return LlmRunFinish(
        invocationId = invocationId,
        mode = TradingMode.PAPER,
        symbol = TradingSymbol.BTC,
        triggerKind = null,
        status = LLM_RUN_STATUS_FAILED,
        startedAt = fixedInstant().minusSeconds(5),
        finishedAt = fixedInstant(),
        errorMessage = "redacted failure",
    )
}

private suspend fun insertFailedLlmRun(repository: LlmRunRepository, invocationId: String) {
    val finish = failedLlmRun(invocationId)
    repository.insertRunning(
        LlmRunStart(
            invocationId = finish.invocationId,
            mode = finish.mode,
            symbol = finish.symbol,
            triggerKind = finish.triggerKind,
            startedAt = finish.startedAt,
        ),
    ).getOrThrow()
    repository.finish(finish).getOrThrow()
}

private suspend fun callTool(
    server: io.modelcontextprotocol.kotlin.sdk.server.Server,
    toolName: String,
    arguments: JsonObject = buildJsonObject {},
): CallToolResult {
    val request = CallToolRequest(
        params = CallToolRequestParams(
            name = toolName,
            arguments = arguments,
        ),
    )

    return server.tools.getValue(toolName).handler.invoke(TestClientConnection, request)
}

private fun assertFreshness(
    structuredContent: JsonObject,
    fetchedAt: String,
    sourceTimestamp: String?,
    stalenessMs: Long,
    staleAfterMs: Long,
    stale: Boolean,
    source: String,
) {
    val freshness = structuredContent.getValue("freshness").jsonObject

    assertEquals(fetchedAt, freshness.getValue("fetchedAt").jsonPrimitive.contentOrNull)
    if (sourceTimestamp == null) {
        assertNull(freshness.getValue("sourceTimestamp").jsonPrimitive.contentOrNull)
    } else {
        assertEquals(sourceTimestamp, freshness.getValue("sourceTimestamp").jsonPrimitive.contentOrNull)
    }
    assertEquals(stalenessMs, freshness.getValue("stalenessMs").jsonPrimitive.longOrNull)
    assertEquals(staleAfterMs, freshness.getValue("staleAfterMs").jsonPrimitive.longOrNull)
    assertEquals(stale, freshness.getValue("stale").jsonPrimitive.booleanOrNull)
    assertEquals(source, freshness.getValue("source").jsonPrimitive.contentOrNull)
}

/**
 * JSON string array を作る。
 */
private fun stringArray(vararg values: String) = buildJsonArray {
    values.forEach { value -> add(value) }
}

/**
 * append に必ず失敗する command_event_log。
 */
private object FailingCommandEventLog : CommandEventLog {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        return Result.failure(IllegalStateException("audit append failed"))
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }
}

/**
 * tool call count だけに失敗する command_event_log。
 *
 * @param delegate append の保存先
 */
private class CountFailingCommandEventLog(
    private val delegate: InMemoryCommandEventLog,
) : CommandEventLog {
    override suspend fun append(event: CommandEvent): Result<Unit> {
        return delegate.append(event)
    }

    override suspend fun countDistinctLlmLaunchesSince(since: Instant, excludedInvocationId: String?): Result<Int> {
        return delegate.countDistinctLlmLaunchesSince(since, excludedInvocationId)
    }

    override suspend fun countToolCallEvents(decisionRunId: String, toolNames: Set<String>): Result<Int> {
        return Result.failure(IllegalStateException("audit count failed"))
    }

    suspend fun events(): List<CommandEvent> {
        return delegate.events()
    }
}

/**
 * account tool が snapshot 付き読み取りだけを使うことを検証する fake broker。
 *
 * @param balance get_balance 用の snapshot と更新時刻
 * @param accountStatus get_account_status 用の status と更新時刻
 * @param positions get_positions 用の一覧と更新時刻
 * @param openOrders get_open_orders 用の一覧と更新時刻
 */
private class SnapshotOnlyBroker(
    private val balance: AccountSnapshotWithUpdatedAt,
    private val accountStatus: AccountStatusWithUpdatedAt,
    private val positions: PositionsWithUpdatedAt = PositionsWithUpdatedAt(
        positions = emptyList(),
        updatedAt = fixedInstant(),
    ),
    private val openOrders: OpenOrdersWithUpdatedAt = OpenOrdersWithUpdatedAt(
        openOrders = emptyList(),
        updatedAt = fixedInstant(),
    ),
) : Broker {

    override suspend fun getBalance(): Result<AccountSnapshot> {
        return Result.failure(UnsupportedOperationException("getBalance must not be used for account freshness."))
    }

    override suspend fun getBalanceWithUpdatedAt(): Result<AccountSnapshotWithUpdatedAt> {
        return Result.success(balance)
    }

    override suspend fun getPositions(): Result<List<Position>> {
        return Result.failure(UnsupportedOperationException("getPositions must not be used for account freshness."))
    }

    override suspend fun getPositionsWithUpdatedAt(): Result<PositionsWithUpdatedAt> {
        return Result.success(positions)
    }

    override suspend fun getOpenOrders(): Result<List<Order>> {
        return Result.failure(UnsupportedOperationException("getOpenOrders must not be used for account freshness."))
    }

    override suspend fun getOpenOrdersWithUpdatedAt(): Result<OpenOrdersWithUpdatedAt> {
        return Result.success(openOrders)
    }

    override suspend fun getAccountStatus(): Result<AccountStatus> {
        return Result.failure(UnsupportedOperationException("getAccountStatus must not be used for account freshness."))
    }

    override suspend fun getAccountStatusWithUpdatedAt(): Result<AccountStatusWithUpdatedAt> {
        return Result.success(accountStatus)
    }

    override suspend fun placeOrder(command: PlaceOrderCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun previewOrder(command: PlaceOrderCommand): Result<PreviewOrderResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun closePosition(command: ClosePositionCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun updateProtection(command: UpdateProtectionCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun cancelOrder(command: CancelOrderCommand): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun reconcile(tickSnapshot: TickSnapshot): Result<PaperReconcileResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }

    override suspend fun sweepHardHalt(reasonJa: String, tickSnapshot: TickSnapshot): Result<PaperTradeResult> {
        return Result.failure(UnsupportedOperationException("not used"))
    }
}

private fun accountSnapshot(totalEquityJpy: String = "100000.00000000"): AccountSnapshot {
    return AccountSnapshot(
        mode = TradingMode.PAPER,
        cashJpy = totalEquityJpy,
        initialCashJpy = "100000.00000000",
        btcQuantity = "0.000000000000",
        btcMarkPriceJpy = "0.00000000",
        totalEquityJpy = totalEquityJpy,
        equityPeakJpy = totalEquityJpy,
        drawdownRatio = "0.00000000",
    )
}

private fun accountStatus(currentEquityJpy: String = "100000.00000000"): AccountStatus {
    return AccountStatus(
        mode = TradingMode.PAPER,
        riskState = "RUNNING",
        drawdownRatio = "0.00000000",
        hardHalt = false,
        currentEquityJpy = currentEquityJpy,
        todayRealizedPnlJpy = "0.00000000",
        protectionStatus = ProtectionStatus(
            protectedPositionCount = 0,
            unprotectedPositionCount = 0,
            orphanStopCount = 0,
            orphanTakeProfitCount = 0,
            pendingCancelCount = 0,
            lastReconciledAt = null,
            lastMarketDataAt = null,
            tradingLockOwner = null,
        ),
    )
}

/**
 * tool handler の receiver を満たす test 用 connection。
 */
private object TestClientConnection : ClientConnection {
    override val sessionId: String = "test-session"

    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) = Unit

    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult {
        return unsupported()
    }

    override suspend fun createMessage(request: CreateMessageRequest, options: RequestOptions?): CreateMessageResult {
        return unsupported()
    }

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult {
        return unsupported()
    }

    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult {
        return unsupported()
    }

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult {
        return unsupported()
    }

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult {
        return unsupported()
    }

    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit

    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit

    override suspend fun sendResourceListChanged() = Unit

    override suspend fun sendToolListChanged() = Unit

    override suspend fun sendPromptListChanged() = Unit

    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) = Unit

    private fun <ResultType> unsupported(): ResultType {
        error("TestClientConnection does not support client callbacks.")
    }
}

/**
 * MCP server unit test 用の fake market data source。
 */
private object FakeMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "100",
                bid = "99",
                ask = "101",
                high = "110",
                low = "90",
                volume = "1.0",
                timestamp = "2026-07-01T00:00:00Z",
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(
            listOf(
                Candle(
                    symbol = symbol.apiSymbol,
                    interval = interval,
                    openTime = "2026-07-01T00:00:00Z",
                    open = "100",
                    high = "110",
                    low = "90",
                    close = "105",
                    volume = "1.0",
                ),
            ),
        )
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel("99", "0.1")),
                asks = listOf(OrderbookLevel("101", "0.1")),
            ),
        )
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(
            listOf(
                RecentTrade(
                    symbol = symbol.apiSymbol,
                    price = "100",
                    size = "0.01",
                    side = TradeSide.BUY,
                    timestamp = "2026-07-01T00:00:00Z",
                ),
            ),
        )
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

/**
 * preview_order test 用の高価格帯 fake market data source。
 */
private object PreviewMarketDataSource : MarketDataSource {
    override suspend fun getTicker(symbol: TradingSymbol): Result<Ticker> {
        return Result.success(
            Ticker(
                symbol = symbol.apiSymbol,
                last = "10000000",
                bid = "9990000",
                ask = "10000000",
                high = "10100000",
                low = "9900000",
                volume = "1.0",
                timestamp = fixedInstant().toString(),
            ),
        )
    }

    override suspend fun getCandles(
        symbol: TradingSymbol,
        interval: CandleInterval,
        limit: Int,
    ): Result<List<Candle>> {
        return Result.success(emptyList())
    }

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        return Result.success(
            Orderbook(
                symbol = symbol.apiSymbol,
                bids = listOf(OrderbookLevel("9990000", "0.1")),
                asks = listOf(OrderbookLevel("10000000", "0.1")),
            ),
        )
    }

    override suspend fun getTrades(symbol: TradingSymbol, limit: Int): Result<List<RecentTrade>> {
        return Result.success(emptyList())
    }

    override suspend fun getSymbolRules(symbol: TradingSymbol): Result<SymbolRules> {
        return Result.success(
            SymbolRules(
                symbol = symbol.apiSymbol,
                minOrderSize = "0.0001",
                sizeStep = "0.0001",
                tickSize = "1",
                takerFee = "0.0005",
                makerFee = "-0.0001",
            ),
        )
    }
}

private class CorrelationAuditFailingMarketDataSource : MarketDataSource by PreviewMarketDataSource {
    var correlation: GmoPublicRequestCorrelation? = null
        private set

    override suspend fun getOrderbook(symbol: TradingSymbol, depth: Int): Result<Orderbook> {
        correlation = currentCoroutineContext()[GmoPublicRequestCorrelation]

        return Result.failure(GmoRequestAuditException())
    }
}

private fun fixedClock(): Clock {
    return Clock.fixed(fixedInstant(), ZoneOffset.UTC)
}

private fun largeSafetyDenial(
    index: Int,
    rawSecret: String,
    deniedAt: Instant,
): DecisionRunSafetyDenial {
    val largeText = "あ".repeat(400)

    return DecisionRunSafetyDenial(
        invocationId = "denial-$index",
        deniedAt = deniedAt,
        finalReason = "preview_order_rejected",
        decision = DecisionRunDecision(
            decisionId = "decision-$index",
            action = "ENTER",
            provider = "claude",
            estimatedWinProbability = "0.50",
            expectedRMultiple = "0.11",
            roundTripCostR = "0.01",
            reasonJa = largeText,
            setupTagsJson = "[\"$largeText\",\"$largeText\",\"$largeText\",\"$largeText\",\"$largeText\"]",
            missingDataJaJson = "[]",
            noTradeConditionsJaJson = "[]",
            createdAt = deniedAt,
        ),
        intent = DecisionRunIntent(
            intentId = "intent-$index",
            tradePlanId = "plan-$index",
            parentTradePlanId = null,
            revisionCount = 0,
            side = "BUY",
            orderType = "LIMIT",
            sizeBtc = "0.001",
            priceJpy = "10000000",
            protectiveStopPriceJpy = "9900000",
            takeProfitPriceJpy = "10500000",
            thesisJa = largeText,
            invalidationConditionsJaJson = "[]",
            targetPriceJpy = "10500000",
            timeStopAt = null,
            setupTagsJson = null,
        ),
        falsification = DecisionRunFalsification(
            verdict = "APPROVED",
            provider = "codex",
            reasonJa = "approved",
            createdAt = deniedAt,
        ),
        safetyViolation = DecisionRunSafetyViolation(
            rule = "EXPECTED_VALUE_GATE",
            measuredValue = "$rawSecret $largeText",
            limitValue = largeText,
            messageJa = "$rawSecret $largeText",
            createdAt = deniedAt,
        ),
    )
}

private fun fixedInstant(): Instant {
    return Instant.parse("2026-07-04T12:00:00Z")
}
