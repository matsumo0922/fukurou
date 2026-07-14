package me.matsumo.fukurou.trading.exchange.gmo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.matsumo.fukurou.trading.domain.TradingSymbol
import me.matsumo.fukurou.trading.market.DurableIngressGapSource
import me.matsumo.fukurou.trading.market.DurableMarketEventIngress
import me.matsumo.fukurou.trading.market.IngressOperationDeadline
import me.matsumo.fukurou.trading.market.MarketStreamIdentity
import me.matsumo.fukurou.trading.market.PaperMarketTradeEvent
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GmoDurablePublicWebSocketMarketEventStreamTest {
    @Test
    fun openStaysPausedUntilStartingSubscribeAndConnectedCommits() = runBlocking {
        val fixture = ListenerFixture()

        fixture.start()
        await { fixture.socket.requestCount == 1L }

        assertEquals(listOf("begin", "activate"), fixture.ingress.calls)
        assertEquals(1, fixture.socket.sentTexts.size)
        assertFalse(fixture.socket.aborted)
    }

    @Test
    fun terminalRaceAtBeginSubscribeAndActivatePublishesNoLateDemand() = runBlocking {
        listOf(StartupRace.BEGIN, StartupRace.SUBSCRIBE, StartupRace.ACTIVATE).forEach { race ->
            val fixture = ListenerFixture(race = race)
            fixture.start()
            await { fixture.reachedRaceBoundary() }

            fixture.listener.onError(fixture.socket, IllegalStateException("terminal race"))
            fixture.completeRaceBoundary()
            await { fixture.ingress.disconnectCount == 1 }

            assertEquals(0, fixture.socket.requestCount, race.name)
            assertEquals(1, fixture.ingress.disconnectCount, race.name)
        }
    }

    @Test
    fun f1TerminalAtBeginSeamStartsNoDatabaseLifecycle() = runBlocking {
        val fixture = ListenerFixture(racePoint = DurableListenerRacePoint.F1_BEGIN)

        fixture.start()
        fixture.listener.awaitTermination()

        assertEquals(emptyList(), fixture.ingress.calls)
        assertEquals(0, fixture.socket.sentTexts.size)
        assertEquals(0, fixture.socket.requestCount)
    }

    @Test
    fun f1StartedBeginDelaysTerminationUntilDatabaseDisconnectCompletes() = runBlocking {
        val disconnect = CompletableDeferred<Result<Unit>>()
        val fixture = ListenerFixture(
            race = StartupRace.BEGIN,
            disconnectDeferred = disconnect,
        )
        val session = GmoDurableMarketEventSession(fixture.socket, fixture.events, fixture.scope, fixture.listener)
        fixture.start()
        await { "begin" in fixture.ingress.calls }

        session.close()
        assertTrue(fixture.scope.isActive)
        assertEquals(0, fixture.ingress.disconnectCount)

        fixture.completeRaceBoundary()
        await { fixture.ingress.disconnectCount == 1 }
        assertTrue(fixture.scope.isActive)

        disconnect.complete(Result.success(Unit))
        await { !fixture.scope.isActive }
    }

    @Test
    fun f2TerminalAtSubscribeSeamSendsNoSubscription() = runBlocking {
        val fixture = ListenerFixture(racePoint = DurableListenerRacePoint.F2_SUBSCRIBE)

        fixture.start()
        fixture.listener.awaitTermination()

        assertEquals(listOf("begin", "disconnect:TRANSPORT_ERROR"), fixture.ingress.calls)
        assertEquals(0, fixture.socket.sentTexts.size)
        assertEquals(0, fixture.socket.requestCount)
    }

    @Test
    fun f3TerminalAtActivateSeamPublishesNoInitialDemand() = runBlocking {
        val fixture = ListenerFixture(racePoint = DurableListenerRacePoint.F3_ACTIVATE)

        fixture.start()
        fixture.listener.awaitTermination()

        assertEquals(listOf("begin", "activate", "disconnect:TRANSPORT_ERROR"), fixture.ingress.calls)
        assertEquals(0, fixture.socket.requestCount)
    }

    @Test
    fun f4TerminalAtDecodeSeamDecodesAndRegistersNothing() = runBlocking {
        val fixture = ListenerFixture(racePoint = DurableListenerRacePoint.F4_DECODE)
        fixture.startConnected()

        fixture.listener.onText(fixture.socket, tradePayload(1), true)
        fixture.listener.awaitTermination()

        assertEquals(0, fixture.ingress.calls.count { it.startsWith("register:") })
        assertTrue(fixture.events.tryReceive().isFailure)
        assertEquals(1, fixture.socket.requestCount)
    }

    @Test
    fun f5TerminalAtRegisterSeamPublishesNoEventOrDemand() = runBlocking {
        val fixture = ListenerFixture(racePoint = DurableListenerRacePoint.F5_REGISTER)
        fixture.startConnected()

        fixture.listener.onText(fixture.socket, tradePayload(1), true)
        fixture.listener.awaitTermination()

        assertEquals(1, fixture.ingress.calls.count { it.startsWith("register:") })
        assertTrue(fixture.events.tryReceive().isFailure)
        assertEquals(1, fixture.socket.requestCount)
    }

    @Test
    fun f6TerminalAtPongAndDemandSeamsPublishesNoLateDemand() = runBlocking {
        listOf(1, 2).forEach { occurrence ->
            val fixture = ListenerFixture(
                racePoint = DurableListenerRacePoint.F6_PING,
                raceOccurrence = occurrence,
            )
            fixture.startConnected()

            fixture.listener.onPing(fixture.socket, ByteBuffer.allocate(0))
            fixture.listener.awaitTermination()

            assertEquals(if (occurrence == 1) 0 else 1, fixture.socket.pongCount, "occurrence=$occurrence")
            assertEquals(1, fixture.socket.requestCount, "occurrence=$occurrence")
        }
    }

    @Test
    fun errorBeforeStartingClaimsTerminalWithoutDatabaseBegin() {
        val fixture = ListenerFixture()

        fixture.listener.onError(fixture.socket, IllegalStateException("early error"))
        fixture.start()

        assertTrue(fixture.socket.aborted)
        assertEquals(emptyList(), fixture.ingress.calls)
    }

    @Test
    fun fragmentedTextRequestsNextFragmentAndRegistersExactlyOnce() = runBlocking {
        val fixture = ListenerFixture()
        fixture.startConnected()
        val payload = tradePayload(1)

        fixture.listener.onText(fixture.socket, payload.take(40), false)
        assertEquals(2, fixture.socket.requestCount)
        fixture.listener.onText(fixture.socket, payload.drop(40), true)
        await { "register:1" in fixture.ingress.calls }

        assertEquals(1, fixture.ingress.calls.count { it.startsWith("register:") })
        assertEquals(3, fixture.socket.requestCount)
    }

    @Test
    fun registrationFalseAndExceptionFailClosedWithoutPublishing() = runBlocking {
        listOf(RegisterOutcome.FALSE, RegisterOutcome.FAILURE).forEach { outcome ->
            val fixture = ListenerFixture(registerOutcome = outcome)
            fixture.startConnected()

            fixture.listener.onText(fixture.socket, tradePayload(1), true)
            await { fixture.ingress.disconnectCount == 1 }

            assertTrue(fixture.events.tryReceive().isFailure, outcome.name)
            assertEquals(1, fixture.socket.requestCount, outcome.name)
        }
    }

    @Test
    fun decodeFailureAndLatePingCompletionPublishNoDemand() = runBlocking {
        val decodeFixture = ListenerFixture()
        decodeFixture.startConnected()
        decodeFixture.listener.onText(decodeFixture.socket, "{", true)
        await { decodeFixture.ingress.disconnectCount == 1 }
        assertEquals(1, decodeFixture.socket.requestCount)

        val pingFixture = ListenerFixture(deferPong = true)
        pingFixture.startConnected()
        pingFixture.listener.onPing(pingFixture.socket, ByteBuffer.allocate(0))
        pingFixture.listener.onError(pingFixture.socket, IllegalStateException("ping race"))
        pingFixture.socket.sendPongDeferred?.complete(pingFixture.socket)
        await { pingFixture.ingress.disconnectCount == 1 }
        assertEquals(1, pingFixture.socket.requestCount)
    }

    @Test
    fun terminalCallbacksDisconnectExactlyOnceAndCloseWaitsForDisconnect() = runBlocking {
        val disconnect = CompletableDeferred<Result<Unit>>()
        val fixture = ListenerFixture(disconnectDeferred = disconnect)
        fixture.startConnected()
        val session = GmoDurableMarketEventSession(fixture.socket, fixture.events, fixture.scope, fixture.listener)

        session.close()
        fixture.listener.onClose(fixture.socket, 1006, "closed")
        fixture.listener.onError(fixture.socket, IllegalStateException("duplicate"))
        await { fixture.ingress.disconnectCount == 1 }

        assertTrue(fixture.scope.isActive)
        disconnect.complete(Result.success(Unit))
        await { !fixture.scope.isActive }
        assertEquals(1, fixture.ingress.disconnectCount)
    }

    @Test
    fun f8TerminationCompletionOccursAfterDatabaseDisconnect() = runBlocking {
        val disconnect = CompletableDeferred<Result<Unit>>()
        val fixture = ListenerFixture(
            disconnectDeferred = disconnect,
            racePoint = DurableListenerRacePoint.F8_TERMINATION,
        )
        fixture.startConnected()
        val session = GmoDurableMarketEventSession(fixture.socket, fixture.events, fixture.scope, fixture.listener)

        session.close()
        await { fixture.ingress.disconnectCount == 1 }
        assertTrue(fixture.scope.isActive)

        disconnect.complete(Result.success(Unit))
        await { !fixture.scope.isActive }

        assertEquals(1, fixture.ingress.disconnectCount)
        assertEquals(1, fixture.raceSeam.invocationCount)
    }

    @Test
    fun registrationAllowsOnlyOneInFlightAndIgnoresLateCompletionAfterTerminal() = runBlocking {
        val registration = CompletableDeferred<Result<Boolean>>()
        val fixture = ListenerFixture(registerDeferred = registration)
        fixture.startConnected()

        fixture.listener.onText(fixture.socket, tradePayload(1), true)
        await { fixture.ingress.registrationInFlight == 1 }
        fixture.listener.onText(fixture.socket, tradePayload(2), true)
        fixture.listener.onError(fixture.socket, IllegalStateException("late completion"))
        registration.complete(Result.success(true))
        await { fixture.ingress.disconnectCount == 1 }

        assertEquals(1, fixture.ingress.maxRegistrationInFlight)
        assertEquals(1, fixture.ingress.calls.count { it.startsWith("register:") })
        assertTrue(fixture.events.tryReceive().isFailure)
        assertEquals(1, fixture.socket.requestCount)
    }

    @Test
    fun bufferAccepts1024EventsAndFailsClosedOn1025th() = runBlocking {
        val fixture = ListenerFixture()
        fixture.startConnected()

        repeat(1_025) { index ->
            fixture.listener.onText(fixture.socket, tradePayload(index + 1), true)
            if (index < 1_024) await { fixture.socket.requestCount == index + 2L }
        }
        await { fixture.ingress.disconnectCount == 1 }

        assertEquals(1_025, fixture.ingress.calls.count { it.startsWith("register:") })
        assertEquals(1_025, fixture.socket.requestCount)
        assertTrue("disconnect:BACKPRESSURE" in fixture.ingress.calls)
    }

    private suspend fun ListenerFixture.startConnected() {
        start()
        await { socket.requestCount == 1L }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(1)
        }
    }
}

