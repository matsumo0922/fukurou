package me.matsumo.fukurou.trading.runner

import kotlinx.coroutines.CancellationException

/** Standard material 構築に失敗した境界を表す安定した stage。 */
enum class StandardMaterialFailureStage {
    ACCOUNT_SNAPSHOT,
    MARKET_DATA_SOURCE,
    TICKER,
    CANDLES,
    ORDERBOOK,
    INDICATOR_PROJECTION,
    CANONICAL_HASH,
    MATERIAL_PERSISTENCE,
    RUN_MANIFEST_PERSISTENCE,
}

/** Standard material の失敗を raw payload や例外 message に依存せず stage で伝える例外。 */
class StandardMaterialFailure(
    val stage: StandardMaterialFailureStage,
    cause: Throwable,
) : RuntimeException("Standard material is unavailable at ${stage.name}.", cause)

internal suspend fun <T> withStandardMaterialStage(stage: StandardMaterialFailureStage, block: suspend () -> T): T {
    return try {
        block()
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (failure: StandardMaterialFailure) {
        throw failure
    } catch (throwable: Throwable) {
        throw StandardMaterialFailure(stage, throwable)
    }
}

internal fun <T> withStandardMaterialValueStage(stage: StandardMaterialFailureStage, block: () -> T): T {
    return try {
        block()
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (failure: StandardMaterialFailure) {
        throw failure
    } catch (throwable: Throwable) {
        throw StandardMaterialFailure(stage, throwable)
    }
}
