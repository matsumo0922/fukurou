package me.matsumo.fukurou

import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fukurou module の Testcontainers PostgreSQL timeout contract。 */
class TestPostgresConnectionTest {
    @Test
    fun boundedContainerConfiguresDriverTimeoutParameters() {
        val parameters = InspectablePostgresContainer().configuredUrlParameters()

        assertEquals(TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_CONNECT_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_SOCKET_TIMEOUT_KEY])
    }
}

/** URL parameter を container 起動なしで観測する test double。 */
private class InspectablePostgresContainer : PostgreSQLContainer<InspectablePostgresContainer>("postgres:16-alpine") {
    init {
        configureBoundedTestJdbcConnections()
    }

    fun configuredUrlParameters(): Map<String, String> = urlParameters.toMap()
}
