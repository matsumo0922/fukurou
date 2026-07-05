package me.matsumo.fukurou

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import java.io.File

/**
 * WebUI の filesystem 配信 root を指定する環境変数名。
 */
private const val FUKUROU_WEB_ROOT_ENV = "FUKUROU_WEB_ROOT"

/**
 * production image 内で Vite build output を置く既定 path。
 */
private const val DEFAULT_WEB_ROOT = "/app/web"

/**
 * SPA fallback で返す entrypoint file 名。
 */
private const val WEB_INDEX_FILE = "index.html"

/**
 * SPA fallback から保護する API / docs の path prefix。
 */
private val ProtectedWebFallbackPrefixes = listOf(
    "/ops",
    "/evaluation",
    "/health",
    "/health/live",
    "/health/ready",
    "/revision",
    "/swagger",
    "/openapi.json",
)

/**
 * 環境変数から WebUI の filesystem 配信 root を取得する。
 */
internal fun webRootFromEnv(): File {
    val configuredWebRoot = System.getenv(FUKUROU_WEB_ROOT_ENV)?.trim()
    val webRootPath = configuredWebRoot?.ifEmpty { DEFAULT_WEB_ROOT } ?: DEFAULT_WEB_ROOT

    return File(webRootPath)
}

/**
 * WebUI の実ファイル、または non-API GET 向けの SPA fallback を応答する。
 */
internal suspend fun ApplicationCall.respondWebStaticFallback(webRoot: File?): Boolean {
    if (request.httpMethod != HttpMethod.Get) {
        return false
    }

    val resolvedWebRoot = webRoot?.canonicalFile ?: return false

    if (!resolvedWebRoot.isDirectory) {
        return false
    }

    val requestPath = request.path()
    val existingFile = requestPath.toExistingWebFile(resolvedWebRoot)

    if (existingFile != null) {
        respondFile(existingFile)
        return true
    }

    if (requestPath.isProtectedWebFallbackPath()) {
        return false
    }

    val indexFile = File(resolvedWebRoot, WEB_INDEX_FILE)

    if (!indexFile.isFile) {
        return false
    }

    respondFile(indexFile)
    return true
}

/**
 * request path に対応する WebUI 実ファイルを返す。path traversal は canonical path で拒否する。
 */
private fun String.toExistingWebFile(webRoot: File): File? {
    val relativePath = trimStart('/')
    val requestedPath = relativePath.ifEmpty { WEB_INDEX_FILE }
    val requestedFile = File(webRoot, requestedPath).canonicalFile

    if (!requestedFile.isInside(webRoot)) {
        return null
    }

    if (!requestedFile.isFile) {
        return null
    }

    return requestedFile
}

/**
 * 指定 file が WebUI root 配下にあるかを返す。
 */
private fun File.isInside(directory: File): Boolean {
    return toPath().startsWith(directory.toPath())
}

/**
 * SPA fallback で隠してはいけない API / docs path かを返す。
 */
private fun String.isProtectedWebFallbackPath(): Boolean {
    return ProtectedWebFallbackPrefixes.any { protectedPath -> matchesProtectedWebFallbackPath(protectedPath) }
}

/**
 * API / docs prefix と request path の一致を返す。
 */
private fun String.matchesProtectedWebFallbackPath(protectedPath: String): Boolean {
    val isExactPath = this == protectedPath
    val isChildPath = startsWith("$protectedPath/")

    return isExactPath || isChildPath
}
