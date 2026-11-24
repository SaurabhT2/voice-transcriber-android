package com.saurabhtiwari.voicetranscriber

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers (S256). */
internal object Pkce {
    data class Pair(val verifier: String, val challenge: String)

    fun generate(random: SecureRandom = SecureRandom()): Pair {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        val verifier = base64Url(bytes) // 64 URL-safe characters, within the 43..128 limit
        return Pair(verifier, challengeFor(verifier))
    }

    fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
