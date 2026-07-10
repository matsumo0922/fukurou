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
    fun deploymentFiles_doNotContainRuntimeLegacyEnvNames() {
        val repositoryRoot = repositoryRoot()
        val deploymentFiles = listOf(
            ".env.example",
            "docker-compose.yml",
            "docker-compose.dev.yml",
            "docker-compose.prod.yml",
        )
        val violations = deploymentFiles.flatMap { relativePath ->
            val content = Files.readString(repositoryRoot.resolve(relativePath))

            RuntimeConfigCatalog.runtimeLegacyEnvNames()
                .filter { legacyEnvName -> content.contains(legacyEnvName) }
                .map { legacyEnvName -> "$relativePath: $legacyEnvName" }
        }

        assertTrue(
            violations.isEmpty(),
            "deployment files must not declare runtime legacy env names: $violations",
        )
    }
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
