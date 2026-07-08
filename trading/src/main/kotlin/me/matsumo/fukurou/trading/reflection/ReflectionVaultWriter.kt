package me.matsumo.fukurou.trading.reflection

import kotlinx.coroutines.CancellationException
import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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

    private fun createDirectorySkeleton() {
        REQUIRED_REFLECTION_DIRECTORIES.forEach { relativePath ->
            Files.createDirectories(vaultPath.resolve(relativePath))
        }
    }

    private fun writeIfChanged(file: ReflectionMarkdownFile): ReflectionVaultWriteState {
        val targetPath = vaultPath.resolve(file.relativePath)
        val content = redactor.redact(file.content)

        Files.createDirectories(requireNotNull(targetPath.parent))

        if (Files.exists(targetPath) && Files.readString(targetPath, StandardCharsets.UTF_8) == content) {
            return ReflectionVaultWriteState.UNCHANGED
        }

        atomicReplace(targetPath, content)

        return ReflectionVaultWriteState.WRITTEN
    }

    private fun atomicReplace(targetPath: Path, content: String) {
        val parentPath = requireNotNull(targetPath.parent)
        val tempPath = Files.createTempFile(parentPath, "${targetPath.fileName}.", ".tmp")

        try {
            Files.writeString(
                tempPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            moveReplacing(tempPath, targetPath)
        } catch (throwable: Throwable) {
            Files.deleteIfExists(tempPath)

            throw throwable
        }
    }

    private fun moveReplacing(tempPath: Path, targetPath: Path) {
        try {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
            )
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
    "Knowledge/Setups",
    "Knowledge/WeeklyReviews",
)
