package me.matsumo.fukurou.trading.knowledge

import me.matsumo.fukurou.trading.runner.SecretRedactor
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal fun writeRedactedVaultFileIfChanged(
    targetPath: Path,
    rawContent: String,
    redactor: SecretRedactor,
): Boolean {
    val content = redactor.redact(rawContent)

    Files.createDirectories(requireNotNull(targetPath.parent))

    if (Files.exists(targetPath) && Files.readString(targetPath, StandardCharsets.UTF_8) == content) {
        return false
    }

    atomicReplace(targetPath, content)

    return true
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
