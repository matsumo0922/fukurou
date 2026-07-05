package me.matsumo.fukurou

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WebUI static delivery と SPA fallback の route contract を検証するテスト。
 */
class WebStaticDeliveryTest {

    @Test
    fun webStaticDelivery_returnsAssetAndFallbackIndex() = testApplication {
        val webRoot = createWebRoot()

        application {
            module(
                readinessProbe = { true },
                webRoot = webRoot,
            )
        }

        val rootResponse = client.get("/")
        val assetResponse = client.get("/assets/app.js")
        val deepLinkResponse = client.get("/dashboard/evaluation")

        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertTrue(rootResponse.bodyAsText().contains("Fukurou Web Test"))
        assertEquals(HttpStatusCode.OK, assetResponse.status)
        assertEquals("console.log('fukurou web');", assetResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, deepLinkResponse.status)
        assertTrue(deepLinkResponse.bodyAsText().contains("Fukurou Web Test"))
    }

    @Test
    fun webStaticDelivery_doesNotShadowExistingApiRoutes() = testApplication {
        val webRoot = createWebRoot()

        application {
            module(
                readinessProbe = { true },
                revision = "web-delivery-test",
                webRoot = webRoot,
            )
        }

        val revisionResponse = client.get("/revision")
        val healthResponse = client.get("/health")
        val swaggerResponse = client.get("/swagger")
        val openApiResponse = client.get("/openapi.json")

        assertEquals(HttpStatusCode.OK, revisionResponse.status)
        assertEquals("web-delivery-test", revisionResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        assertTrue(healthResponse.bodyAsText().contains(""""service":"fukurou""""))
        assertEquals(HttpStatusCode.OK, swaggerResponse.status)
        assertTrue(swaggerResponse.bodyAsText().contains("Swagger UI"))
        assertEquals(HttpStatusCode.OK, openApiResponse.status)
        assertTrue(openApiResponse.bodyAsText().contains("Fukurou API"))
    }

    @Test
    fun webStaticDelivery_doesNotFallbackApiPrefixesOrNonGetRequests() = testApplication {
        val webRoot = createWebRoot()

        application {
            module(
                readinessProbe = { true },
                webRoot = webRoot,
            )
        }

        val unknownOpsResponse = client.get("/ops/unknown")
        val unknownEvaluationResponse = client.get("/evaluation/unknown")
        val unknownPostResponse = client.post("/settings")

        assertEquals(HttpStatusCode.NotFound, unknownOpsResponse.status)
        assertNotIndexHtml(unknownOpsResponse.bodyAsText())
        assertJsonNotFound(unknownOpsResponse.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, unknownEvaluationResponse.status)
        assertNotIndexHtml(unknownEvaluationResponse.bodyAsText())
        assertJsonNotFound(unknownEvaluationResponse.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, unknownPostResponse.status)
        assertNotIndexHtml(unknownPostResponse.bodyAsText())
        assertJsonNotFound(unknownPostResponse.bodyAsText())
    }

    @Test
    fun webStaticDelivery_doesNotServeFilesOutsideWebRoot() = testApplication {
        val webRoot = createWebRoot()
        val outsideFile = createOutsideWebRootFile(webRoot)

        application {
            module(
                readinessProbe = { true },
                webRoot = webRoot,
            )
        }

        val traversalResponse = client.get("/../${outsideFile.name}")
        val responseBody = traversalResponse.bodyAsText()

        assertEquals(HttpStatusCode.OK, traversalResponse.status)
        assertFalse(responseBody.contains(outsideFile.readText()))
        assertTrue(responseBody.contains("Fukurou Web Test"))
    }

    private fun createWebRoot(): File {
        val webRoot = createTempDirectory("fukurou-web-test").toFile()
        val assetsDirectory = File(webRoot, "assets")

        require(assetsDirectory.mkdirs())

        File(webRoot, "index.html").writeText("<!doctype html><title>Fukurou Web Test</title>")
        File(assetsDirectory, "app.js").writeText("console.log('fukurou web');")

        return webRoot
    }

    private fun assertNotIndexHtml(responseBody: String) {
        assertFalse(responseBody.contains("Fukurou Web Test"))
    }

    private fun assertJsonNotFound(responseBody: String) {
        assertTrue(responseBody.contains(""""message":"not found""""))
    }

    private fun createOutsideWebRootFile(webRoot: File): File {
        val outsideFile = File(webRoot.parentFile, "fukurou-outside-${System.nanoTime()}.txt")

        outsideFile.writeText("outside secret")

        return outsideFile
    }
}
