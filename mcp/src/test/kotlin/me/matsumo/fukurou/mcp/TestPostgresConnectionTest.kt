package me.matsumo.fukurou.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/** MCP module の Testcontainers PostgreSQL timeout contract。 */
class TestPostgresConnectionTest {
    @Test
    fun boundedContainerConfiguresDriverTimeoutParameters() {
        val parameters = InspectablePostgresContainer().configuredUrlParameters()

        assertEquals(TEST_POSTGRES_CONNECT_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_CONNECT_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_LOGIN_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_LOGIN_TIMEOUT_KEY])
        assertEquals(TEST_POSTGRES_SOCKET_TIMEOUT_SECONDS.toString(), parameters[TEST_POSTGRES_SOCKET_TIMEOUT_KEY])
    }

    @Test
    fun queryParameterOverrideReplacesTimeoutsWithoutBreakingUrlStructure() {
        val url = "jdbc:postgresql://localhost:5432/test" +
            "?connectTimeout=10&loginTimeout=30&socketTimeout=300&applicationName=fukurou%20test"

        val overridden = url.withJdbcQueryParameters(
            mapOf(
                TEST_POSTGRES_CONNECT_TIMEOUT_KEY to "2",
                TEST_POSTGRES_SOCKET_TIMEOUT_KEY to "2",
            ),
        )

        assertEquals(1, overridden.count { character -> character == '?' })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("connectTimeout=2") })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("socketTimeout=2") })
        assertEquals(1, overridden.split('&').count { parameter -> parameter.contains("loginTimeout=30") })
        assertEquals(true, overridden.endsWith("applicationName=fukurou%20test"))
    }
}

/** URL parameter を container 起動なしで観測する test double。 */
private class InspectablePostgresContainer :
    BoundedTestPostgresContainer<InspectablePostgresContainer>("postgres:16-alpine") {
    fun configuredUrlParameters(): Map<String, String> = urlParameters.toMap()
}
