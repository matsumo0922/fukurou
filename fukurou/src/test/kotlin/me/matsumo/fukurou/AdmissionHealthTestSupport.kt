package me.matsumo.fukurou

import kotlinx.coroutines.delay
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealth
import me.matsumo.fukurou.trading.daemon.LlmExecutionAdmissionHealthTestFixture
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

/** admission-dependent test method の前後で process-global health を完全初期化する。 */
internal fun resetAdmissionHealthForTest() {
    LlmExecutionAdmissionHealthTestFixture.reset()
}

/** runtime config rejection と無関係な execution admission が healthy であることを確認する。 */
internal fun assertAdmissionHealthyForTest() {
    assertTrue(LlmExecutionAdmissionHealth.isHealthy(), "LLM execution admission must be healthy for this assertion.")
}

/** ApplicationStopped subscriber が通知する cleanup 結果を保持する test observer。 */
internal class ApplicationShutdownResultCapture {
    private val result = AtomicReference<Result<Unit>>()

    /** module へ渡す cleanup result observer。 */
    val observer: (Result<Unit>) -> Unit = result::set

    /** application scope 終了後に cleanup success を検証する。 */
    fun assertSucceeded() {
        val captured = checkNotNull(result.get()) { "Application shutdown result was not observed." }
        captured.getOrThrow()
    }
}

/** 実 observable state を monotonic deadline まで観測し、最後の証跡を timeout に含める。 */
internal suspend fun <T> awaitMonotonicObservation(
    description: String,
    timeout: Duration = ADMISSION_TEST_OBSERVATION_TIMEOUT,
    observe: suspend () -> T,
    completed: (T) -> Boolean,
): T {
    val lastObservation = observeUntilMonotonicDeadline(
        timeout = timeout,
        observe = observe,
        completed = completed,
    )

    check(completed(lastObservation)) {
        "$description did not converge within $timeout. lastObservation=$lastObservation"
    }
    return lastObservation
}

/** monotonic deadline まで観測し、完了または期限切れ時点の最後の値を返す。 */
internal suspend fun <T> observeUntilMonotonicDeadline(
    timeout: Duration,
    interval: Duration = ADMISSION_TEST_OBSERVATION_INTERVAL,
    observe: suspend () -> T,
    completed: (T) -> Boolean,
): T {
    val deadlineNanos = System.nanoTime() + timeout.toNanos()
    var lastObservation = observe()

    while (!completed(lastObservation) && System.nanoTime() < deadlineNanos) {
        delay(interval.toMillis())
        lastObservation = observe()
    }

    return lastObservation
}

private val ADMISSION_TEST_OBSERVATION_TIMEOUT: Duration = Duration.ofSeconds(6)
private val ADMISSION_TEST_OBSERVATION_INTERVAL: Duration = Duration.ofMillis(25)