private class ListenerFixture(
    race: StartupRace? = null,
    registerOutcome: RegisterOutcome = RegisterOutcome.SUCCESS,
    registerDeferred: CompletableDeferred<Result<Boolean>>? = null,
    disconnectDeferred: CompletableDeferred<Result<Unit>>? = null,
    deferPong: Boolean = false,
    racePoint: DurableListenerRacePoint? = null,
    raceOccurrence: Int = 1,
) {
    private val beginDeferred = if (race == StartupRace.BEGIN) CompletableDeferred<Result<Unit>>() else null
    private val activateDeferred = if (race == StartupRace.ACTIVATE) CompletableDeferred<Result<Boolean>>() else null
    val ingress = RecordingDurableIngress(
        beginDeferred,
        activateDeferred,
        registerOutcome,
        registerDeferred,
        disconnectDeferred,
    )
    val socket = RecordingWebSocket(race == StartupRace.SUBSCRIBE, deferPong)
    val events = Channel<PaperMarketTradeEvent>(1_024)
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val raceSeam = InjectingRaceSeam(racePoint, raceOccurrence)
    val listener = GmoDurableWebSocketListener(
        sessionId = UUID.randomUUID(),
        identity = MarketStreamIdentity("GMO_COIN", "BTC_JPY", "TRADES"),
        ingress = ingress,
        decoder = GmoTradeMessageDecoder(TradingSymbol.BTC, UUID.randomUUID()),
        events = events,
        scope = scope,
        clock = Clock.systemUTC(),
        raceSeam = raceSeam,
    )

    init {
        raceSeam.action = { listener.onError(socket, IllegalStateException("injected terminal race")) }
    }

    fun start() = listener.start(socket, "subscription")

    fun reachedRaceBoundary(): Boolean = when {
        beginDeferred != null -> "begin" in ingress.calls
        socket.sendTextDeferred != null -> socket.sentTexts.isNotEmpty()
        activateDeferred != null -> "activate" in ingress.calls
        else -> false
    }

    fun completeRaceBoundary() {
        beginDeferred?.complete(Result.success(Unit))
        socket.sendTextDeferred?.complete(socket)
        activateDeferred?.complete(Result.success(true))
    }
}

