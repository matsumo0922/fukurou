package me.matsumo.fukurou

import java.sql.DriverManager

/** PID 1 が socket publication 前に読む launch maintenance generation probe。 */
object LaunchFenceDatabaseProbeMain {
    /** secret を出力せず generation と maintenance flag だけを返す。 */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "launch fence database probe does not accept arguments" }
        val url = requireNotNull(System.getenv("DB_URL")) { "DB_URL is required" }
        val user = requireNotNull(System.getenv("DB_USER")) { "DB_USER is required" }
        val password = requireNotNull(System.getenv("DB_PASSWORD")) { "DB_PASSWORD is required" }

        println(readState(url, user, password))
    }

    @Suppress("NestedBlockDepth")
    private fun readState(
        url: String,
        user: String,
        password: String,
    ): String {
        return DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                "SELECT generation, enabled FROM llm_launch_maintenance WHERE singleton = TRUE",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        "${result.getLong(1)}|${result.getBoolean(2)}"
                    } else {
                        "0|false"
                    }
                }
            }
        }
    }
}
