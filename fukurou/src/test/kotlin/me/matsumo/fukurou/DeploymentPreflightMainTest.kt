package me.matsumo.fukurou

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** DeploymentPreflightMain の allowlisted hook contract を検証するテスト。 */
class DeploymentPreflightMainTest {
    @Test
    fun `unknown hook is rejected before reading candidate inputs`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentPreflightMain.main(arrayOf("arbitrary-command"))
        }
    }

    @Test
    fun signedTokenRequiresExactCandidateBindingsAndLifetime() {
        val token = token(expiresAt = 1_900)

        DeploymentPreflightMain.verifyToken(token, tokenEnvironment(), 1_100)
        assertFailsWith<IllegalArgumentException> {
            DeploymentPreflightMain.verifyToken(token, tokenEnvironment() + ("FUKUROU_REVISION" to "other"), 1_100)
        }
        assertFailsWith<IllegalArgumentException> {
            DeploymentPreflightMain.verifyToken(token, tokenEnvironment(), 1_900)
        }
    }

    @Test
    fun signatureVerificationRejectsMutation() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val token = token(expiresAt = 1_900)
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(token)
        }
        val signature = signer.sign()
        val publicKey = """
            -----BEGIN PUBLIC KEY-----
            ${Base64.getEncoder().encodeToString(keyPair.public.encoded)}
            -----END PUBLIC KEY-----
        """.trimIndent()

        DeploymentPreflightMain.verifySignature(token, signature, publicKey)
        assertFailsWith<IllegalArgumentException> {
            DeploymentPreflightMain.verifySignature(token + byteArrayOf(0), signature, publicKey)
        }
    }

    @Test
    fun processArtifactHookIsAcceptedBeforeFoundation() {
        val token = token(expiresAt = 1_900, hooks = listOf("PROCESS_ARTIFACT_PREFLIGHT_V1", "FOUNDATION_PREFLIGHT_V1"))
        DeploymentPreflightMain.verifyToken(token, tokenEnvironment(), 1_100)
    }

    private fun token(expiresAt: Long, hooks: List<String> = listOf("FOUNDATION_PREFLIGHT_V1")): ByteArray = """
        {
          "profile":"CANARY_ONLY",
          "namespaceId":"canary-fixture",
          "generation":7,
          "allowedHookIds":[${hooks.joinToString(",") { "\"$it\"" }}],
          "candidateSha":"candidate-sha",
          "candidateDigest":"${"sha256:" + "a".repeat(64)}",
          "contractHash":"catalog-hash",
          "nonce":"${"b".repeat(64)}",
          "issuedAt":1000,
          "expiresAt":$expiresAt
        }
    """.trimIndent().encodeToByteArray()

    private fun tokenEnvironment(): Map<String, String> = mapOf(
        "FUKUROU_CANDIDATE_CATALOG_HASH" to "catalog-hash",
        "FUKUROU_REVISION" to "candidate-sha",
        "FUKUROU_CANDIDATE_DIGEST" to ("sha256:" + "a".repeat(64)),
    )
}
