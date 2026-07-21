package me.matsumo.fukurou.trading.replay

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.matsumo.fukurou.trading.persistence.executeUpdate
import me.matsumo.fukurou.trading.runtime.TradingDatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** replay 対象期間。`toExclusive` は上端を含まない。 */
data class ReplayWindow(
    val fromMs: Long,
    val toExclusiveMs: Long,
) {
    init {
        require(fromMs < toExclusiveMs) { "replay window requires fromMs < toExclusiveMs." }
    }
}

/** replay の実行境界を決める設定。対象件数上限・statement timeout・短縮 TTL 候補を持つ。 */
data class ReplayBounds(
    val window: ReplayWindow,
    val candidateTtlSeconds: List<Long>,
    val maxTargets: Int,
    val statementTimeoutSeconds: Int,
) {
    init {
        require(candidateTtlSeconds.isNotEmpty()) { "at least one candidate TTL is required." }
        require(candidateTtlSeconds.all { seconds -> seconds > 0 }) { "candidate TTL seconds must be positive." }
        require(maxTargets > 0) { "maxTargets must be positive." }
        require(statementTimeoutSeconds > 0) { "statementTimeoutSeconds must be positive." }
    }
}

/** replay run を打ち切らず全体失敗させるための例外。部分結果を出さない。 */
class ReplayRunFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * replay 専用の read-only 接続と、単一の REPEATABLE READ read-only snapshot transaction を提供する runtime。
 *
 * production の書き込み経路 (`ExposedPaperLedgerWriter` / `PaperBroker`) を依存に含めない。
 */
class ReplayReadOnlyRuntime private constructor(
    private val ownedDataSource: HikariDataSource?,
    private val database: ExposedDatabase,
) : AutoCloseable {

    /**
     * 全入力を単一の read-only snapshot で読む。query 間で異なる snapshot を混ぜない。
     *
     * `statement_timeout` を transaction 内に設定し、上限超過を run 失敗として扱う。
     */
    fun <T> readInSingleSnapshot(statementTimeoutSeconds: Int, block: JdbcTransaction.() -> T): T {
        return transaction(
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
            db = database,
        ) {
            executeUpdate("SET LOCAL statement_timeout='${statementTimeoutSeconds}s'")
            block()
        }
    }

    override fun close() {
        ownedDataSource?.close()
    }

    companion object {
        private const val READ_ONLY_POOL_SIZE = 2

        /** read-only role の credential を前提に、write 権を持たない DataSource を組んで runtime を作る。 */
        fun fromDatabaseConfig(config: TradingDatabaseConfig): ReplayReadOnlyRuntime {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = READ_ONLY_POOL_SIZE
                isReadOnly = true
                isAutoCommit = false
                connectionInitSql = "SET default_transaction_read_only = on"
            }
            val dataSource = HikariDataSource(hikariConfig)
            val database = ExposedDatabase.connect(dataSource)

            return ReplayReadOnlyRuntime(dataSource, database)
        }

        /** 既存 Exposed database から runtime を作る。回帰テストが in-process の database を渡すために使う。 */
        fun fromDatabase(database: ExposedDatabase): ReplayReadOnlyRuntime {
            return ReplayReadOnlyRuntime(ownedDataSource = null, database = database)
        }
    }
}
