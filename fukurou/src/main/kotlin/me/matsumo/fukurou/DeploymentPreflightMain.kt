package me.matsumo.fukurou

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.matsumo.fukurou.trading.daemon.LlmLaunchReleaseBarrier
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** deploy candidate が production mutation 前に実行する unprivileged foundation preflight。 */
object DeploymentPreflightMain {
    /** allowlist 済み hook と signed one-shot token だけを実行する。 */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[0] == "foundation") {
            "UNSUPPORTED_DEPLOYMENT_PREFLIGHT"
        }
        val tokenBytes = Files.readAllBytes(Path.of(args[1]))
        verifySignature(
            tokenBytes = tokenBytes,
            signatureBytes = Files.readAllBytes(Path.of(args[2])),
            publicKeyPem = Files.readString(Path.of(args[3])),
        )
        verifyToken(tokenBytes, System.getenv(), Instant.now().epochSecond)
        check(!LlmLaunchReleaseBarrier.PREFILTER_ACTIVATION_RELEASED) {
            "PREFILTER_RELEASE_BARRIER_OPEN"
        }
        check(System.getenv("DB_URL") == null && System.getenv("DB_PASSWORD") == null) {
            "CANARY_PRODUCTION_DATABASE_MOUNTED"
        }

        println("FOUNDATION_PREFLIGHT_V1 OK")
    }

    internal fun verifySignature(
        tokenBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKeyPem: String,
    ) {
        val encodedKey = publicKeyPem
            .lineSequence()
            .filterNot { line -> line.startsWith("-----") }
            .joinToString("")
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)),
        )
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(tokenBytes)
        require(verifier.verify(signatureBytes)) { "INVALID_CANARY_TOKEN_SIGNATURE" }
    }

    internal fun verifyToken(
        tokenBytes: ByteArray,
        environment: Map<String, String>,
        nowEpochSecond: Long,
    ) {
        val token = Json.parseToJsonElement(tokenBytes.decodeToString()).jsonObject
        require(token.getValue("profile").jsonPrimitive.content == "CANARY_ONLY") {
            "INVALID_CANARY_PROFILE"
        }
        val allowedHookIds = token.getValue("allowedHookIds").jsonArray
            .map { element -> element.jsonPrimitive.content }
        require(allowedHookIds == listOf("FOUNDATION_PREFLIGHT_V1")) {
            "INVALID_CANARY_HOOK_SET"
        }
        require(token.getValue("contractHash").jsonPrimitive.content == environment["FUKUROU_CANDIDATE_CATALOG_HASH"]) {
            "INVALID_CANARY_CONTRACT_BINDING"
        }
        require(token.getValue("candidateSha").jsonPrimitive.content == environment["FUKUROU_REVISION"]) {
            "INVALID_CANARY_SHA_BINDING"
        }
        val candidateDigest = token.getValue("candidateDigest").jsonPrimitive.content
        require(candidateDigest.matches(IMAGE_DIGEST_PATTERN) && candidateDigest == environment["FUKUROU_CANDIDATE_DIGEST"]) {
            "INVALID_CANARY_DIGEST_BINDING"
        }
        require(token.getValue("namespaceId").jsonPrimitive.content.matches(NAMESPACE_PATTERN)) {
            "INVALID_CANARY_NAMESPACE"
        }
        require(token.getValue("generation").jsonPrimitive.long >= 0) {
            "INVALID_CANARY_GENERATION"
        }
        require(token.getValue("nonce").jsonPrimitive.content.matches(NONCE_PATTERN)) {
            "INVALID_CANARY_NONCE"
        }
        val issuedAt = token.getValue("issuedAt").jsonPrimitive.long
        val expiresAt = token.getValue("expiresAt").jsonPrimitive.long
        val validLifetime = issuedAt <= nowEpochSecond && expiresAt > nowEpochSecond
        val validMaximumLifetime = expiresAt - issuedAt <= MAX_TOKEN_LIFETIME_SECONDS
        require(validLifetime && validMaximumLifetime) {
            "EXPIRED_CANARY_TOKEN"
        }
    }
}

private const val MAX_TOKEN_LIFETIME_SECONDS = 15 * 60L
private val IMAGE_DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")
private val NONCE_PATTERN = Regex("[0-9a-f]{64}")
private val NAMESPACE_PATTERN = Regex("canary-[a-zA-Z0-9._-]{1,96}")
