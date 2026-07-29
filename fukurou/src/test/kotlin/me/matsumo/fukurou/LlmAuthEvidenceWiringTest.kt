package me.matsumo.fukurou

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI auth 失敗 evidence が、Ktor process 内の全 LLM invocation 経路へ配線されていることを検証するテスト。
 *
 * evidence state は in-process であり、監視 API が読むのは 1 instance だけである。auditor を構築する
 * 経路が1つでも state を受け取らない、あるいは別 instance を受け取ると、その経路で観測した認証失敗は
 * `/ops/llm-auth` に届かず、失効中でも `logged_in` を返す。この配線漏れは型検査で防げず、実行時にも
 * 例外にならないため、構築箇所を静的に確認する。
 *
 * 新しい invocation 経路を追加したときは、その auditor へ共有 state を渡すこと。
 */
class LlmAuthEvidenceWiringTest {

    @Test
    fun everyProductionAuditorConstructionReceivesTheSharedEvidenceState() {
        val constructions = productionSourceRoots().flatMap { root -> auditorConstructions(root) }

        // 走査対象が空になると検査が無意味に green になるため、既知の構築数を下回らないことを確認する
        assertTrue(constructions.size >= KNOWN_PRODUCTION_CONSTRUCTION_COUNT, "found ${constructions.size}")
        assertEquals(emptyList(), constructions.filterNot(AuditorConstruction::passesSharedState))
    }

    @Test
    fun theScannerRejectsConstructionsThatBreakTheSharedInstance() {
        val cases = mapOf(
            "missing" to "LlmInvocationAuditor(\n    clock = clock,\n)",
            "explicit null" to "LlmInvocationAuditor(\n    authEvidenceState = null,\n)",
            "fresh instance" to "LlmInvocationAuditor(\n    authEvidenceState = LlmAuthEvidenceState(),\n)",
            "mention only" to "LlmInvocationAuditor(\n    clock = clock, // authEvidenceState は別途\n)",
        )

        cases.forEach { (label, snippet) ->
            val fixture = Files.createTempFile("auditor-wiring-$label", ".kt")
            Files.writeString(fixture, snippet)

            val construction = auditorConstructions(fixture).single()

            assertEquals(false, construction.passesSharedState, label)
            Files.deleteIfExists(fixture)
        }
    }

    @Test
    fun theScannerAcceptsAConstructionThatForwardsTheSharedState() {
        val fixture = Files.createTempFile("auditor-wiring-accepted", ".kt")
        Files.writeString(fixture, "LlmInvocationAuditor(\n    authEvidenceState = runtime.authEvidenceState,\n)")

        val construction = auditorConstructions(fixture).single()

        assertEquals(true, construction.passesSharedState)
        Files.deleteIfExists(fixture)
    }
}

/**
 * production source 中の auditor 構築1件。
 *
 * @param location 構築位置（`file:line`）
 * @param passesSharedState 共有 state を forward しているか
 */
private data class AuditorConstruction(
    val location: String,
    val passesSharedState: Boolean,
)

/** production の Kotlin source root。新しいファイルに構築が増えても走査対象に入る。 */
private fun productionSourceRoots(): List<Path> {
    return listOf(
        Path.of("src/main/kotlin"),
        Path.of("../trading/src/main/kotlin"),
    ).filter(Files::isDirectory)
}

/** 指定 root 配下の全 Kotlin source から auditor 構築を集める。 */
private fun auditorConstructions(root: Path): List<AuditorConstruction> {
    if (!Files.isDirectory(root)) {
        return auditorConstructionsInFile(root)
    }

    return Files.walk(root).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
            .toList()
            .flatMap(::auditorConstructionsInFile)
    }
}

private fun auditorConstructionsInFile(source: Path): List<AuditorConstruction> {
    val lines = source.readText().lines()
    val constructions = mutableListOf<AuditorConstruction>()
    var lineIndex = 0

    while (lineIndex < lines.size) {
        if (!lines[lineIndex].isAuditorConstructionStart()) {
            lineIndex += 1
            continue
        }

        val startLine = lineIndex
        var depth = 0
        var forwardsSharedState = false

        while (lineIndex < lines.size) {
            val line = lines[lineIndex].substringBefore("//")
            depth += line.count { character -> character == '(' } - line.count { character -> character == ')' }

            if (line.forwardsSharedEvidenceState()) {
                forwardsSharedState = true
            }

            lineIndex += 1

            if (depth <= 0) break
        }

        constructions += AuditorConstruction("${source.fileName}:${startLine + 1}", forwardsSharedState)
    }

    return constructions
}

private fun String.isAuditorConstructionStart(): Boolean {
    val code = substringBefore("//")

    // class 宣言自体は構築ではない
    if (code.contains("class LlmInvocationAuditor(")) return false

    return code.contains("LlmInvocationAuditor(")
}

/**
 * `authEvidenceState` に共有 instance を forward しているかを判定する。
 *
 * `null` と新規生成（`LlmAuthEvidenceState()`）は、値としては通っても経路ごとに別 instance になり、
 * 監視 API が読む state へ evidence が届かないため受理しない。
 */
private fun String.forwardsSharedEvidenceState(): Boolean {
    val assignment = substringAfter("authEvidenceState = ", missingDelimiterValue = "").trim().trimEnd(',')

    if (assignment.isEmpty()) return false

    return assignment != "null" && !assignment.startsWith("LlmAuthEvidenceState(")
}

/** 現在の production 経路数。走査が空振りしていないことの下限として使う。 */
private const val KNOWN_PRODUCTION_CONSTRUCTION_COUNT = 4
