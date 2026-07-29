package me.matsumo.fukurou

import kotlinx.coroutines.runBlocking
import me.matsumo.fukurou.trading.invoker.LlmAuthEvidenceReader
import me.matsumo.fukurou.trading.invoker.LlmAuthEvidenceState
import me.matsumo.fukurou.trading.invoker.LlmAuthFailureEvidence
import me.matsumo.fukurou.trading.invoker.LlmProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * credential marker の存在だけでなく、観測済みの認証失敗 evidence を反映した CLI auth status を検証するテスト。
 */
class LlmAuthStatusEvidenceTest {

    @Test
    fun snapshot_reportsTokenSuspectWhenTheCurrentCredentialObservedAnAuthFailure() = runBlocking {
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = MARKER_GENERATION))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.TOKEN_SUSPECT, status.status)
        assertNoSecretLikeDetail(status.detail)
    }

    @Test
    fun snapshot_reportsLoggedInWhenNoFailureWasObserved() = runBlocking {
        // 回帰: 正常時は従来どおり logged_in を返す
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)

        val status = codexStatus(home, LlmAuthEvidenceState())

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }

    @Test
    fun snapshot_keepsTokenSuspectAfterALaterInvocationRecordsNoFailure() = runBlocking {
        // invocation は per-run copy に対して成功するだけで persistent source の有効性を証明しない。
        // 降格の解除は再ログインだけで、後続の成功や非認証 failure では解除しない
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = MARKER_GENERATION))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.TOKEN_SUSPECT, status.status)
    }

    @Test
    fun snapshot_ignoresEvidenceFromASupersededCredentialGeneration() = runBlocking {
        // 再ログインで marker が更新されたら、それより古い世代の失敗は解消済みとして無視する
        val home = codexHomeWithMarker(markerModifiedAt = Instant.parse("2026-07-22T00:00:00Z"))
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = Instant.parse("2026-07-20T00:00:00Z")))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }

    @Test
    fun snapshot_treatsEvidenceEqualToTheMarkerTimeAsCurrent() = runBlocking {
        // 同一 timestamp の衝突は、失効を見逃す側ではなく降格を残す側に倒す
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = MARKER_GENERATION))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.TOKEN_SUSPECT, status.status)
    }

    @Test
    fun snapshot_ignoresEvidenceWithoutAKnownGeneration() = runBlocking {
        // 世代不明の evidence は現 credential のものと判定できないため降格に使わない
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = null))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }

    @Test
    fun snapshot_doesNotDowngradeAProviderFromAnotherProvidersEvidence() = runBlocking {
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CLAUDE, failureEvidence(generation = MARKER_GENERATION))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }

    @Test
    fun snapshot_keepsTokenSuspectWhenALaterFailureHasNoKnownGeneration() = runBlocking {
        // 世代既知の失効を観測した後、世代を観測できない failure が続いても降格を維持する。
        // 世代不明で上書きすると status 側がそれを無視して logged_in へ戻る
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = MARKER_GENERATION))
            recordFailure(
                LlmProvider.CODEX,
                LlmAuthFailureEvidence(
                    observedAt = Instant.parse("2026-07-26T00:00:00Z"),
                    credentialGeneration = null,
                ),
            )
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.TOKEN_SUSPECT, status.status)
    }

    @Test
    fun snapshot_reportsUnknownWhenTheMarkerTimestampCannotBeRead() = runBlocking {
        // marker は存在するが世代を判定できない。判定不能を「正常」と報告しない
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val marker = home.resolve(".codex").resolve("auth.json")
        val unreadableTimestampService = DefaultLlmAuthService(
            config = LlmAuthServiceConfig(
                claudeCommandTemplate = listOf("claude"),
                codexCommandTemplate = listOf("codex"),
                cliHome = home,
                codexHome = home.resolve(".codex"),
                inheritedLoginEnvironment = emptyMap(),
            ),
            authEvidenceReader = LlmAuthEvidenceState(),
            markerModifiedAtReader = { path ->
                if (path == marker) throw IOException("synthetic timestamp failure") else Files.getLastModifiedTime(path).toInstant()
            },
        )

        val status = unreadableTimestampService.use { service ->
            service.snapshot().getOrThrow().providers.single { provider -> provider.provider == LlmAuthProvider.CODEX }
        }

        assertEquals(LlmAuthStatus.UNKNOWN, status.status)
    }

    @Test
    fun snapshot_reportsUnknownWhenTheEvidenceSourceIsUnavailable() = runBlocking {
        // evidence 参照自体が失敗した場合も logged_in へ倒さない
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)
        val failingReader = LlmAuthEvidenceReader { throw IllegalStateException("evidence source unavailable") }

        val status = codexStatus(home, failingReader)

        assertEquals(LlmAuthStatus.UNKNOWN, status.status)
        assertNoSecretLikeDetail(status.detail)
    }

    @Test
    fun snapshot_reportsLoggedOutWhenTheMarkerIsAbsent() = runBlocking {
        val home = Files.createTempDirectory("fukurou-llm-auth-status-home")
        Files.createDirectories(home.resolve(".codex"))
        val evidenceState = LlmAuthEvidenceState().apply {
            recordFailure(LlmProvider.CODEX, failureEvidence(generation = MARKER_GENERATION))
        }

        val status = codexStatus(home, evidenceState)

        assertEquals(LlmAuthStatus.LOGGED_OUT, status.status)
    }

    @Test
    fun snapshot_reportsLoggedInWithoutAnEvidenceReader() = runBlocking {
        // evidence source 未注入の構成では従来どおり marker の存在だけで判定する
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)

        val status = codexStatus(home, evidenceState = null)

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }

    @Test
    fun snapshot_reportsLoggedInWhenTheStateIsEmptyAfterARestart() = runBlocking {
        // process 再起動で state が失われた直後は evidence 無しとして扱う（既知の限界）
        val home = codexHomeWithMarker(markerModifiedAt = MARKER_GENERATION)

        val status = codexStatus(home, LlmAuthEvidenceState())

        assertEquals(LlmAuthStatus.LOGGED_IN, status.status)
    }
}