private class InjectingRaceSeam(
    private val target: DurableListenerRacePoint?,
    private val targetOccurrence: Int,
) : DurableListenerRaceSeam {
    lateinit var action: () -> Unit
    var invocationCount = 0
        private set

    override fun before(point: DurableListenerRacePoint) {
        if (point != target) return
        invocationCount += 1
        if (invocationCount == targetOccurrence) action()
    }
}

/** startup callbackの競合注入位置。 */
private enum class StartupRace { BEGIN, SUBSCRIBE, ACTIVATE }

/** registerReceivedのfake結果。 */
private enum class RegisterOutcome { SUCCESS, FALSE, FAILURE }

private class RecordingDurableIngress(
    private val beginDeferred: CompletableDeferred<Result<Unit>>? = null,
    private val activateDeferred: CompletableDeferred<Result<Boolean>>? = null,
    private val registerOutcome: RegisterOutcome = RegisterOutcome.SUCCESS,
    private val registerDeferred: CompletableDeferred<Result<Boolean>>? = null,
    private val disconnectDeferred: CompletableDeferred<Result<Unit>>? = null,
) : DurableMarketEventIngress {
    val calls = CopyOnWriteArrayList<String>()
    private val registrations = AtomicInteger(0)
    private val maxRegistrations = AtomicInteger(0)
    val disconnectCount: Int get() = calls.count { it.startsWith("disconnect:") }
    val registrationInFlight: Int get() = registrations.get()
    val maxRegistrationInFlight: Int get() = maxRegistrations.get()

    override suspend fun begin(
        sessionId: UUID,
        identity: MarketStreamIdentity,
        deadline: IngressOperationDeadline,
    ): Result<Unit> {
        calls += "begin"
        return beginDeferred?.await() ?: Result.success(Unit)
    }

    override suspend fun activate(sessionId: UUID, deadline: IngressOperationDeadline): Result<Boolean> {
        calls += "activate"
        return activateDeferred?.await() ?: Result.success(true)
    }

    override suspend fun registerReceived(
        sessionId: UUID,
        sequence: Long,
        deadline: IngressOperationDeadline,
    ): Result<Boolean> {
        calls += "register:$sequence"
        val inFlight = registrations.incrementAndGet()
        maxRegistrations.getAndUpdate { current -> maxOf(current, inFlight) }
        return try {
            registerDeferred?.await() ?: when (registerOutcome) {
                RegisterOutcome.SUCCESS -> Result.success(true)
                RegisterOutcome.FALSE -> Result.success(false)
                RegisterOutcome.FAILURE -> Result.failure(IllegalStateException("registration failed"))
            }
        } finally {
            registrations.decrementAndGet()
        }
    }

    override suspend fun disconnect(
        sessionId: UUID,
        source: DurableIngressGapSource,
        deadline: IngressOperationDeadline,
    ): Result<Unit> {
        calls += "disconnect:$source"
        return disconnectDeferred?.await() ?: Result.success(Unit)
    }
}

private class RecordingWebSocket(deferSendText: Boolean = false, deferPong: Boolean = false) : WebSocket {
    val sentTexts = CopyOnWriteArrayList<String>()
    val sendTextDeferred = if (deferSendText) CompletableFuture<WebSocket>() else null
    val sendPongDeferred = if (deferPong) CompletableFuture<WebSocket>() else null
    @Volatile var requestCount = 0L
    @Volatile var aborted = false
    @Volatile var pongCount = 0

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        sentTexts += data.toString()
        return sendTextDeferred ?: completed()
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = completed()

    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = completed()

    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> {
        pongCount += 1
        return sendPongDeferred ?: completed()
    }

    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> = completed()

    override fun request(n: Long) {
        requestCount += n
    }

    override fun getSubprotocol(): String = ""

    override fun isOutputClosed(): Boolean = aborted

    override fun isInputClosed(): Boolean = aborted

    override fun abort() {
        aborted = true
    }

    private fun completed(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
}

private fun tradePayload(sequence: Int): String {
    val second = "%02d".format(sequence % 60)
    return """{"channel":"trades","symbol":"BTC","side":"BUY","price":"10000000","size":"0.01","timestamp":"2026-01-01T00:00:${second}Z"}"""
}
