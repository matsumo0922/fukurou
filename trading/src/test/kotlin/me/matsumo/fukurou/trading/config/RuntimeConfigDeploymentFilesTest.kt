package me.matsumo.fukurou.trading.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * deployment file が runtime config を列挙しないことを検証するテスト。
 */
class RuntimeConfigDeploymentFilesTest {

    @Test
    fun deploymentFiles_doNotDeclareRuntimeLegacyEnvNames() {
        val repositoryRoot = repositoryRoot()
        val violations = deploymentFiles(repositoryRoot).flatMap { deploymentFile ->
            RuntimeConfigCatalog.runtimeLegacyEnvNames()
                .filter { legacyEnvName -> deploymentFile.declaresEnvName(legacyEnvName) }
                .map { legacyEnvName -> "${repositoryRoot.relativize(deploymentFile)}: $legacyEnvName" }
        }

        assertTrue(
            violations.isEmpty(),
            "deployment files must not declare runtime legacy env names: $violations",
        )
    }

    @Test
    fun primaryDeploymentFiles_keepRequiredDeploymentLegacyEnvNames() {
        val repositoryRoot = repositoryRoot()
        val requiredEnvNames = setOf(
            "FUKUROU_MCP_JAR_PATH",
            "FUKUROU_TRADING_MODE",
            "FUKUROU_GMO_PUBLIC_BASE_URL",
            "FUKUROU_GMO_PUBLIC_WEBSOCKET_URL",
            "FUKUROU_OBSIDIAN_VAULT_PATH",
        )
        val primaryDeploymentFiles = listOf(
            repositoryRoot.resolve(".env.example"),
            repositoryRoot.resolve("docker-compose.yml"),
            repositoryRoot.resolve("docker-compose.prod.yml"),
        )
        val catalogEnvNames = RuntimeConfigCatalog.deploymentLegacyEnvNames()

        assertTrue(
            catalogEnvNames.containsAll(requiredEnvNames),
            "required deployment env names must belong to the deployment catalog: $requiredEnvNames",
        )

        val missingDeclarations = primaryDeploymentFiles.flatMap { deploymentFile ->
            requiredEnvNames
                .filterNot { legacyEnvName -> deploymentFile.activelyDeclaresEnvName(legacyEnvName) }
                .map { legacyEnvName -> "${repositoryRoot.relativize(deploymentFile)}: $legacyEnvName" }
        }

        assertTrue(
            missingDeclarations.isEmpty(),
            "primary deployment files must keep required deployment env names: $missingDeclarations",
        )
    }
}

private fun deploymentFiles(repositoryRoot: Path): List<Path> {
    val composeFiles = Files.newDirectoryStream(repositoryRoot, "docker-compose*.y*ml").use { paths ->
        paths.toList()
    }

    return (listOf(repositoryRoot.resolve(".env.example")) + composeFiles)
        .sortedBy { path -> path.fileName.toString() }
}

private fun Path.declaresEnvName(envName: String): Boolean = matchesEnvName(envName, allowComment = true)

private fun Path.activelyDeclaresEnvName(envName: String): Boolean = matchesEnvName(envName, allowComment = false)

private fun Path.matchesEnvName(envName: String, allowComment: Boolean): Boolean {
    val optionalCommentPattern = if (allowComment) "#?\\s*" else ""
    val declarationPattern = Regex(
        pattern = "(?m)^\\s*$optionalCommentPattern${Regex.escape(envName)}\\s*[=:]",
    )

    return declarationPattern.containsMatchIn(Files.readString(this))
}

private fun repositoryRoot(): Path {
    var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()

    while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
        candidate = requireNotNull(candidate.parent) {
            "repository root was not found."
        }
    }

    return candidate
}
