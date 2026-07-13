package me.matsumo.fukurou

import java.sql.DriverManager
import java.util.UUID

/** PID 1 が観測した child process identity。 */
private data class SpawnReceipt(
    val registrationId: UUID,
    val reservationId: UUID,
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
        require(args.size == 8) {
            "role, registration, reservation, invocation, container, namespace, pid and start ticks are required"
        }
        val role = args[0]
        require(role == "PROVIDER" || role == "MCP") { "unsupported PID registration role" }
        val registrationId = UUID.fromString(args[1])
        val reservationId = UUID.fromString(args[2])
        val invocationId = args[3]
        val containerInstanceId = args[4]
        val namespaceInode = args[5].toLong()
        val processId = args[6].toInt()
        val processStartTicks = args[7].toLong()
        require(invocationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "invalid invocation ID" }
        require(containerInstanceId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "invalid container instance ID" }
        require(namespaceInode > 0 && processId > 1 && processStartTicks > 0) { "invalid process identity" }
        val receipt = SpawnReceipt(
            registrationId = registrationId,
            reservationId = reservationId,
            invocationId = invocationId,
            containerInstanceId = containerInstanceId,
            namespaceInode = namespaceInode,
            processId = processId,
            processStartTicks = processStartTicks,
        )

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
                WHERE registration_id=? AND reservation_id=? AND invocation_id=?
                    AND role='PROVIDER' AND container_instance_id=? AND state='SPAWN_RESERVED'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, receipt.namespaceInode)
            statement.setInt(2, receipt.processId)
            statement.setLong(3, receipt.processStartTicks)
            statement.setObject(4, receipt.registrationId)
            statement.setObject(5, receipt.reservationId)
            statement.setString(6, receipt.invocationId)
            statement.setString(7, receipt.containerInstanceId)
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
                SELECT ?, invocation_id, reservation_id, 'MCP', container_instance_id, ?, ?, ?, 'ACTIVE'
                FROM llm_pid_registrations
                WHERE registration_id=md5('fukurou-pid-registration-v1:' || invocation_id || ':PROVIDER')::uuid
                    AND reservation_id=? AND invocation_id=? AND role='PROVIDER' AND container_instance_id=?
                    AND state IN ('SPAWN_RESERVED','ACTIVE')
                ON CONFLICT (registration_id) DO UPDATE
                SET updated_at=clock_timestamp()
                WHERE llm_pid_registrations.reservation_id=EXCLUDED.reservation_id
                    AND llm_pid_registrations.invocation_id=EXCLUDED.invocation_id
                    AND llm_pid_registrations.role=EXCLUDED.role
                    AND llm_pid_registrations.container_instance_id=EXCLUDED.container_instance_id
                    AND llm_pid_registrations.pid_namespace_inode=EXCLUDED.pid_namespace_inode
                    AND llm_pid_registrations.process_id=EXCLUDED.process_id
                    AND llm_pid_registrations.process_start_ticks=EXCLUDED.process_start_ticks
                    AND llm_pid_registrations.state='ACTIVE'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, receipt.registrationId)
            statement.setLong(2, receipt.namespaceInode)
            statement.setInt(3, receipt.processId)
            statement.setLong(4, receipt.processStartTicks)
            statement.setObject(5, receipt.reservationId)
            statement.setString(6, receipt.invocationId)
            statement.setString(7, receipt.containerInstanceId)
            statement.executeUpdate()
        }
    }
}
