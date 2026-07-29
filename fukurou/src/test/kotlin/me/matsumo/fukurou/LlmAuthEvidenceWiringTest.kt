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
 * `/ops/llm-auth` に届かず、失効中でも `logged_in` を返す。
 *
 * 配線は3層で担保する。
 *
 * 1. `:fukurou` の中間 factory は `authEvidenceState` を必須引数として受けるため、呼び出し側が渡し忘れると
 *    コンパイルが失敗する
 * 2. production の state 生成を1箇所に固定する。中間 factory へ別 instance を注入すると生成箇所が増えるため、
 *    引数の値を見なくても共有が崩れたことを検出できる
 * 3. auditor 構築が `null` を受け取る形を検出する
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
    fun productionCreatesTheEvidenceStateExactlyOnce() {
        // 共有 instance であることは、構築箇所の引数を見るだけでは判定できない。中間 factory へ
        // 別 instance を注入すると型検査も引数検査も通るため、生成そのものを 1 箇所に固定する。
        // production で state を生成してよいのは application runtime resource の組み立てだけ
        val instantiations = productionSourceRoots().flatMap { root -> evidenceStateInstantiations(root) }

        assertEquals(listOf(SOLE_PRODUCTION_INSTANTIATION_FILE), instantiations)
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
    return kotlinSources(root).flatMap(::auditorConstructionsInFile)
}

/** 指定 path が directory ならその配下の Kotlin source を、file ならそれ自体を返す。 */
private fun kotlinSources(root: Path): List<Path> {
    if (!Files.isDirectory(root)) {
        return listOf(root)
    }

    return Files.walk(root).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && path.extension == "kt" }.toList()
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
 * `authEvidenceState` に値を forward しているかを判定する。
 *
 * `null` と直書きの新規生成は受理しない。ただし別変数として渡された孤立 instance はこの検査では
 * 判別できないため、共有の保証は生成箇所を1つに固定する検査が担う。
 */
private fun String.forwardsSharedEvidenceState(): Boolean {
    val assignment = substringAfter("authEvidenceState = ", missingDelimiterValue = "").trim().trimEnd(',')

    if (assignment.isEmpty()) return false

    return assignment != "null" && !assignment.startsWith("LlmAuthEvidenceState(")
}

/** 指定 root 配下から `LlmAuthEvidenceState()` を生成しているファイル名を集める。 */
private fun evidenceStateInstantiations(root: Path): List<String> {
    return kotlinSources(root).flatMap { source ->
        source.readText().lines().mapIndexedNotNull { _, line ->
            val code = line.substringBefore("//")

            if (code.contains("LlmAuthEvidenceState(")) source.fileName.toString() else null
        }
    }
}

/** 現在の production 経路数。走査が空振りしていないことの下限として使う。 */
private const val KNOWN_PRODUCTION_CONSTRUCTION_COUNT = 4

/**
 * production で唯一 state を生成してよい場所。
 *
 * 行番号は含めない。無関係な編集で行がずれるたびに落ちると、検査の意図（生成箇所が増えていないか）
 * とは無関係な保守コストになる。
 */
private const val SOLE_PRODUCTION_INSTANTIATION_FILE = "Application.kt"