private suspend fun codexStatus(cliHome: Path, evidenceState: LlmAuthEvidenceReader?): LlmAuthProviderStatus {
    return DefaultLlmAuthService(
        config = LlmAuthServiceConfig(
            claudeCommandTemplate = listOf("claude"),
            codexCommandTemplate = listOf("codex"),
            cliHome = cliHome,
            codexHome = cliHome.resolve(".codex"),
            inheritedLoginEnvironment = emptyMap(),
        ),
        authEvidenceReader = evidenceState,
    ).use { service ->
        service.snapshot().getOrThrow().providers.single { provider -> provider.provider == LlmAuthProvider.CODEX }
    }
}

private fun codexHomeWithMarker(markerModifiedAt: Instant): Path {
    val cliHome = Files.createTempDirectory("fukurou-llm-auth-status-home")
    val codexHome = cliHome.resolve(".codex")
    Files.createDirectories(codexHome)
    val marker = codexHome.resolve("auth.json")
    Files.writeString(marker, """{"token":"persisted"}""")
    Files.setLastModifiedTime(marker, FileTime.from(markerModifiedAt))

    return cliHome
}

private fun failureEvidence(generation: Instant?): LlmAuthFailureEvidence {
    return LlmAuthFailureEvidence(
        observedAt = Instant.parse("2026-07-25T00:00:00Z"),
        credentialGeneration = generation,
    )
}

private fun assertNoSecretLikeDetail(detail: String?) {
    val text = detail.orEmpty()

    assertEquals(false, text.contains("token"))
    assertEquals(false, text.contains("credential marker present"))
}

private val MARKER_GENERATION: Instant = Instant.parse("2026-07-20T00:00:00Z")
