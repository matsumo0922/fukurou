package me.matsumo.fukurou

import java.sql.DriverManager

/** PID 1 が観測した child process identity。 */
private data class SpawnReceipt(
    val invocationId: String,
    val containerInstanceId: String,
    val namespaceInode: Long,
    val processId: Int,
    val processStartTicks: Long,
)

/** PID 1 の spawn receipt を exact CAS で operational registration へ反映する。 */
object PidRegistrationReceiptMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 6) { "role, invocation, container, namespace, pid and start ticks are required" }
        val role = args[0]
        require(role == "PROVIDER" || role == "MCP") { "unsupported PID registration role" }
        val invocationId = args[1]
        val containerInstanceId = args[2]
        val namespaceInode = args[3].toLong()
        val processId = args[4].toInt()
        val processStartTicks = args[5].toLong()
        require(invocationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "invalid invocation ID" }
        require(containerInstanceId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "invalid container instance ID" }
        require(namespaceInode > 0 && processId > 1 && processStartTicks > 0) { "invalid process identity" }
        val receipt = SpawnReceipt(invocationId, containerInstanceId, namespaceInode, processId, processStartTicks)

        val url = requireNotNull(System.getenv("DB_URL")) { "DB_URL is required" }
        val user = requireNotNull(System.getenv("DB_USER")) { "DB_USER is required" }
        val password = requireNotNull(System.getenv("DB_PASSWORD")) { "DB_PASSWORD is required" }
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL lock_timeout = '250ms'")
                statement.execute("SET LOCAL statement_timeout = '1s'")
            }
            val updatedRows = if (role == "PROVIDER") {
                activateProvider(
                    connection = connection,
                    receipt = receipt,
                )
            } else {
                registerMcp(
                    connection = connection,
                    receipt = receipt,
                )
            }
            check(updatedRows == 1) { "PID registration receipt did not match exactly one reservation" }
            connection.commit()
        }
    }

    private fun activateProvider(connection: java.sql.Connection, receipt: SpawnReceipt): Int {
        return connection.prepareStatement(
            """
                UPDATE llm_pid_registrations
                SET state='ACTIVE', pid_namespace_inode=?, process_id=?, process_start_ticks=?, updated_at=clock_timestamp()
                WHERE invocation_id=? AND role='PROVIDER' AND container_instance_id=? AND state='SPAWN_RESERVED'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, receipt.namespaceInode)
            statement.setInt(2, receipt.processId)
            statement.setLong(3, receipt.processStartTicks)
            statement.setString(4, receipt.invocationId)
            statement.setString(5, receipt.containerInstanceId)
            statement.executeUpdate()
        }
    }

    private fun registerMcp(connection: java.sql.Connection, receipt: SpawnReceipt): Int {
        return connection.prepareStatement(
            """
                INSERT INTO llm_pid_registrations(
                    registration_id, invocation_id, reservation_id, role, container_instance_id,
                    pid_namespace_inode, process_id, process_start_ticks, state
                )
                SELECT md5('fukurou-pid-registration-v1:' || invocation_id || ':MCP')::uuid,
                    invocation_id, reservation_id, 'MCP', container_instance_id, ?, ?, ?, 'ACTIVE'
                FROM llm_pid_registrations
                WHERE invocation_id=? AND role='PROVIDER' AND container_instance_id=?
                    AND state IN ('SPAWN_RESERVED','ACTIVE')
                ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, receipt.namespaceInode)
            statement.setInt(2, receipt.processId)
            statement.setLong(3, receipt.processStartTicks)
            statement.setString(4, receipt.invocationId)
            statement.setString(5, receipt.containerInstanceId)
            statement.executeUpdate()
        }
    }
}
