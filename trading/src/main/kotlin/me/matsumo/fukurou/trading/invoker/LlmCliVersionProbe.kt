package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** CLI version identity の bounded probe。 */
fun interface LlmCliVersionProbe {
    suspend fun probe(request: LlmCliVersionProbeRequest): Result<String>
}

/** immutable fingerprint がある場合だけ process cache を共有する probe request。 */
data class LlmCliVersionProbeRequest(
    val provider: LlmProvider,
    val command: List<String>,
    val templateRevision: String,
    val immutableFingerprint: String?,
)

/** 10秒/128 byte上限で `--version` を実行する probe。 */
class ProcessLlmCliVersionProbe(
    private val timeout: Duration = Duration.ofSeconds(10),
) : LlmCliVersionProbe {
    private val immutableCache = ConcurrentHashMap<String, CompletableFuture<Result<String>>>()

    override suspend fun probe(request: LlmCliVersionProbeRequest): Result<String> = withContext(Dispatchers.IO) {
        val cacheKey = request.immutableFingerprint?.let { fingerprint -> request.cacheKey(fingerprint) }
        if (cacheKey == null) return@withContext probeOnce(request)

        val pending = CompletableFuture<Result<String>>()
        val existing = immutableCache.putIfAbsent(cacheKey, pending)
        if (existing != null) return@withContext existing.get()

        val result = probeOnce(request)
        pending.complete(result)
        if (result.isFailure) immutableCache.remove(cacheKey, pending)

        result
    }

    private fun probeOnce(request: LlmCliVersionProbeRequest): Result<String> = runCatching {
        require(request.command.isNotEmpty()) { "CLI version probe command is empty." }
        val process = ProcessBuilder(request.command)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("CLI version probe timed out.")
        }
        require(process.exitValue() == 0) { "CLI version probe failed." }
        val bytes = process.inputStream.readNBytes(MAX_CLI_VERSION_BYTES + 1)
        require(bytes.size in 1..MAX_CLI_VERSION_BYTES) { "CLI version identity size rejected." }
        val version = bytes.toString(StandardCharsets.UTF_8).trim().replace(WHITESPACE, " ")
        require(version.isNotBlank()) { "CLI version identity is blank." }

        "revision=${request.templateRevision};template=${request.command.dropLast(1).joinToString(" ")};" +
            "fingerprint=${request.immutableFingerprint ?: "UNKNOWN"};version=$version"
    }
}

/** process 内の全 invocation consumer が共有する single-flight version probe。 */
object ProcessScopedLlmCliVersionProbe : LlmCliVersionProbe {
    private val delegate = ProcessLlmCliVersionProbe()

    override suspend fun probe(request: LlmCliVersionProbeRequest): Result<String> = delegate.probe(request)
}

const val MAX_CLI_VERSION_BYTES = 128
private val WHITESPACE = Regex("\\s+")

private fun LlmCliVersionProbeRequest.cacheKey(fingerprint: String): String =
    listOf(provider.name, command.joinToString("\u0000"), templateRevision, fingerprint).joinToString("\u0001")
