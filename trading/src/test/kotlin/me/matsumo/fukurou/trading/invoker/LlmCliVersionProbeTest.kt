package me.matsumo.fukurou.trading.invoker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmCliVersionProbeTest {
    @Test
    fun immutableIdentityProbe_isSingleFlightAndInvalidatesByTemplateRevisionAndFingerprint() = runBlocking {
        val directory = Files.createTempDirectory("llm-cli-probe-test")
        val counter = directory.resolve("counter")
        val probe = ProcessLlmCliVersionProbe()
        val base = request(counter.toString(), "revision-1", "image-a")

        coroutineScope {
            List(8) { async { probe.probe(base).getOrThrow() } }.awaitAll()
        }
        probe.probe(base.copy(templateRevision = "revision-2")).getOrThrow()
        probe.probe(base.copy(immutableFingerprint = "image-b")).getOrThrow()

        assertEquals(3, counter.readText().length)
    }

    @Test
    fun unknownFingerprint_probesEveryPhaseAndPersistsWrapperIdentity() = runBlocking {
        val directory = Files.createTempDirectory("llm-cli-probe-unknown-test")
        val counter = directory.resolve("counter")
        val probe = ProcessLlmCliVersionProbe()
        val request = request(counter.toString(), "revision-1", null)

        val first = probe.probe(request).getOrThrow()
        probe.probe(request).getOrThrow()

        assertEquals(2, counter.readText().length)
        assertTrue(first.contains("template=/bin/sh -c"))
        assertTrue(first.contains("revision=revision-1"))
        assertTrue(first.contains("fingerprint=UNKNOWN"))
        assertTrue(first.contains("version=fixture-cli 1.0"))
    }

    @Test
    fun processScopedProbe_isSingleFlightAcrossConsumersAndInvalidatesChangedIdentity() = runBlocking {
        val directory = Files.createTempDirectory("llm-cli-process-probe-test")
        val counter = directory.resolve("counter")
        val base = request(counter.toString(), "process-revision-1", "process-image-a")
        val firstConsumer: LlmCliVersionProbe = ProcessScopedLlmCliVersionProbe
        val secondConsumer: LlmCliVersionProbe = ProcessScopedLlmCliVersionProbe

        coroutineScope {
            listOf(
                async { firstConsumer.probe(base).getOrThrow() },
                async { secondConsumer.probe(base).getOrThrow() },
            ).awaitAll()
        }
        firstConsumer.probe(base.copy(immutableFingerprint = "process-image-b")).getOrThrow()
        secondConsumer.probe(base.copy(immutableFingerprint = null)).getOrThrow()
        firstConsumer.probe(base.copy(immutableFingerprint = null)).getOrThrow()

        assertEquals(4, counter.readText().length)
    }

    private fun request(
        counterPath: String,
        revision: String,
        fingerprint: String?,
    ): LlmCliVersionProbeRequest {
        val script = "printf x >> '$counterPath'; sleep 0.1; printf 'fixture-cli 1.0'"

        return LlmCliVersionProbeRequest(
            provider = LlmProvider.CLAUDE,
            command = listOf("/bin/sh", "-c", script, "--version"),
            templateRevision = revision,
            immutableFingerprint = fingerprint,
        )
    }
}
