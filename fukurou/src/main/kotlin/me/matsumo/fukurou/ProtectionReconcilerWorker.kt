package me.matsumo.fukurou

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.matsumo.fukurou.trading.persistence.ExposedCommandEventLog
import me.matsumo.fukurou.trading.persistence.ExposedRiskStateRepository
import me.matsumo.fukurou.trading.persistence.PostgresGlobalTradingLock
import me.matsumo.fukurou.trading.persistence.TradingPersistenceBootstrap
import me.matsumo.fukurou.trading.reconciler.EmptyTickStream
import me.matsumo.fukurou.trading.reconciler.MutableReconcilerStatus
import me.matsumo.fukurou.trading.reconciler.ProtectionReconciler
import java.time.Clock
import java.time.Duration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * ProtectionReconciler loop の既定実行間隔。
 */
private val DEFAULT_RECONCILER_INTERVAL = Duration.ofSeconds(5)

/**
 * Ktor backend 上で ProtectionReconciler を常駐起動する worker。
 *
 * @param reconciler 実行する ProtectionReconciler
 * @param interval loop 間隔
 * @param scope worker coroutine scope
 */
class ProtectionReconcilerWorker(
    private val reconciler: ProtectionReconciler,
    private val interval: Duration = DEFAULT_RECONCILER_INTERVAL,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private var job: Job? = null

    /**
     * worker loop を開始する。
     */
    fun start(): ProtectionReconcilerWorker {
        require(job == null) { "ProtectionReconcilerWorker is already started." }

        job = scope.launch {
            reconciler.runLoop(interval)
        }

        return this
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }
}

/**
 * DB runtime から ProtectionReconcilerWorker を構築して起動する。
 */
internal fun startProtectionReconcilerWorker(
    dataSource: HikariDataSource,
    database: ExposedDatabase,
    status: MutableReconcilerStatus,
    clock: Clock = Clock.systemUTC(),
): ProtectionReconcilerWorker {
    TradingPersistenceBootstrap(database, clock).ensureSchema().getOrThrow()

    val riskStateRepository = ExposedRiskStateRepository(database, clock)
    val commandEventLog = ExposedCommandEventLog(database)
    val tradingLock = PostgresGlobalTradingLock(dataSource, clock)
    val reconciler = ProtectionReconciler(
        riskStateRepository = riskStateRepository,
        commandEventLog = commandEventLog,
        tradingLock = tradingLock,
        tickStream = EmptyTickStream,
        status = status,
        clock = clock,
    )

    return ProtectionReconcilerWorker(reconciler = reconciler).start()
}
