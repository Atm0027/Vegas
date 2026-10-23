/**
 * Vegas sync: pairing secrets. The PC shows a 6-digit code; the phone proves it knows the code
 * for the exact certificate it is talking to, so a device in the middle cannot pair in its place
 * without also knowing the code. The PC answers with a long random token for later requests.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Pairing {
    private val random = SecureRandom()

    fun proof(code: String, certSha256: ByteArray, nonce: ByteArray): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(certSha256)
            doFinal(nonce)
        }
    }

    fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun newNonce(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /** Constant-time comparison so a wrong proof or token leaks nothing through timing. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
