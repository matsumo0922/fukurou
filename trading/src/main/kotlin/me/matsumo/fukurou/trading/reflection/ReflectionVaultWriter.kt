package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.knowledge.writeRedactedVaultFileIfChanged
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * reflection report を Obsidian vault の Knowledge 配下へ書き込む writer。
 *
 * @param vaultPath 書き込み先 vault path
 * @param redactor note 書き込み前に秘密値を伏せる redactor
 */
class ReflectionVaultWriter(
    private val vaultPath: Path,
    private val redactor: SecretRedactor,
) {

    /**
     * reflection report 一式を vault へ書き込む。
     */
    fun write(reports: ReflectionReports): Result<ReflectionWriteSummary> {
        return try {
            val summary = ReflectionWriteCounter()

            createDirectorySkeleton()
            reports.files.forEach { file ->
                summary.record(writeIfChanged(file))
            }

            Result.success(summary.toSummary())
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /**
     * 指定週の PromptCandidates note frontmatter から retry state を読み取る。
     */
    fun readPromptCandidateState(weekId: String): Result<ReflectionPromptCandidateNoteState?> {
        return try {
            val targetPath = vaultPath.resolve("Knowledge/PromptCandidates/$weekId.md")

            if (!Files.exists(targetPath)) {
                return Result.success(null)
            }

            Result.success(
                parseReflectionPromptCandidateNoteState(
                    Files.readString(targetPath, StandardCharsets.UTF_8),
                ),
            )
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    private fun createDirectorySkeleton() {
        REQUIRED_REFLECTION_DIRECTORIES.forEach { relativePath ->
            Files.createDirectories(vaultPath.resolve(relativePath))
        }
    }

    private fun writeIfChanged(file: ReflectionMarkdownFile): ReflectionVaultWriteState {
        val targetPath = vaultPath.resolve(file.relativePath)

        return if (writeRedactedVaultFileIfChanged(targetPath, file.content, redactor)) {
            ReflectionVaultWriteState.WRITTEN
        } else {
            ReflectionVaultWriteState.UNCHANGED
        }
    }
}

/**
 * reflection vault file 書き込み結果。
 */
private enum class ReflectionVaultWriteState {
    /**
     * file を作成または置換した。
     */
    WRITTEN,

    /**
     * 既存 file と内容が一致していた。
     */
    UNCHANGED,
}

/**
 * writer の出力件数を集計する mutable counter。
 */
private class ReflectionWriteCounter {

    private var writtenFiles = 0
    private var unchangedFiles = 0

    /**
     * file ごとの書き込み結果を集計する。
     */
    fun record(state: ReflectionVaultWriteState) {
        when (state) {
            ReflectionVaultWriteState.WRITTEN -> writtenFiles += 1
            ReflectionVaultWriteState.UNCHANGED -> unchangedFiles += 1
        }
    }

    /**
     * immutable summary に変換する。
     */
    fun toSummary(): ReflectionWriteSummary {
        return ReflectionWriteSummary(
            writtenFiles = writtenFiles,
            unchangedFiles = unchangedFiles,
        )
    }
}

/**
 * reflection report 用に必ず作る directory。
 */
private val REQUIRED_REFLECTION_DIRECTORIES = listOf(
    "Knowledge",
    "Knowledge/DailyReflections",
    "Knowledge/Calibration",
    "Knowledge/PromptCandidates",
    "Knowledge/Setups",
    "Knowledge/WeeklyReviews",
)

internal fun parseReflectionPromptCandidateNoteState(content: String): ReflectionPromptCandidateNoteState? {
    val frontmatter = content.frontmatterLines() ?: return null
    val fields = frontmatter.mapNotNull { line -> line.toFrontmatterField() }.toMap()
    val statusValue = fields["generation_status"]?.yamlScalarValue() ?: return null
    val status = ReflectionPromptCandidateGenerationStatus.fromWireValue(statusValue) ?: return null
    val attemptCount = fields["attempt_count"]?.yamlScalarValue()?.toIntOrNull() ?: 0
    val nextRetryAfter = fields["next_retry_after"]
        ?.yamlScalarValue()
        ?.takeUnless { value -> value == "null" || value.isBlank() }
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

    return ReflectionPromptCandidateNoteState(
        status = status,
        attemptCount = attemptCount,
        nextRetryAfter = nextRetryAfter,
    )
}

private fun String.frontmatterLines(): List<String>? {
    val lines = lineSequence().toList()

    if (lines.firstOrNull() != "---") {
        return null
    }

    val endIndex = lines.drop(1).indexOfFirst { line -> line == "---" }

    if (endIndex < 0) {
        return null
    }

    return lines.drop(1).take(endIndex)
}

private fun String.toFrontmatterField(): Pair<String, String>? {
    val separatorIndex = indexOf(":")

    if (separatorIndex <= 0) {
        return null
    }

    val key = take(separatorIndex).trim()
    val value = drop(separatorIndex + 1).trim()

    return key to value
}

private fun String.yamlScalarValue(): String {
    val trimmed = trim()
    val quoted = trimmed.length >= 2 && trimmed.first() == '"'
    val quoteClosed = quoted && trimmed.last() == '"'

    if (!quoteClosed) {
        return trimmed
    }

    return trimmed
        .drop(1)
        .dropLast(1)
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
