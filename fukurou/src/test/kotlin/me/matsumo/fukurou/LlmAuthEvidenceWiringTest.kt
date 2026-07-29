package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CLI auth 失敗 evidence が、Ktor process 内の全 LLM invocation 経路へ配線されていることを検証するテスト。
 *
 * evidence state は in-process であり、監視 API が読むのは 1 instance だけである。auditor を構築する
 * 経路が1つでも state を受け取らないと、その経路で観測した認証失敗は `/ops/llm-auth` に届かず、
 * 失効中でも `logged_in` を返す。この配線漏れは型検査で防げず、実行時にも例外にならないため、
 * 構築箇所の網羅を静的に確認する。
 *
 * 新しい invocation 経路を追加したときは、その auditor へ `authEvidenceState` を渡すこと。
 */
class LlmAuthEvidenceWiringTest {

    @Test
    fun everyProductionAuditorConstructionReceivesTheSharedEvidenceState() {
        val sources = listOf(
            Path.of("src/main/kotlin/me/matsumo/fukurou/Application.kt"),
            Path.of("src/main/kotlin/me/matsumo/fukurou/LlmDaemonSchedulerWorker.kt"),
            Path.of("src/main/kotlin/me/matsumo/fukurou/ReflectionRunnerWorker.kt"),
            Path.of("../trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunner.kt"),
        )
        val constructionsWithoutState = sources.flatMap { source ->
            auditorConstructionsMissingEvidenceState(source)
        }

        assertEquals(emptyList(), constructionsWithoutState)
    }

    @Test
    fun theScannerDetectsAConstructionWithoutTheEvidenceState() {
        // 検査自体が機能していることを示す。production 経路を模した断片で、
        // authEvidenceState を渡さない構築を検出できることを確認する
        val fixture = Files.createTempFile("auditor-wiring-fixture", ".kt")
        Files.writeString(
            fixture,
            """
                val auditor = LlmInvocationAuditor(
                    commandEventLog = commandEventLog,
                    redactor = redactor,
                    clock = clock,
                )
            """.trimIndent(),
        )

        val detected = auditorConstructionsMissingEvidenceState(fixture)

        assertEquals(1, detected.size)
        Files.deleteIfExists(fixture)
    }
}

/**
 * 指定 source 内の `LlmInvocationAuditor(` 構築のうち、`authEvidenceState` を渡していないものの
 * 行番号を返す。構築は閉じ括弧の深さで区切る。
 */
private fun auditorConstructionsMissingEvidenceState(source: Path): List<String> {
    val lines = source.readText().lines()
    val missing = mutableListOf<String>()
    var lineIndex = 0

    while (lineIndex < lines.size) {
        if (!lines[lineIndex].contains("LlmInvocationAuditor(")) {
            lineIndex += 1
            continue
        }

        val startLine = lineIndex
        var depth = 0
        var passesEvidenceState = false

        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            depth += line.count { character -> character == '(' } - line.count { character -> character == ')' }

            if (line.contains("authEvidenceState")) {
                passesEvidenceState = true
            }

            lineIndex += 1

            if (depth <= 0) break
        }

        if (!passesEvidenceState) {
            missing += "${source.fileName}:${startLine + 1}"
        }
    }

    return missing
}
