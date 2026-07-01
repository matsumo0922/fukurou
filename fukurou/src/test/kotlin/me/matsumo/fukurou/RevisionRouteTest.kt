package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * revision route の挙動を検証するテスト。
 */
class RevisionRouteTest {

    @Test
    fun revision_returns_injected_commit_hash_as_plain_text() = testApplication {
        application {
            module(
                readinessProbe = { true },
                revision = "0123456789abcdef",
            )
        }

        val response = client.get("/revision")
        val responseContentType = ContentType.parse(response.headers[HttpHeaders.ContentType].orEmpty())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Plain, responseContentType.withoutParameters())
        assertEquals("0123456789abcdef", response.bodyAsText())
    }

    @Test
    fun revision_returns_unknown_when_revision_is_blank() = testApplication {
        application {
            module(
                readinessProbe = { true },
                revision = "   ",
            )
        }

        val response = client.get("/revision")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("unknown", response.bodyAsText())
    }
}
